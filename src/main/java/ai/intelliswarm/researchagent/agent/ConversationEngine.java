package ai.intelliswarm.researchagent.agent;

import ai.intelliswarm.researchagent.config.ResearchProperties;
import ai.intelliswarm.researchagent.tool.IngestLedger;
import ai.intelliswarm.researchagent.tool.ResearchToolset;
import ai.intelliswarm.swarmai.agent.llm.LlmClient;
import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRequest;
import ai.intelliswarm.swarmai.agent.llm.LlmResponse;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The orchestrator's multi-turn agentic loop.
 *
 * <p>For each user message:
 * <ol>
 *   <li>Append the user message; build an {@link LlmRequest} with the orchestrator
 *       system prompt + full history + the research toolset.</li>
 *   <li>Send to the {@link LlmClient}.</li>
 *   <li>If the response wants tools: run each (permission-routed), append the
 *       results, and loop.</li>
 *   <li>Else: append the assistant text and return it.</li>
 * </ol>
 *
 * <p>The orchestrator decomposes the work dynamically — it maintains a living plan
 * via {@code todo_write} and delegates sub-investigations via {@code subagent_spawn},
 * so the workflow is model-decided rather than a fixed pipeline.
 */
@Component
public class ConversationEngine {

    private static final Logger log = LoggerFactory.getLogger(ConversationEngine.class);

    private static final String FALLBACK_SYSTEM = """
            You are a lead medical research investigator. Maintain a plan with todo_write,
            delegate searches and appraisal to sub-agents via subagent_spawn, gather evidence
            that supports AND contradicts the hypothesis, cite every claim, and finish by
            calling report_write.""";

    /**
     * Tools the orchestrator LLM may call directly.
     * All literature search, download, and ingest operations must go via subagent_spawn —
     * the orchestrator never calls pubmed_search, pdf_download, rag_ingest etc. directly.
     */
    private static final Set<String> ORCHESTRATOR_TOOL_ALLOWLIST = Set.of(
            "todo_write", "subagent_spawn",
            "rag_status", "relevance_filter", "citation_validate",
            "csv_analysis", "report_write"
    );

    private final LlmClient llm;
    private final List<BaseTool> tools;        // restricted to ORCHESTRATOR_TOOL_ALLOWLIST
    private final List<BaseTool> allTools;     // full set — still executable if called by name
    private final Map<String, BaseTool> toolsByName;
    private final Session session;
    private final ToolRouter router;
    private final ResearchProperties props;
    private final String systemPrompt;
    private final IngestLedger ingestLedger;
    private final ai.intelliswarm.researchagent.tool.RelevanceLedger relevanceLedger;

    public ConversationEngine(LlmClient llm,
                              ResearchToolset toolset,
                              Session session,
                              ToolRouter router,
                              ResearchProperties props,
                              IngestLedger ingestLedger,
                              ai.intelliswarm.researchagent.tool.RelevanceLedger relevanceLedger) {
        this.llm = llm;
        this.allTools = toolset.tools();
        // Restrict what the orchestrator LLM *sees* to the allowlist — all search/download/ingest
        // tools are intentionally hidden so the model cannot bypass subagent_spawn.
        this.tools = allTools.stream()
                .filter(t -> ORCHESTRATOR_TOOL_ALLOWLIST.contains(t.getFunctionName()))
                .toList();
        this.toolsByName = new HashMap<>();
        for (BaseTool t : allTools) toolsByName.put(t.getFunctionName(), t); // full map for routing
        this.session = session;
        this.router = router;
        this.props = props;
        this.ingestLedger = ingestLedger;
        this.relevanceLedger = relevanceLedger;
        this.systemPrompt = Prompts.load("orchestrator.md", FALLBACK_SYSTEM);
    }

    public List<BaseTool> tools() { return tools; }

