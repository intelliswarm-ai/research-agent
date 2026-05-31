package ai.intelliswarm.researchagent.eval;

import ai.intelliswarm.researchagent.agent.ConversationEngine.ToolCallObserver;
import ai.intelliswarm.researchagent.agent.Session;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects fine-grained metrics across the orchestrator and all spawned sub-agents.
 *
 * <p>Pricing is model-aware. Known models and their per-1M-token rates (May 2026):
 * <pre>
 *   gpt-4o-mini        $0.15  / $0.60
 *   gpt-4o             $2.50  / $10.00
 *   gpt-4.1            $2.00  / $8.00
 *   gpt-4.1-mini       $0.40  / $1.60
 *   gpt-4.1-nano       $0.10  / $0.40
 *   o1-mini            $1.10  / $4.40
 *   o3-mini            $1.10  / $4.40
 * </pre>
 */
@Component
public class MetricsCollector implements ToolCallObserver {

    // model-id prefix → [inputPricePer1M, outputPricePer1M]
    private static final Map<String, double[]> PRICING = Map.of(
            "gpt-4o-mini",  new double[]{ 0.150,  0.600 },
            "gpt-4o",       new double[]{ 2.500, 10.000 },
            "gpt-4.1-mini", new double[]{ 0.400,  1.600 },
            "gpt-4.1-nano", new double[]{ 0.100,  0.400 },
            "gpt-4.1",      new double[]{ 2.000,  8.000 },
            "o1-mini",      new double[]{ 1.100,  4.400 },
            "o3-mini",      new double[]{ 1.100,  4.400 }
    );
    private static final double[] DEFAULT_PRICING = { 0.150, 0.600 }; // gpt-4o-mini fallback

    public record ToolCallRecord(String toolName, long elapsedMs, int resultLength, long timestamp,
                                  String resultPreview) {}
    public record SubagentRecord(String type, int turns, long inputTokens, long outputTokens, long elapsedMs) {}

    private final List<ToolCallRecord>  toolCalls  = new CopyOnWriteArrayList<>();
    private final List<SubagentRecord>  subagents  = new CopyOnWriteArrayList<>();
    /** IDs explicitly rejected by relevance_filter during this run (populated by RelevanceFilterTool). */
    private final List<String> rejectedSourceIds = new CopyOnWriteArrayList<>();
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
        toolCalls.add(new ToolCallRecord(call.toolName(), elapsedMs, len, System.currentTimeMillis(),
                resultPreview != null ? resultPreview : ""));
    }

    /** Records a source ID that was explicitly rejected by the relevance_filter gate. */
    public void recordRejectedSourceId(String id) {
        if (id != null && !id.isBlank()) rejectedSourceIds.add(id.toLowerCase().strip());
    }

    /** Returns a snapshot of all source IDs rejected by relevance_filter during this run. */
    public List<String> rejectedSourceIds() { return List.copyOf(rejectedSourceIds); }

    /**
     * Counts report_write tool calls whose resultPreview contains an "identical resubmit" marker —
     * meaning the LLM resubmitted an unchanged report after a skip-nudge.
     */
    public long identicalResubmitCount() {
        return toolCalls.stream()
                .filter(r -> "report_write".equals(r.toolName()))
                .filter(r -> {
                    String p = r.resultPreview().toLowerCase();
                    return p.contains("unchanged") || p.contains("identical")
                            || p.contains("same report") || p.contains("no changes");
                })
                .count();
    }

    // ── Derived metrics ──────────────────────────────────────────────────────

    public long totalInputTokens(Session session) {
        return session.inputTokens() + subagentInputTokens.get();
    }

    public long totalOutputTokens(Session session) {
        return session.outputTokens() + subagentOutputTokens.get();
    }

    public double totalCostUSD(Session session) {
        return totalCostUSD(session, null);
    }

    public double totalCostUSD(Session session, String model) {
        double[] p = pricingFor(model);
        return (totalInputTokens(session) / 1_000_000.0) * p[0]
             + (totalOutputTokens(session) / 1_000_000.0) * p[1];
    }

    /** Returns [inputPer1M, outputPer1M] for the given model (longest-prefix match). */
    public static double[] pricingFor(String model) {
        if (model != null) {
            for (Map.Entry<String, double[]> e : PRICING.entrySet()) {
                if (model.startsWith(e.getKey())) return e.getValue();
            }
        }
        return DEFAULT_PRICING;
    }

    /** One-line cost summary suitable for the REPL status bar. */
    public String costSummary(Session session, String model) {
        long in  = totalInputTokens(session);
        long out = totalOutputTokens(session);
        double cost = totalCostUSD(session, model);
        return String.format("$%.4f  (%,d in + %,d out tokens · %s)",
                cost, in, out, model != null ? model : "gpt-4o-mini");
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

    /**
     * Counts tool call records whose resultPreview contains HTTP 429 / rate-limit signals.
     * Used by QualityScorer DEFECT[pubmed-rate-limit-no-backoff].
     */
    public long rateLimitErrorCount() {
        return toolCalls.stream()
                .filter(r -> {
                    String p = r.resultPreview().toLowerCase();
                    return p.contains("429") || p.contains("rate limit") || p.contains("too many requests");
                })
                .count();
    }

    public List<ToolCallRecord> toolCalls() { return List.copyOf(toolCalls); }
    public List<SubagentRecord> subagents() { return List.copyOf(subagents); }
}
