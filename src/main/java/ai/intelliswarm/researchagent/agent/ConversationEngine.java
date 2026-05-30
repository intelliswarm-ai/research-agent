package ai.intelliswarm.researchagent.agent;

import ai.intelliswarm.researchagent.config.ResearchProperties;
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

    private final LlmClient llm;
    private final List<BaseTool> tools;
    private final Map<String, BaseTool> toolsByName;
    private final Session session;
    private final ToolRouter router;
    private final ResearchProperties props;
    private final String systemPrompt;

    public ConversationEngine(LlmClient llm,
                              ResearchToolset toolset,
                              Session session,
                              ToolRouter router,
                              ResearchProperties props) {
        this.llm = llm;
        this.tools = toolset.tools();
        this.toolsByName = new HashMap<>();
        for (BaseTool t : tools) toolsByName.put(t.getFunctionName(), t);
        this.session = session;
        this.router = router;
        this.props = props;
        this.systemPrompt = Prompts.load("orchestrator.md", FALLBACK_SYSTEM);
    }

    public List<BaseTool> tools() { return tools; }

    public TurnResult runTurn(String userMessage, PermissionPrompter prompter, ToolCallObserver observer) {
        session.append(LlmMessage.user(userMessage == null ? "" : userMessage));

        int maxIterations = props.getAgents().getMaxTurnsPerTask();
        int iterations = 0;
        String finalText = "";

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
            for (LlmToolCall call : resp.toolCalls()) {
                String result = runOne(call, prompter, observer);
                session.append(LlmMessage.toolResult(call.id(), result));
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
