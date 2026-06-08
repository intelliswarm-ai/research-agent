package ai.intelliswarm.researchagent.eval;

import ai.intelliswarm.researchagent.agent.ConversationEngine;
import ai.intelliswarm.researchagent.agent.ConversationEngine.TurnResult;
import ai.intelliswarm.researchagent.agent.PermissionPrompter;
import ai.intelliswarm.researchagent.agent.Session;
import ai.intelliswarm.researchagent.config.ResearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Headless (no JLine) runner for the full research pipeline.
 * Activated when the application is launched with the {@code --batch} flag.
 *
 * <p>Example:
 * <pre>
 *   java -jar research-agent.jar --batch "does amyloid-beta clearance decline in early Alzheimer?"
 * </pre>
 *
 * <p>Writes:
 * <ul>
 *   <li>{@code output/research_report_<ts>.md} — the full markdown report</li>
 *   <li>{@code output/eval_result_<ts>.json} — structured metrics + scores for research-agent-eval</li>
 *   <li>{@code output/metrics_<ts>.txt} — human-readable metrics + quality report</li>
 * </ul>
 */
@Component
public class BatchResearchRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchResearchRunner.class);

    private final ConversationEngine engine;
    private final Session session;
    private final MetricsCollector metrics;
    private final EvalResultWriter resultWriter;
    private final ResearchProperties props;
    private final ai.intelliswarm.researchagent.agent.ToolRouter router;

    public BatchResearchRunner(ConversationEngine engine, Session session,
                                MetricsCollector metrics, EvalResultWriter resultWriter,
                                ResearchProperties props,
                                ai.intelliswarm.researchagent.agent.ToolRouter router) {
        this.engine       = engine;
        this.session      = session;
        this.metrics      = metrics;
        this.resultWriter = resultWriter;
        this.props        = props;
        this.router       = router;
    }

    public void run(String hypothesis) throws IOException {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════");
        System.out.println("  RESEARCH AGENT — BATCH EVALUATION RUN");
        System.out.println("════════════════════════════════════════════════════════");
        System.out.println("  Hypothesis: " + hypothesis);
        System.out.println("  Model:      " + props.getModel().getPrimary());
        System.out.println("════════════════════════════════════════════════════════");
        System.out.println();

        // Batch mode is non-interactive: allow all tools (incl. sub-agent tool calls).
        router.setSessionPrompter(ai.intelliswarm.researchagent.agent.PermissionPrompter.allowAll());

        String seed = buildSeedMessage(hypothesis);
        long t0 = System.currentTimeMillis();

        // Composite observer: metrics + live console output for debugging
        ConversationEngine.ToolCallObserver liveObserver = new ConversationEngine.ToolCallObserver() {
            @Override public void onToolCallStart(ai.intelliswarm.swarmai.agent.llm.LlmToolCall call) {
                metrics.onToolCallStart(call);
                String argsStr = String.valueOf(call.arguments());
                if (argsStr.length() > 150) argsStr = argsStr.substring(0, 150) + "…";
                System.out.println("  ▶ " + call.toolName() + "  " + argsStr);
            }
            @Override public void onToolCallEnd(ai.intelliswarm.swarmai.agent.llm.LlmToolCall call, String result, long ms) {
                metrics.onToolCallEnd(call, result, ms);
                String preview = (result == null ? "" : result).replaceAll("\\s+", " ").trim();
                if (preview.length() > 200) preview = preview.substring(0, 200) + "…";
                System.out.println("  ✓ " + call.toolName() + " (" + ms + "ms)  " + preview);
            }
        };

        TurnResult result = engine.runTurn(seed, PermissionPrompter.allowAll(), liveObserver);

        long wallMs = System.currentTimeMillis() - t0;

        // ── Metrics ──────────────────────────────────────────────────────────
        String metricsReport = metrics.renderReport(session, wallMs);
        System.out.println(metricsReport);

        // ── Find the report file that was written by report_write tool ────────
        // CRITICAL: only accept a report written DURING this run (mtime >= t0). Otherwise a run
        // that failed to produce a report (e.g. gate-looped) silently scores the newest STALE
        // .md left in output/ from a previous, unrelated run — corrupting every eval score.
        String reportFilePath = findLatestReport(t0);
        if (reportFilePath.isEmpty()) {
            System.out.println("  NOTE: no report file was written during this run (mtime >= run start). "
                    + "Scoring as no-report — this is the honest outcome of a failed/gate-looped run, "
                    + "not a stale prior report.");
        }

        // ── Quality scores — score the actual report file, not the LLM's closing message ──
        // When no report was written THIS run, score empty content so the scorer hits its clean
        // no-report / gate-loop gate (accurate diagnostics) instead of grading the LLM's last
        // message (often a crash/error string) and mislabelling it 'stale-report'.
        String reportContent = "";
        if (!reportFilePath.isEmpty()) {
            try {
                reportContent = java.nio.file.Files.readString(java.nio.file.Path.of(reportFilePath));
            } catch (IOException e) {
                log.warn("Could not read report file for scoring: {}", e.getMessage());
            }
        }
        // Pass hypothesis so the scorer can cross-check that the scored report
        // actually corresponds to this run (not a stale file from a previous run).
        QualityScorer.ScoreResult scores = QualityScorer.compute(reportContent, metrics, session, wallMs, hypothesis);
        String qualityReport = QualityScorer.render(scores, metrics, session, wallMs);
        System.out.println(qualityReport);

        // ── Write JSON for eval module ─────────────────────────────────────────
        // Pass result.error() so EvalResultWriter can force scoreOverall=0 and
        // set run_error=true in the JSON when the agent crashed before completing.
        resultWriter.write(hypothesis, props.getModel().getPrimary(),
                reportFilePath, metrics, session, scores, wallMs, result.error());

        // ── Save human-readable metrics file ──────────────────────────────────
        java.nio.file.Path outDir = java.nio.file.Paths.get(props.getOutputDir()).toAbsolutePath();
        java.nio.file.Files.createDirectories(outDir);
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        java.nio.file.Path metricsFile = outDir.resolve("metrics_" + ts + ".txt");
        java.nio.file.Files.writeString(metricsFile,
                "Hypothesis: " + hypothesis + "\n\n" + metricsReport + "\n\n" + qualityReport,
                java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("  Metrics saved to: " + metricsFile);

        if (result.error()) {
            System.out.println("  WARNING: run ended with error after " + result.iterations() + " iterations.");
            System.out.println("  DEFECT[run-error]: quality scores may be unreliable — the agent crashed before");
            System.out.println("  completing normally. If no report file was found, scores describe a stale report.");
        }
        System.out.println();
    }

    /**
     * Returns the newest {@code research_report_*.md} in output/ that was written at or after
     * {@code minMtimeMs} (this run's start). Reports older than the run start are stale files from
     * previous runs and MUST NOT be scored. Returns "" when this run produced no report.
     */
    private static String findLatestReport(long minMtimeMs) {
        try {
            java.nio.file.Path outDir = java.nio.file.Paths.get("output").toAbsolutePath();
            if (!java.nio.file.Files.exists(outDir)) return "";
            // Allow a small clock-skew tolerance so a report written milliseconds before t0 was
            // captured isn't rejected. Reports from prior runs are minutes-to-days older.
            long cutoff = minMtimeMs - 2000;
            return java.nio.file.Files.list(outDir)
                    .filter(p -> p.getFileName().toString().startsWith("research_report_")
                              && p.getFileName().toString().endsWith(".md"))
                    .filter(p -> {
                        try { return java.nio.file.Files.getLastModifiedTime(p).toMillis() >= cutoff; }
                        catch (Exception e) { return false; }
                    })
                    .max(java.util.Comparator.comparing(p -> {
                        try { return java.nio.file.Files.getLastModifiedTime(p); }
                        catch (Exception e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
                    }))
                    .map(java.nio.file.Path::toAbsolutePath)
                    .map(java.nio.file.Path::toString)
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String buildSeedMessage(String hypothesis) {
        return "## Research Investigation Request\n\n"
             + "**Hypothesis to investigate:**\n> " + hypothesis + "\n\n"
             + "**Evidence level required:** human clinical studies (reject animal/in-vitro-only papers).\n\n"
             + "**Instructions — follow the mandatory workflow:**\n"
             + "1. `todo_write` plan.\n"
             + "2. Decompose the hypothesis into searchable sub-concepts (do not use one keyword-soup query).\n"
             + "3. Spawn `literature-scout` — prefer `europepmc_fulltext` for real full text; search each sub-concept.\n"
             + "4. `rag_status` to confirm ingestion.\n"
             + "5. `relevance_filter` with the hypothesis, evidence_level='human clinical studies', and the ingested {source,title} list. "
             + "Drop REJECTED papers; only cite RELEVANT ones; if none are RELEVANT, the verdict is INSUFFICIENT EVIDENCE.\n"
             + "6. Spawn `evidence-appraiser` on the RELEVANT sources only.\n"
             + "7. `citation_validate` with all cited source labels.\n"
             + "8. `report_write` with: Hypothesis, Methodology, Relevance Screening, Supporting Evidence, "
             + "Contradicting Evidence, Tangential/Indirect Findings, Verdict (incl. INSUFFICIENT EVIDENCE option), "
             + "Limitations, Citation Validation, References.\n";
    }
}