    public TurnResult runTurn(String userMessage, PermissionPrompter prompter, ToolCallObserver observer) {
        session.append(LlmMessage.user(userMessage == null ? "" : userMessage));

        int maxIterations = props.getAgents().getMaxTurnsPerTask();
        int iterations = 0;
        String finalText = "";
        int consecutivePlanning = 0; // anti-paralysis: count back-to-back todo_write-only turns
        // Tracks todo_write calls since the last non-planning action (subagent_spawn / report_write / etc.).
        // Resets to 0 whenever any non-todo_write tool succeeds — this allows re-planning between
        // investigations in a multi-turn REPL session while still blocking pure planning loops.
        int todoWritesSinceLastAction = 0;
        int scoutsSpawned = 0; // track literature-scout subagents for minimum-count gate
        int papersAtLastRelevanceFilter = 0; // track how many papers relevance_filter last saw
        int reportWriteGateBlocks = 0; // gate-loop guard: hard-block report_write after 5 gate rejections
        Map<String, Integer> consecutiveToolFailures = new HashMap<>(); // tool → consecutive error count

        while (iterations++ < maxIterations) {
            LlmRequest req = buildRequest();
            LlmResponse resp;
            try {
                resp = llm.send(req);
            } catch (Exception e) {
                String summary = summarize(e);
                log.warn("LLM call failed: {}", summary);
                session.append(LlmMessage.assistant("(error contacting model: " + summary + ")"));
                return new TurnResult("Error contacting model: " + summary, iterations, true);
            }

            session.recordUsage(resp.tokenUsage());

            if (!resp.needsToolExecution()) {
                String text = resp.text() == null ? "" : resp.text();
                if (!text.isBlank()) session.append(LlmMessage.assistant(text));
                return new TurnResult(text, iterations, false);
            }

            session.append(LlmMessage.assistantWithToolCalls(resp.text(), resp.toolCalls()));
            List<LlmToolCall> calls = resp.toolCalls();
            List<String> failedTools = new ArrayList<>();
            for (LlmToolCall call : calls) {
                // Hard cap: todo_write blocked after 3 consecutive planning calls with no research in between.
                // Resets when any non-todo_write tool succeeds so re-planning between investigations is allowed.
                if ("todo_write".equals(call.toolName()) && todoWritesSinceLastAction >= 3) {
                    String blocked = "⛔ BLOCKED[todo_write-consecutive-cap]: todo_write has been called "
                            + todoWritesSinceLastAction + " times since the last research action "
                            + "— you are stuck in a planning loop. "
                            + "Your ONLY valid next action is subagent_spawn with type='literature-scout'. "
                            + "If you have truly exhausted search strategies, call report_write with INSUFFICIENT EVIDENCE. "
                            + "todo_write will be available again after a non-planning tool succeeds.";
                    log.warn("todo_write consecutive-cap ({} since last action) — blocking", todoWritesSinceLastAction);
                    session.append(LlmMessage.toolResult(call.id(), blocked));
                    if (observer != null) observer.onToolCallEnd(call, blocked, 0);
                    failedTools.add(call.toolName());
                    consecutiveToolFailures.merge(call.toolName(), 1, Integer::sum);
                    continue;
                }
                if ("todo_write".equals(call.toolName())) {
                    todoWritesSinceLastAction++;
                }

                // Relevance filter timing nudge: track when it's called and whether more papers
                // were added since the last relevance_filter call.
                if ("relevance_filter".equals(call.toolName())) {
                    // Check if more papers were ingested since the last relevance_filter
                    long currentPapers = ingestLedger.all().size();
                    if (papersAtLastRelevanceFilter > 0 && currentPapers > papersAtLastRelevanceFilter + 2) {
                        log.info("relevance_filter called with more papers than last time ({} vs {}) — good re-screen",
                                currentPapers, papersAtLastRelevanceFilter);
                    }
                    papersAtLastRelevanceFilter = (int) currentPapers;
                }
                // Minimum-scout gate: HARD BLOCK relevance_filter until at least 3 scouts have run.
                // Without 3 scouts, the paper corpus is too narrow for meaningful screening.
                if ("relevance_filter".equals(call.toolName()) && scoutsSpawned < 3) {
                    int needed = 3 - scoutsSpawned;
                    String blocked = "⛔ BLOCKED[min-scout-gate]: relevance_filter cannot be called yet — "
                            + "you have only spawned " + scoutsSpawned + " literature-scout(s). "
                            + "Minimum 3 scouts required before screening. "
                            + "Spawn " + needed + " more literature-scout(s) with DIFFERENT sub-concepts "
                            + "or databases (e.g. if first scout used PubMed, next should also use OpenAlex). "
                            + "Each scout must cover a distinct angle of the hypothesis. "
                            + "After " + needed + " more scouts complete, call relevance_filter once with ALL papers.";
                    log.warn("relevance_filter BLOCKED after only {} scouts (need 3) — scout count: {}",
                            scoutsSpawned, scoutsSpawned);
                    session.append(LlmMessage.toolResult(call.id(), blocked));
                    if (observer != null) observer.onToolCallEnd(call, blocked, 0);
                    failedTools.add(call.toolName());
                    consecutiveToolFailures.merge(call.toolName(), 1, Integer::sum);
                    continue;
                }

                // Gate-loop hard block: after 5 report_write gate rejections, auto-write a rescue report
                // using ONLY approved labels (bypassing further model calls for the report content).
                if ("report_write".equals(call.toolName()) && reportWriteGateBlocks >= 5) {
                    java.util.List<String> approved = relevanceLedger.relevant();
                    log.warn("report_write gate-loop: {} blocks — auto-building rescue report with {} approved labels",
                            reportWriteGateBlocks, approved.size());
                    // Build a minimal valid report with ONLY approved sources (or INSUFFICIENT EVIDENCE)
                    String rescueContent;
                    if (approved.isEmpty()) {
                        rescueContent = "# Research Report (Auto-Rescue)\n\n"
                                + "## Hypothesis\n(Gate-loop recovery — original hypothesis not recoverable)\n\n"
                                + "## Methodology\nMultiple attempts to write a sourced report were blocked by the gate.\n\n"
                                + "## Supporting Evidence\nNone found.\n\n"
                                + "## Contradicting Evidence\nNone found.\n\n"
                                + "## Verdict\nINSUFFICIENT EVIDENCE — The gate-loop prevention system terminated "
                                + "the run after " + reportWriteGateBlocks + " failed report_write attempts. "
                                + "No approved citations remained after relevance screening.\n\n"
                                + "## Limitations\nRun terminated by gate-loop cap.\n\n"
                                + "## Citation Validation\nNot applicable.\n\n"
                                + "## References\n(none)\n";
                    } else {
                        StringBuilder refs = new StringBuilder();
                        approved.forEach(id -> refs.append("- ").append(id).append("\n"));
                        rescueContent = "# Research Report (Auto-Rescue)\n\n"
                                + "## Hypothesis\n(Recovered via gate-loop rescue)\n\n"
                                + "## Methodology\nRelevance filter approved " + approved.size() + " source(s). "
                                + "Report written with approved sources only after " + reportWriteGateBlocks + " gate rejections.\n\n"
                                + "## Supporting Evidence\n(See references — gate-loop rescue; appraiser output not preserved)\n\n"
                                + "## Contradicting Evidence\nNone found.\n\n"
                                + "## Verdict\nINSUFFICIENT EVIDENCE — Gate-loop recovery. Original appraiser output was "
                                + "blocked " + reportWriteGateBlocks + " times. Approved sources: " + String.join(", ", approved) + ".\n\n"
                                + "## Limitations\nRun terminated by gate-loop cap after " + reportWriteGateBlocks + " report_write failures.\n\n"
                                + "## Citation Validation\nNot applicable (auto-rescue mode).\n\n"
                                + "## References\n" + refs;
                    }
                    // Execute report_write directly with the rescue content
                    LlmToolCall rescueCall = new LlmToolCall(
                            call.id(), "report_write", java.util.Map.of("content", rescueContent));
                    String result = runOne(rescueCall, prompter, observer);
                    session.append(LlmMessage.toolResult(call.id(),
                            "GATE-LOOP-RESCUE: " + result + " (auto-built after " + reportWriteGateBlocks + " gate blocks)"));
                    if (observer != null) observer.onToolCallEnd(call, result, 0);
                    // End the loop — we wrote the rescue report
                    return new TurnResult("(gate-loop rescue report written)", iterations, false);
                }

                String result = runOne(call, prompter, observer);
                session.append(LlmMessage.toolResult(call.id(), result));
                boolean failed = result.startsWith("Error") || result.startsWith("Sub-agent failed")
                        || result.startsWith("⛔");  // report_write gate blocks

                // Track gate blocks on report_write
                if ("report_write".equals(call.toolName()) && result.startsWith("⛔ GATE")) {
                    reportWriteGateBlocks++;
                }

                // Track scout subagents by reading the type argument
                if ("subagent_spawn".equals(call.toolName()) && !failed) {
                    Object typeArg = call.arguments() == null ? null : call.arguments().get("type");
                    if (typeArg != null) {
                        String t = typeArg.toString().toLowerCase();
                        if (t.contains("literature") || t.contains("scout")) scoutsSpawned++;
                    }
                }

                if (failed) {
                    failedTools.add(call.toolName());
                    consecutiveToolFailures.merge(call.toolName(), 1, Integer::sum);
                } else {
                    consecutiveToolFailures.remove(call.toolName());
                    // Reset consecutive planning counter on any successful non-planning action
                    if (!"todo_write".equals(call.toolName())) {
                        todoWritesSinceLastAction = 0;
                    }
                }
            }

            // Anti-paralysis guard #1: todo_write-only turns — counter NEVER resets (escalating pressure)
            boolean onlyPlanning = !calls.isEmpty()
                    && calls.stream().allMatch(c -> "todo_write".equals(c.toolName()));
            consecutivePlanning = onlyPlanning ? consecutivePlanning + 1 : 0;
            if (consecutivePlanning >= 2) {
                log.warn("Planning paralysis detected ({} consecutive todo_write turns, {} since last action) — nudging",
                        consecutivePlanning, todoWritesSinceLastAction);
                String urgency = consecutivePlanning >= 4 ? "CRITICAL — you are in a planning loop. " : "";
                session.append(LlmMessage.user("[system] " + urgency + "You have called todo_write "
                        + consecutivePlanning + " consecutive times (" + todoWritesSinceLastAction
                        + " since last research action, cap=3). STOP planning NOW. "
                        + "Your next action MUST be subagent_spawn with type='literature-scout'. "
                        + "If you call todo_write again without researching, it will be BLOCKED."));
                // Do NOT reset consecutivePlanning — keep pressure building
            }

            // Anti-paralysis guard #2: repeated tool failures — stop retrying a broken tool
            for (Map.Entry<String, Integer> entry : new ArrayList<>(consecutiveToolFailures.entrySet())) {
                if (entry.getValue() >= 3) {
                    log.warn("Tool '{}' has failed {} consecutive times — injecting skip nudge",
                            entry.getKey(), entry.getValue());
                    String nudge;
                    if ("report_write".equals(entry.getKey())) {
                        // Use RELEVANT labels from RelevanceLedger (not all ingested) — the model
                        // must only cite papers that passed relevance_filter, not all ingested papers.
                        java.util.List<String> approvedLabels = relevanceLedger.relevant();
                        java.util.List<String> rejectedLabels = relevanceLedger.rejected();
                        String approved = approvedLabels.isEmpty() ? "NONE — verdict must be INSUFFICIENT EVIDENCE"
                                : String.join(", ", approvedLabels);
                        String rejected = rejectedLabels.isEmpty() ? "none"
                                : String.join(", ", rejectedLabels);
                        nudge = "[system] CRITICAL: report_write has been blocked " + entry.getValue() + " times. "
                              + "PERMANENT BAN — remove ALL of these source IDs from EVERY section: [" + rejected + "]. "
                              + "They must not appear in Supporting Evidence, Contradicting Evidence, Tangential, "
                              + "References, OR Citation Validation. "
                              + "ONLY cite sources from this approved list: [" + approved + "]. "
                              + "No other source IDs are permitted anywhere in the report. "
                              + "If the approved list is NONE, write INSUFFICIENT EVIDENCE with zero citations.";
                    } else {
                        nudge = "[system] The tool '" + entry.getKey()
                              + "' has returned errors " + entry.getValue() + " times in a row. "
                              + "It is currently unavailable. Do NOT call it again this session. "
                              + "Use alternative tools (e.g. pdf_download instead of europepmc_fulltext) "
                              + "and continue with the papers already ingested.";
                    }
                    session.append(LlmMessage.user(nudge));
                    consecutiveToolFailures.remove(entry.getKey());
                }
            }
        }

        log.warn("Hit max iterations ({})", maxIterations);
        session.append(LlmMessage.assistant("(hit max iterations — stopping)"));
        return new TurnResult("(hit max iterations)", iterations, true);
    }

