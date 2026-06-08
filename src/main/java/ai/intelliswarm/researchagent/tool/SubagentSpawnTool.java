package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.researchagent.agent.PermissionPrompter;
import ai.intelliswarm.researchagent.agent.Prompts;
import ai.intelliswarm.researchagent.agent.ToolRouter;
import ai.intelliswarm.researchagent.config.ResearchProperties;
import ai.intelliswarm.researchagent.eval.MetricsCollector;
import ai.intelliswarm.swarmai.agent.llm.LlmClient;
import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRequest;
import ai.intelliswarm.swarmai.agent.llm.LlmResponse;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spawns an isolated research sub-agent in-process.
 *
 * <p>The sub-agent gets:
 * <ul>
 *   <li>Its own ephemeral message history (parent context not shared)</li>
 *   <li>A restricted tool subset (caller specifies which tools by name)</li>
 *   <li>A role-specific system prompt (persona)</li>
 * </ul>
 *
 * <p>This is the "dynamic" core that replaces the static 5-stage pipeline — the
 * orchestrator can spawn any number of sub-agents in any order, adapting as it
 * learns what the evidence says.
 *
 * <p>Default research personas:
 * <ul>
 *   <li>{@code literature-scout} — searches PubMed/Arxiv/etc., downloads and ingests PDFs</li>
 *   <li>{@code evidence-appraiser} — queries RAG, classifies passages SUPPORTS/CONTRADICTS/NEUTRAL</li>
 *   <li>{@code synthesizer} — writes structured report sections from evidence summaries</li>
 * </ul>
 */
