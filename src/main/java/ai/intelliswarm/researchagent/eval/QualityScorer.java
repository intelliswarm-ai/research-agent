package ai.intelliswarm.researchagent.eval;

import ai.intelliswarm.researchagent.agent.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scores the final research report on multiple quality dimensions.
 * Returns a {@link ScoreResult} record for downstream use by the eval module.
 */
public final class QualityScorer {

    // Scoring weights (must sum to 1.0)
    private static final double W_STRUCTURE  = 0.20;
    private static final double W_EVIDENCE   = 0.25;
    private static final double W_CITATIONS  = 0.20;
    private static final double W_BALANCE    = 0.15;
    private static final double W_VERDICT    = 0.10;
    private static final double W_EFFICIENCY = 0.10;

    private QualityScorer() {}

    public record ScoreResult(
            double structure, double evidence, double citations,
            double balance, double verdict, double efficiency, double overall,
            List<String> issues, boolean citationValidationPassed
    ) {}

    public static ScoreResult compute(String reportText, MetricsCollector metrics,
                                       Session session, long wallMs) {
        if (reportText == null || reportText.isBlank()) {
            return new ScoreResult(0, 0, 0, 0, 0, 0, 0,
                    List.of("No report text produced"), false);
        }
        String lower = reportText.toLowerCase();
        List<String> issues = new ArrayList<>();

        // Structure
        List<String> missing = new ArrayList<>();
        for (String[] kv : new String[][]{
                {"hypothesis", "Hypothesis"}, {"methodology", "Methodology"},
                {"supporting evidence", "Supporting Evidence"}, {"contradicting evidence", "Contradicting Evidence"},
                {"verdict", "Verdict"}, {"limitation", "Limitations"}, {"reference", "References"}}) {
            if (!lower.contains(kv[0])) missing.add(kv[1]);
        }
        double scoreStructure = ((7 - missing.size()) / 7.0) * 10.0;
        if (!missing.isEmpty()) issues.add("Missing sections: " + missing);

        // Evidence depth
        long sourceLabels = countPattern(reportText, "\\*source:\\s*\\S+\\*|\\(source:\\s*\\S+\\)|source:\\s*[a-z]+:\\d+");
        long quotes       = countPattern(reportText, "\"[^\"]{20,}\"");
        double scoreEvidence = Math.min(10.0, sourceLabels * 1.5 + quotes * 0.5);
        if (sourceLabels < 3) issues.add("Fewer than 3 labeled citations (found " + sourceLabels + ")");
        if (quotes < 2)       issues.add("Fewer than 2 verbatim quotes");

        // Citations
        long pmids  = countPattern(reportText, "\\b\\d{7,9}\\b");
        long dois   = countPattern(reportText, "10\\.\\d{4,}/\\S+");
        boolean hasValidation = lower.contains("citation validation") || lower.contains("confirmed in pubmed");
        double scoreCitations = Math.min(10.0, pmids * 1.5 + dois * 1.5 + (hasValidation ? 2.0 : 0));
        if (pmids == 0 && dois == 0) issues.add("No PMIDs or DOIs — citations may be fabricated");
        if (!hasValidation)          issues.add("Citation validation not included");

        // Balance
        boolean hasSupports    = lower.contains("supports") || lower.contains("supporting evidence");
        boolean hasContradicts = lower.contains("contradicts") || lower.contains("contradicting evidence");
        double scoreBalance = (hasSupports && hasContradicts) ? 10.0
                            : (hasSupports || hasContradicts) ? 5.0 : 0.0;
        if (!hasSupports || !hasContradicts)
            issues.add("Missing " + (!hasSupports ? "supporting" : "contradicting") + " evidence section");

        // Verdict
        boolean verdictKeyword = lower.contains("supported") || lower.contains("contradicted") || lower.contains("inconclusive");
        boolean justification  = lower.contains("because") || lower.contains("therefore") || lower.contains("evidence suggests");
        double scoreVerdict = (verdictKeyword ? 6.0 : 0.0) + (justification ? 4.0 : 0.0);
        if (!verdictKeyword) issues.add("No explicit verdict keyword");

        // Efficiency
        long papersIngested = metrics.papersIngested();
        double costUSD      = metrics.totalCostUSD(session);
        double wallMin      = wallMs / 60_000.0;
        double effPapers    = papersIngested >= 5 ? 10 : papersIngested >= 3 ? 7 : papersIngested >= 1 ? 4 : 0;
        double effCost      = costUSD < 0.05 ? 10 : costUSD < 0.10 ? 8 : costUSD < 0.20 ? 6 : 4;
        double effTime      = wallMin < 5 ? 10 : wallMin < 10 ? 8 : wallMin < 20 ? 6 : 4;
        double scoreEfficiency = (effPapers + effCost + effTime) / 3.0;
        if (papersIngested < 3) issues.add("Only " + papersIngested + " papers ingested (need ≥5)");
        if (costUSD > 0.30)     issues.add(String.format("High cost $%.4f", costUSD));

        double overall = scoreStructure * W_STRUCTURE + scoreEvidence * W_EVIDENCE
                       + scoreCitations * W_CITATIONS + scoreBalance * W_BALANCE
                       + scoreVerdict * W_VERDICT + scoreEfficiency * W_EFFICIENCY;

        return new ScoreResult(scoreStructure, scoreEvidence, scoreCitations,
                scoreBalance, scoreVerdict, scoreEfficiency, overall,
                issues, hasValidation);
    }

    /** Render a human-readable quality report for console output. */
    public static String render(ScoreResult s, MetricsCollector metrics, Session session, long wallMs) {
        long pmids  = countPattern("", "");  // placeholder — caller has report text
        double cost = metrics.totalCostUSD(session);
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════╗\n");
        sb.append("║                 QUALITY EVALUATION                       ║\n");
        sb.append("╠══════════════════════════════════════════════════════════╣\n");
        sb.append(fmt("Structure completeness",  s.structure(),  W_STRUCTURE));
        sb.append(fmt("Evidence depth",          s.evidence(),   W_EVIDENCE));
        sb.append(fmt("Citation quality",        s.citations(),  W_CITATIONS));
        sb.append(fmt("Balance (both sides)",    s.balance(),    W_BALANCE));
        sb.append(fmt("Verdict clarity",         s.verdict(),    W_VERDICT));
        sb.append(fmt("Efficiency",              s.efficiency(), W_EFFICIENCY));
        sb.append("╠══════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  ▶  OVERALL QUALITY SCORE : %.1f / 10%n", s.overall()));
        sb.append("╠══════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  Papers: %d  |  Cost: $%.4f  |  Citation valid: %s%n",
                metrics.papersIngested(), cost, s.citationValidationPassed() ? "✅" : "✗"));
        if (!s.issues().isEmpty()) {
            sb.append("╠══════════════════════════════════════════════════════════╣\n");
            sb.append("║  Issues:\n");
            s.issues().forEach(i -> sb.append("║    ✗ ").append(i).append("\n"));
        }
        sb.append("╚══════════════════════════════════════════════════════════╝\n");
        return sb.toString();
    }

    private static String fmt(String label, double score, double weight) {
        return String.format("║  %-26s: %4.1f / 10  (wt %.0f%%)%n", label, score, weight * 100);
    }

    private static long countPattern(String text, String regex) {
        if (text == null || text.isBlank() || regex.isBlank()) return 0;
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).results().count();
    }

    /** Convenience overload used by BatchResearchRunner with report text. */
    public static ScoreResult compute(String reportText, MetricsCollector metrics,
                                       Session session, long wallMs, String _ignored) {
        return compute(reportText, metrics, session, wallMs);
    }
}
