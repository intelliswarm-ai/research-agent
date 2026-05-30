package ai.intelliswarm.researchagent.eval;

import ai.intelliswarm.researchagent.agent.ConversationEngine.ToolCallObserver;
import ai.intelliswarm.researchagent.agent.Session;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects fine-grained metrics across the orchestrator and all spawned sub-agents.
 *
 * <p>gpt-4o-mini pricing (May 2026):
 * <ul>
 *   <li>Input:  $0.15 / 1M tokens</li>
 *   <li>Output: $0.60 / 1M tokens</li>
 * </ul>
 */
@Component
public class MetricsCollector implements ToolCallObserver {

    // gpt-4o-mini pricing
    private static final double INPUT_COST_PER_1M  = 0.150;
    private static final double OUTPUT_COST_PER_1M = 0.600;

    public record ToolCallRecord(String toolName, long elapsedMs, int resultLength, long timestamp) {}
    public record SubagentRecord(String type, int turns, long inputTokens, long outputTokens, long elapsedMs) {}

    private final List<ToolCallRecord>  toolCalls  = new CopyOnWriteArrayList<>();
    private final List<SubagentRecord>  subagents  = new CopyOnWriteArrayList<>();
    private final AtomicLong subagentInputTokens  = new AtomicLong();
    private final AtomicLong subagentOutputTokens = new AtomicLong();

    // Called by SubagentSpawnTool after each sub-agent finishes
    public void recordSubagent(String type, int turns, long input, long output, long elapsedMs) {
        subagents.add(new SubagentRecord(type, turns, input, output, elapsedMs));
        subagentInputTokens.addAndGet(input);
        subagentOutputTokens.addAndGet(output);
    }

    @Override
    public void onToolCallStart(LlmToolCall call) {}

    @Override
    public void onToolCallEnd(LlmToolCall call, String resultPreview, long elapsedMs) {
        int len = resultPreview == null ? 0 : resultPreview.length();
        toolCalls.add(new ToolCallRecord(call.toolName(), elapsedMs, len, System.currentTimeMillis()));
    }

    // ── Derived metrics ──────────────────────────────────────────────────────

    public long totalInputTokens(Session session) {
        return session.inputTokens() + subagentInputTokens.get();
    }

    public long totalOutputTokens(Session session) {
        return session.outputTokens() + subagentOutputTokens.get();
    }

    public double totalCostUSD(Session session) {
        return (totalInputTokens(session) / 1_000_000.0) * INPUT_COST_PER_1M
             + (totalOutputTokens(session) / 1_000_000.0) * OUTPUT_COST_PER_1M;
    }

    public long papersIngested() {
        return toolCalls.stream().filter(r -> r.toolName().equals("rag_ingest")).count();
    }

    public long ragSearchesRun() {
        return toolCalls.stream().filter(r -> r.toolName().equals("rag_search")).count();
    }

    public long pdfDownloads() {
        return toolCalls.stream().filter(r -> r.toolName().equals("pdf_download")).count();
    }

    public long pubmedSearches() {
        return toolCalls.stream().filter(r -> r.toolName().equals("pubmed_search")).count();
    }

    public Map<String, Long> toolCallCountByName() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ToolCallRecord r : toolCalls) {
            counts.merge(r.toolName(), 1L, Long::sum);
        }
        return counts;
    }

    public long slowestToolMs() {
        return toolCalls.stream().mapToLong(ToolCallRecord::elapsedMs).max().orElse(0);
    }

    public double avgToolMs() {
        return toolCalls.stream().mapToLong(ToolCallRecord::elapsedMs).average().orElse(0);
    }

    // ── Render ───────────────────────────────────────────────────────────────

    public String renderReport(Session session, long wallClockMs) {
        long totalIn  = totalInputTokens(session);
        long totalOut = totalOutputTokens(session);
        double cost   = totalCostUSD(session);

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════╗\n");
        sb.append("║                 EVALUATION METRICS                       ║\n");
        sb.append("╠══════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  Wall-clock time      : %s%n", formatDuration(wallClockMs)));
        sb.append(String.format("║  Total tokens (in)    : %,d%n", totalIn));
        sb.append(String.format("║    Orchestrator in    : %,d%n", session.inputTokens()));
        sb.append(String.format("║    Sub-agents in      : %,d%n", subagentInputTokens.get()));
        sb.append(String.format("║  Total tokens (out)   : %,d%n", totalOut));
        sb.append(String.format("║    Orchestrator out   : %,d%n", session.outputTokens()));
        sb.append(String.format("║    Sub-agents out     : %,d%n", subagentOutputTokens.get()));
        sb.append(String.format("║  Estimated cost (USD) : $%.4f%n", cost));
        sb.append("╠══════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  Sub-agents spawned   : %d%n", subagents.size()));
        for (SubagentRecord sa : subagents) {
            sb.append(String.format("║    [%s] %d turns, %,d in / %,d out, %s%n",
                    sa.type(), sa.turns(), sa.inputTokens(), sa.outputTokens(),
                    formatDuration(sa.elapsedMs())));
        }
        sb.append("╠══════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  Papers ingested      : %d%n", papersIngested()));
        sb.append(String.format("║  PDF downloads        : %d%n", pdfDownloads()));
        sb.append(String.format("║  PubMed searches      : %d%n", pubmedSearches()));
        sb.append(String.format("║  RAG searches         : %d%n", ragSearchesRun()));
        sb.append(String.format("║  Total tool calls     : %d%n", toolCalls.size()));
        sb.append(String.format("║  Avg tool latency     : %.0f ms%n", avgToolMs()));
        sb.append(String.format("║  Slowest tool call    : %,d ms%n", slowestToolMs()));
        sb.append("╠══════════════════════════════════════════════════════════╣\n");
        sb.append("║  Tool call breakdown:\n");
        toolCallCountByName().forEach((name, count) ->
            sb.append(String.format("║    %-30s %3d calls%n", name, count)));
        sb.append("╚══════════════════════════════════════════════════════════╝\n");
        return sb.toString();
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        return (s / 60) + "m " + (s % 60) + "s";
    }

    public List<ToolCallRecord> toolCalls() { return List.copyOf(toolCalls); }
    public List<SubagentRecord> subagents() { return List.copyOf(subagents); }
}
