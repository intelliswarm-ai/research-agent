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

    public ConversationEngine(LlmClient llm,
                              ResearchToolset toolset,
                              Session session,
                              ToolRouter router,
                              ResearchProperties props,
                              IngestLedger ingestLedger) {
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
        this.systemPrompt = Prompts.load("orchestrator.md", FALLBACK_SYSTEM);
    }

    public List<BaseTool> tools() { return tools; }

    public TurnResult runTurn(String userMessage, PermissionPrompter prompter, ToolCallObserver observer) {
        session.append(LlmMessage.user(userMessage == null ? "" : userMessage));

        int maxIterations = props.getAgents().getMaxTurnsPerTask();
        int iterations = 0;
        String finalText = "";
        int consecutivePlanning = 0; // anti-paralysis: count back-to-back todo_write-only turns
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
                String result = runOne(call, prompter, observer);
                session.append(LlmMessage.toolResult(call.id(), result));
                boolean failed = result.startsWith("Error") || result.startsWith("Sub-agent failed")
                        || result.startsWith("⛔");  // report_write gate blocks
                if (failed) {
                    failedTools.add(call.toolName());
                    consecutiveToolFailures.merge(call.toolName(), 1, Integer::sum);
                } else {
                    consecutiveToolFailures.remove(call.toolName());
                }
            }

            // Anti-paralysis guard #1: todo_write-only turns (trigger after 2)
            boolean onlyPlanning = !calls.isEmpty()
                    && calls.stream().allMatch(c -> "todo_write".equals(c.toolName()));
            consecutivePlanning = onlyPlanning ? consecutivePlanning + 1 : 0;
            if (consecutivePlanning >= 2) {
                log.warn("Planning paralysis detected ({} todo_write-only turns) — nudging to research",
                        consecutivePlanning);
                session.append(LlmMessage.user("[system] You have called todo_write "
                        + consecutivePlanning + " times in a row without doing research. STOP planning. "
                        + "Your next action MUST be subagent_spawn with type='literature-scout' to search "
                        + "for papers. Do not call todo_write again until a sub-agent has run."));
                consecutivePlanning = 0;
            }

            // Anti-paralysis guard #2: repeated tool failures — stop retrying a broken tool
            for (Map.Entry<String, Integer> entry : new ArrayList<>(consecutiveToolFailures.entrySet())) {
                if (entry.getValue() >= 3) {
                    log.warn("Tool '{}' has failed {} consecutive times — injecting skip nudge",
                            entry.getKey(), entry.getValue());
                    String nudge;
                    if ("report_write".equals(entry.getKey())) {
                        // Extract which IDs are RELEVANT from the IngestLedger to give the model
                        // concrete guidance on what it CAN cite instead of what it cannot.
                        String approved = ingestLedger.all().isEmpty() ? "none yet ingested"
                                : String.join(", ", ingestLedger.all());
                        nudge = "[system] CRITICAL: report_write has been blocked " + entry.getValue() + " times. "
                              + "The gate error names the EXACT rejected source IDs — remove them from EVERY "
                              + "section of the report (Supporting Evidence, Contradicting Evidence, Tangential, "
                              + "AND References). They must not appear anywhere. "
                              + "Ingested sources you MAY cite: [" + approved + "]. "
                              + "If none are RELEVANT after relevance_filter, the verdict MUST be "
                              + "INSUFFICIENT EVIDENCE with zero citations — that is a valid, correct result.";
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