    private LlmRequest buildRequest() {
        LlmRequest.Builder b = LlmRequest.builder()
                .model(props.getModel().getPrimary())
                .system(systemPrompt)
                .maxOutputTokens(props.getModel().getMaxOutputTokens())
                .tools(tools);
        for (LlmMessage m : session.messages()) b.message(m);
        return b.build();
    }

    private String runOne(LlmToolCall call, PermissionPrompter prompter, ToolCallObserver observer) {
        BaseTool tool = toolsByName.get(call.toolName());
        if (tool == null) {
            String err = "Error: unknown tool '" + call.toolName() + "'";
            if (observer != null) observer.onToolCallEnd(call, err, 0);
            return err;
        }
        if (observer != null) observer.onToolCallStart(call);
        long t0 = System.currentTimeMillis();
        Object out = router.executeWithRouting(tool, call.arguments(), prompter);
        long ms = System.currentTimeMillis() - t0;
        String resultText = out == null ? "" : out.toString();
        if (observer != null) observer.onToolCallEnd(call, resultText, ms);
        // Mirror what SubagentSpawnTool does for sub-agent ingestions: keep the ledger in sync
        // so report_write's gate can see papers ingested directly by the orchestrator.
        if ("rag_ingest".equals(call.toolName()) && !resultText.startsWith("Error")) {
            Object src = call.arguments() == null ? null : call.arguments().get("source");
            if (src != null) ingestLedger.record(String.valueOf(src));
        }
        return resultText;
    }

    private static String summarize(Throwable e) {
        Throwable cur = e;
        String best = null;
        while (cur != null) {
            String m = cur.getMessage();
            if (m != null && !m.isBlank()) best = m;
            cur = cur.getCause();
        }
        if (best == null) best = e.getClass().getSimpleName();
        best = best.replaceAll("\\s+", " ").trim();
        return best.length() > 400 ? best.substring(0, 400) + "…" : best;
    }

    public record TurnResult(String finalText, int iterations, boolean error) {}

    public interface ToolCallObserver {
        void onToolCallStart(LlmToolCall call);
        void onToolCallEnd(LlmToolCall call, String resultPreview, long elapsedMs);
    }
}