@Component
public class SubagentSpawnTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(SubagentSpawnTool.class);
    private static final int DEFAULT_MAX_TURNS = 40;

    private final LlmClient llm;
    private final ObjectProvider<ResearchToolset> toolsetProvider;
    private final ResearchProperties props;
    private final ToolRouter parentRouter;
    private final MetricsCollector metricsCollector;
    private final IngestLedger ingestLedger;
    private final RelevanceLedger relevanceLedger;

    private volatile Map<String, BaseTool> cachedToolsByName;

    @Autowired
    public SubagentSpawnTool(LlmClient llm,
                             ObjectProvider<ResearchToolset> toolsetProvider,
                             ResearchProperties props,
                             ToolRouter parentRouter,
                             MetricsCollector metricsCollector,
                             IngestLedger ingestLedger,
                             RelevanceLedger relevanceLedger) {
        this.llm = llm;
        this.toolsetProvider = toolsetProvider;
        this.props = props;
        this.parentRouter = parentRouter;
        this.metricsCollector = metricsCollector;
        this.ingestLedger = ingestLedger;
        this.relevanceLedger = relevanceLedger;
    }

    private Map<String, BaseTool> toolsByName() {
        Map<String, BaseTool> snapshot = cachedToolsByName;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (cachedToolsByName != null) return cachedToolsByName;
            Map<String, BaseTool> built = new HashMap<>();
            for (BaseTool t : toolsetProvider.getObject().tools()) {
                if (t instanceof SubagentSpawnTool) continue; // prevent nesting
                built.put(t.getFunctionName(), t);
            }
            cachedToolsByName = built;
            return built;
        }
    }

    @Override public String getFunctionName() { return "subagent_spawn"; }

    @Override
    public String getDescription() {
        return "Delegate a self-contained research task to a fresh sub-agent with its own history. "
             + "Use to parallelise or isolate investigation steps (search PubMed, appraise evidence, "
             + "synthesize a section). The sub-agent does NOT see the parent's conversation — make the "
             + "task fully self-contained. Returns the sub-agent's final report.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "orchestration"; }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }
    @Override public int getMaxResponseLength() { return 16_000; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String type = asString(parameters.get("type"), "literature-scout");
        String task = asString(parameters.get("task"), null);
        String persona = asString(parameters.get("persona"), defaultPersona(type));
        int maxTurns = parameters.get("max_turns") instanceof Number n
                ? Math.min(Math.max(1, n.intValue()), 60)
                : Math.min(DEFAULT_MAX_TURNS, props.getAgents().getSubagentMaxTurns());

        @SuppressWarnings("unchecked")
        List<String> requestedTools = parameters.get("tools") instanceof List<?> raw
                ? raw.stream().map(String::valueOf).toList()
                : defaultToolsFor(type);

        if (task == null || task.isBlank()) return "Error: 'task' is required";

        // ROOT-CAUSE FIX for the relevance gate-loop: deterministically inject the relevance verdicts
        // into every evidence-appraiser task. Previously the orchestrator LLM had to remember to copy
        // the approved labels into the task by hand; when it forgot (or copied a rejected paper), the
        // appraiser cited rejected sources, ReportWriteTool's relevance gate blocked the report, and the
        // run gate-looped. Injecting the whitelist + blocklist from RelevanceLedger here removes that
        // failure mode regardless of orchestrator behaviour.
        boolean isAppraiser = type != null && type.toLowerCase().contains("appraiser");
        if (isAppraiser) {
            task = injectRelevanceVerdicts(task);
        }

        List<BaseTool> tools = new ArrayList<>();
        for (String name : requestedTools) {
            // Strip OpenAI-style "functions." prefix that gpt-4o-mini sometimes hallucinates.
            String normalizedName = name.startsWith("functions.") ? name.substring("functions.".length()) : name;
            BaseTool t = toolsByName().get(normalizedName);
            if (t == null) {
                return "Error: unknown tool '" + name + "' — available: " + toolsByName().keySet();
            }
            tools.add(t);
        }

        log.info("Spawning sub-agent type='{}' max_turns={} tools={}", type, maxTurns, requestedTools);
        long subagentStart = System.currentTimeMillis();

        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(task));

        int turns = 0;
        String finalText = "";
        long inputTokens = 0, outputTokens = 0;
        // Anti-paralysis: track consecutive failures and total calls per tool.
        Map<String, Integer> consecutiveToolFailures = new HashMap<>();
        Map<String, Integer> totalToolCalls = new HashMap<>();
        // Per-tool call caps — prevent runaway loops inside a sub-agent.
        Map<String, Integer> toolCaps = Map.of(
                "europepmc_fulltext",          5,
                "pubmed_search",              20,
                "arxiv_search",               15,
                "openalex_search",            15,
                "semantic_scholar_search",    15,
                "web_search",                  6,
                "pdf_download",               60,
                "rag_ingest",                 60
        );

        while (turns++ < maxTurns) {
            LlmRequest.Builder rb = LlmRequest.builder()
                    .model(props.getModel().getPrimary())
                    .system(persona)
                    .maxOutputTokens(props.getModel().getMaxOutputTokens())
                    .tools(tools);
            for (LlmMessage m : history) rb.message(m);

            LlmResponse resp;
            try {
                resp = llm.send(rb.build());
            } catch (Exception e) {
                log.warn("Sub-agent LLM call failed: {}", e.getMessage());
                return "Sub-agent failed: " + e.getMessage();
            }

            inputTokens += resp.tokenUsage().inputTokens();
            outputTokens += resp.tokenUsage().outputTokens();

            if (!resp.needsToolExecution()) {
                finalText = resp.text() == null ? "" : resp.text();
                break;
            }

            history.add(LlmMessage.assistantWithToolCalls(resp.text(), resp.toolCalls()));
            // Collect nudges to inject AFTER all tool results — OpenAI requires every
            // tool_call_id in an assistant message to be responded to before any user message.
            List<String> pendingNudges = new ArrayList<>();
            for (LlmToolCall call : resp.toolCalls()) {
                // Enforce per-tool call cap before executing.
                int callCount = totalToolCalls.merge(call.toolName(), 1, Integer::sum);
                int cap = toolCaps.getOrDefault(call.toolName(), Integer.MAX_VALUE);
                if (callCount > cap) {
                    String capMsg = "[system] tool '" + call.toolName() + "' has reached its call limit ("
                            + cap + ") for this sub-agent run. Stop using it and wrap up.";
                    log.info("     ⛔ {} · {} cap reached ({})", type, call.toolName(), cap);
                    history.add(LlmMessage.toolResult(call.id(), capMsg));
                    continue;
                }

                // Skip duplicate rag_ingest — scouts sometimes re-ingest the same paper after
                // an anti-paralysis nudge or when multiple scouts cover overlapping queries.
                if ("rag_ingest".equals(call.toolName())) {
                    Object src = call.arguments() == null ? null : call.arguments().get("source");
                    if (src != null && ingestLedger.contains(String.valueOf(src))) {
                        String skipMsg = "Already ingested: " + src + " — skipping duplicate.";
                        log.debug("Sub-agent skipping duplicate rag_ingest for {}", src);
                        history.add(LlmMessage.toolResult(call.id(), skipMsg));
                        continue;
                    }
                }

                BaseTool tool = toolsByName().get(call.toolName());
                String result;
                long t0 = System.currentTimeMillis();
                if (tool == null) {
                    result = "Error: unknown tool '" + call.toolName() + "'";
                } else {
                    // Route through the session's active permission policy — NOT a blanket
                    // allow. In interactive mode this prompts the user (and honours their
                    // "always this session" choices); in auto-approve/batch mode the session
                    // prompter is allow-all. This preserves the interactive permission model:
                    // approving subagent_spawn does not silently grant downstream write tools.
                    Object out = parentRouter.executeWithRouting(tool, call.arguments(),
                            parentRouter.sessionPrompter());
                    result = out == null ? "" : out.toString();
                }
                long elapsed = System.currentTimeMillis() - t0;
                // Propagate the sub-agent's tool call to the parent metrics so
                // pdf_download / rag_ingest / rag_search counts are not lost.
                metricsCollector.onToolCallEnd(call, result, elapsed);
                // Record successful ingestions so the report gates can verify them.
                if ("rag_ingest".equals(call.toolName()) && !result.startsWith("Error")) {
                    Object src = call.arguments() == null ? null : call.arguments().get("source");
                    if (src != null) ingestLedger.record(String.valueOf(src));
                }
                if (log.isInfoEnabled()) {
                    boolean ok = !result.startsWith("Error") && !result.startsWith("Sub-agent failed");
                    String icon = ok ? "✓" : "✗";
                    String note = ok ? shortNote(call.toolName(), result)
                                     : result.replaceAll("\\s+", " ").trim();
                    if (note.length() > 90) note = note.substring(0, 90) + "…";
                    log.info("     {} {} · {}  ({}ms)", icon, type, friendlyLabel(call.toolName(), note), elapsed);
                }
                history.add(LlmMessage.toolResult(call.id(), result));

                // Track failures — nudge injected after all tool results to keep message order valid.
                if (result.startsWith("Error") || result.startsWith("Sub-agent failed")) {
                    int failures = consecutiveToolFailures.merge(call.toolName(), 1, Integer::sum);
                    if (failures >= 3) {
                        String nudge = "[system] Tool '" + call.toolName() + "' has failed "
                                + failures + " consecutive times and is unavailable. "
                                + "Do NOT call it again. Use alternative tools or wrap up with what you have.";
                        log.warn("     ⚠ {} anti-paralysis: {} failed {} times — injecting nudge",
                                type, call.toolName(), failures);
                        pendingNudges.add(nudge);
                        consecutiveToolFailures.remove(call.toolName());
                    }
                } else {
                    consecutiveToolFailures.remove(call.toolName());
                }
            }
            // Safe to add user messages now — all tool_call_ids have been responded to.
            for (String nudge : pendingNudges) {
                history.add(LlmMessage.user(nudge));
            }
        }

        // ── Zero-ingest enforcement for literature-scouts ─────────────────────
        // If the scout ran searches but made 0 rag_ingest calls, it did a 2-turn early exit
        // (search-then-report) without fetching or ingesting anything. Enforce abstract fallback.
        int scoutRagIngests = totalToolCalls.getOrDefault("rag_ingest", 0);
        int scoutSearches   = totalToolCalls.getOrDefault("pubmed_search", 0)
                            + totalToolCalls.getOrDefault("openalex_search", 0)
                            + totalToolCalls.getOrDefault("arxiv_search", 0)
                            + totalToolCalls.getOrDefault("semantic_scholar_search", 0);
        boolean isScout = type != null && (type.toLowerCase().contains("literature") || type.toLowerCase().contains("scout"));
        if (isScout && scoutRagIngests == 0 && scoutSearches >= 2 && turns <= maxTurns) {
            log.warn("Scout zero-ingest detected ({} searches, 0 rag_ingest) — enforcing abstract-fallback", scoutSearches);
            String enforceMsg = "[INGEST ENFORCEMENT] You searched " + scoutSearches + " time(s) but called rag_ingest 0 times. "
                    + "This is a protocol violation — you must ingest paper abstracts. "
                    + "Go back to your search results and call rag_ingest for each paper: "
                    + "use the source label format: pubmed:PMID:abstract-only:short-title "
                    + "and pass the abstract text as the 'content' parameter. "
                    + "Call rag_ingest at least 3 times now before writing your report. "
                    + "Do NOT write more text — call rag_ingest immediately.";
            history.add(LlmMessage.user(enforceMsg));
            int enforceTurns = 0;
            int enforceMax = 8; // max extra turns for abstract ingestion
            while (enforceTurns++ < enforceMax) {
                LlmRequest.Builder reb = LlmRequest.builder()
                        .model(props.getModel().getPrimary())
                        .system(persona)
                        .maxOutputTokens(props.getModel().getMaxOutputTokens())
                        .tools(tools);
                for (LlmMessage m : history) reb.message(m);
                LlmResponse resp2;
                try {
                    resp2 = llm.send(reb.build());
                } catch (Exception e) {
                    log.warn("Enforce-ingest LLM call failed: {}", e.getMessage());
                    break;
                }
                inputTokens += resp2.tokenUsage().inputTokens();
                outputTokens += resp2.tokenUsage().outputTokens();
                if (!resp2.needsToolExecution()) {
                    finalText = resp2.text() == null ? "" : resp2.text();
                    break;
                }
                history.add(LlmMessage.assistantWithToolCalls(resp2.text(), resp2.toolCalls()));
                for (LlmToolCall call2 : resp2.toolCalls()) {
                    // Process tool calls (same pattern as main loop)
                    if ("rag_ingest".equals(call2.toolName())) {
                        Object src2 = call2.arguments() == null ? null : call2.arguments().get("source");
                        if (src2 != null && ingestLedger.contains(String.valueOf(src2))) {
                            history.add(LlmMessage.toolResult(call2.id(), "Already ingested: " + src2));
                            continue;
                        }
                    }
                    BaseTool tool2 = toolsByName().get(call2.toolName());
                    String result2 = tool2 == null ? "Error: unknown tool '" + call2.toolName() + "'"
                            : (parentRouter.executeWithRouting(tool2, call2.arguments(),
                                    parentRouter.sessionPrompter()) + "");
                    metricsCollector.onToolCallEnd(call2, result2, 0);
                    if ("rag_ingest".equals(call2.toolName()) && !result2.startsWith("Error")) {
                        Object src2 = call2.arguments() == null ? null : call2.arguments().get("source");
                        if (src2 != null) {
                            ingestLedger.record(String.valueOf(src2));
                            totalToolCalls.merge("rag_ingest", 1, Integer::sum);
                        }
                    }
                    history.add(LlmMessage.toolResult(call2.id(), result2));
                }
                // Check if scout now has some ingests
                if (totalToolCalls.getOrDefault("rag_ingest", 0) >= 3) {
                    log.info("Scout enforce-ingest: {} papers ingested — stopping enforce loop", totalToolCalls.get("rag_ingest"));
                    break;
                }
            }
            turns += enforceTurns;
        }

        long subagentElapsed = System.currentTimeMillis() - subagentStart;
        metricsCollector.recordSubagent(type, turns, inputTokens, outputTokens, subagentElapsed);

        return "Sub-agent [" + type + "] finished in " + turns + " turn"
                + (turns == 1 ? "" : "s") + " (tokens: " + inputTokens + " in / "
                + outputTokens + " out)\n---\n" + finalText;
    }

    private static String defaultPersona(String type) {
        String loaded = Prompts.load("subagent-" + type + ".md", null);
        if (loaded != null) return loaded;
        return switch (type.toLowerCase()) {
            case "literature-scout" -> """
                    You are a medical literature scout. Search PubMed, Arxiv, Semantic Scholar, and OpenAlex
                    for papers relevant to the given topic. For each paper found:
                    1. Call pdf_download to retrieve the full text.
                    2. Call rag_ingest with a unique source label (format: source:PMID_or_ID:short-title).
                    Ingest up to 5 papers. Never fabricate PMIDs, DOIs, or URLs — copy them verbatim from
                    tool results. If a download fails, skip it and move on. Report all successfully ingested
                    source labels.""";
            case "evidence-appraiser" -> """
                    You are an evidence appraiser. Query the RAG store with rag_search using 2-4 different
                    phrasings of the hypothesis. For each retrieved passage, classify it as:
                    SUPPORTS / CONTRADICTS / NEUTRAL — and quote the key sentence. Always cite the source label.
                    Conclude with an overall verdict: SUPPORTED / CONTRADICTED / INCONCLUSIVE, and a one-sentence
                    justification. If evidence is insufficient, say INCONCLUSIVE — do not overstate.""";
            case "synthesizer" -> """
                    You are a research synthesizer. Given a set of evidence summaries, write a structured
                    section of a research report in markdown. Include: the hypothesis, supporting evidence
                    (with verbatim quotes and source citations), contradicting evidence (same format),
                    and a verdict paragraph. Write for a skeptical scientific audience. Every claim needs a
                    citation.""";
            default -> "You are a research sub-agent. Complete the assigned task using the supplied tools "
                     + "and report your findings concisely with citations.";
        };
    }

    private static List<String> defaultToolsFor(String type) {
        return switch (type.toLowerCase()) {
            case "literature-scout" -> List.of("pubmed_search", "arxiv_search",
                    "semantic_scholar_search", "openalex_search", "web_search",
                    "europepmc_fulltext", "unpaywall_lookup", "pdf_download", "rag_ingest");
            case "evidence-appraiser" -> List.of("rag_search");
            case "synthesizer" -> List.of("rag_search");
            default -> List.of("rag_search");
        };
    }

    private static String asString(Object v, String dflt) {
        return v == null ? dflt : String.valueOf(v);
    }

    /**
     * Appends the session's relevance verdicts to an evidence-appraiser task as a hard whitelist
     * (APPROVED) plus an explicit blocklist (REJECTED — DO NOT CITE), pulled from the
     * {@link RelevanceLedger}. The appraiser prompt already honours an "APPROVED SOURCE LABELS"
     * section; this guarantees it is always present and accurate even when the orchestrator forgot
     * to copy it. Idempotent: if the task already contains an approved-labels block we leave it.
     */
    private String injectRelevanceVerdicts(String task) {
        return buildRelevanceInjection(task, relevanceLedger.relevant(), relevanceLedger.rejected());
    }

    /** Pure, testable core of {@link #injectRelevanceVerdicts(String)}. Package-private for tests. */
    static String buildRelevanceInjection(String task, List<String> approved, List<String> rejected) {
        // Nothing screened yet (or the orchestrator already embedded the list) — leave the task as-is.
        if (approved.isEmpty() && rejected.isEmpty()) return task;
        if (task.toUpperCase().contains("APPROVED SOURCE LABELS")) return task;

        StringBuilder sb = new StringBuilder(task);
        sb.append("\n\n--- RELEVANCE SCREENING RESULTS (injected by the system — authoritative) ---\n");
        sb.append("APPROVED SOURCE LABELS (from relevance_filter — cite ONLY these in Supporting/Contradicting Evidence):\n");
        if (approved.isEmpty()) {
            sb.append("- (none approved — if you have no approved sources, the verdict is INSUFFICIENT EVIDENCE)\n");
        } else {
            for (String id : approved) sb.append("- ").append(id).append("\n");
        }
        if (!rejected.isEmpty()) {
            sb.append("\nREJECTED — DO NOT CITE these in Supporting or Contradicting Evidence "
                    + "(wrong species / off-topic / screened out). Citing any of these will block the report:\n");
            for (String id : rejected) sb.append("- ").append(id).append("\n");
        }
        return sb.toString();
    }

    private static String shortNote(String tool, String result) {
        if ("rag_ingest".equals(tool)) {
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("source: ?(\\S+)").matcher(result);
            if (mm.find()) return mm.group(1);
        }
        return "";
    }

    private static String friendlyLabel(String tool, String note) {
        String base = switch (tool) {
            case "openalex_search"          -> "searched OpenAlex";
            case "pubmed_search"            -> "searched PubMed";
            case "arxiv_search"             -> "searched arXiv";
            case "semantic_scholar_search"  -> "searched Semantic Scholar";
            case "web_search"               -> "searched the web";
            case "europepmc_fulltext"       -> "fetched full text";
            case "unpaywall_lookup"         -> "looked up OA PDF";
            case "pdf_download"             -> "downloaded PDF";
            case "rag_ingest"               -> "ingested paper";
            case "rag_search"               -> "queried RAG";
            case "relevance_filter"         -> "screened relevance";
            case "citation_validate"        -> "validated citations";
            default                         -> tool;
        };
        return note.isBlank() ? base : base + " — " + note;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", Map.of("type", "string",
                "description", "Sub-agent role: 'literature-scout', 'evidence-appraiser', 'synthesizer', "
                             + "or any custom label. Selects the default persona and tool set.",
                "default", "literature-scout"));
        props.put("task", Map.of("type", "string",
                "description", "Self-contained task description. The sub-agent does NOT see the parent's "
                             + "conversation — include all necessary context here."));
        props.put("persona", Map.of("type", "string",
                "description", "Optional custom system prompt overriding the type default."));
        props.put("tools", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "Tool names the sub-agent may use. Defaults to role-appropriate tools."));
        props.put("max_turns", Map.of("type", "integer",
                "description", "Max reasoning turns (default 15, cap 30)."));
        schema.put("properties", props);
        schema.put("required", new String[]{"task"});
        return schema;
    }
}
