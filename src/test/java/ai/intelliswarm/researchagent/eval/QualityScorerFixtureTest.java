package ai.intelliswarm.researchagent.eval;

import ai.intelliswarm.researchagent.agent.Session;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression fixtures for {@link QualityScorer}. Each test pins a specific scorer behaviour to a
 * known-good or known-bad report so future scorer edits cannot silently change the meaning of a
 * score. These run with no network and no LLM — they feed synthetic reports + metrics directly.
 *
 * <p>Covers the substance checks and de-noise fixes added on 2026-06-08:
 * <ul>
 *   <li>stale-report only zeroes content on a TRUE mismatch (0 hypothesis terms)</li>
 *   <li>weak-hypothesis-overlap penalises mildly instead of forcing a false 0.0</li>
 *   <li>golden-reference-zero-recall when landmark trials are all missed</li>
 *   <li>verdict-contradicts-evidence when the conclusion opposes the cited evidence</li>
 *   <li>quote-fabrication-suspected when verbatim quotes match nothing retrieved</li>
 * </ul>
 */
class QualityScorerFixtureTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    private static MetricsCollector metrics(int ingests, String... ragSearchResults) {
        MetricsCollector m = new MetricsCollector();
        for (int i = 0; i < ingests; i++) {
            m.onToolCallEnd(new LlmToolCall("ing" + i, "rag_ingest", Map.of()), "ok", 5);
        }
        for (int i = 0; i < ragSearchResults.length; i++) {
            m.onToolCallEnd(new LlmToolCall("rs" + i, "rag_search", Map.of()), ragSearchResults[i], 5);
        }
        return m;
    }

    private static MetricsCollector metricsWithScouts(int ingests, String... ragSearchResults) {
        MetricsCollector m = metrics(ingests, ragSearchResults);
        m.recordSubagent("literature-scout", 6, 2000, 800, 1000);
        m.recordSubagent("literature-scout", 6, 2000, 800, 1000);
        m.recordSubagent("literature-scout", 6, 2000, 800, 1000);
        m.recordSubagent("evidence-appraiser", 5, 3000, 1400, 1500);
        return m;
    }

    private static QualityScorer.ScoreResult score(String report, MetricsCollector m, String hypothesis) {
        return QualityScorer.compute(report, m, new Session(), 90_000L, hypothesis);
    }

    private static boolean hasDefect(QualityScorer.ScoreResult r, String code) {
        return r.issues().stream().anyMatch(s -> s.contains(code));
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    /** A well-formed report on a recognised domain that cites the landmark trials. */
    private static final String GOOD_REPORT = """
            # Research Report

            ## Hypothesis
            Resistance training improves muscle strength in older adults with sarcopenia.

            ## Methodology
            We searched PubMed and OpenAlex, ingested 5 papers, screened them for relevance, and
            appraised the human clinical evidence on resistance training and sarcopenia.

            ## Supporting Evidence
            > "Progressive resistance training increased muscle strength by 113% in frail elderly
            > participants compared with controls (p<0.001)." — *source: pubmed:8304959*

            > "Combined resistance exercise significantly improved lean body mass and strength in
            > older men (p=0.002)." — *source: pubmed:30025000*

            ## Contradicting Evidence
            > "Resistance training alone showed no significant change in gait speed at 12 weeks
            > (p=0.41)." — *source: pubmed:11502588*

            ## Tangential / Indirect Findings
            None directly relevant.

            ## Verdict
            **SUPPORTED** — because multiple human trials demonstrated that resistance training
            improves muscle strength in sarcopenia, with effect sizes reported above.

            ## Limitations
            Small sample sizes and short follow-up in several trials.

            ## Citation Validation
            All cited PMIDs confirmed in PubMed.

            ## References
            - pubmed:8304959 — Fiatarone 1994 NEJM
            - pubmed:11502588 — Bhasin 2001 NEJM
            - pubmed:30025000 — resistance exercise trial
            """;

    @Test
    @DisplayName("good report on a recognised domain scores well and trips no fatal/substance defects")
    void goodReport() {
        QualityScorer.ScoreResult r = score(GOOD_REPORT, metricsWithScouts(5),
                "resistance training improves muscle strength in sarcopenia");
        assertTrue(r.overall() >= 5.0, "expected a solid overall, got " + r.overall() + " issues=" + r.issues());
        assertFalse(hasDefect(r, "DEFECT[stale-report]"));
        assertFalse(hasDefect(r, "DEFECT[no-report]"));
        assertFalse(hasDefect(r, "DEFECT[golden-reference-zero-recall]"));
        assertFalse(hasDefect(r, "DEFECT[verdict-contradicts-evidence]"));
        assertFalse(hasDefect(r, "DEFECT[quote-fabrication-suspected]"));
    }

    @Test
    @DisplayName("blank report -> overall 0 and DEFECT[no-report]")
    void noReport() {
        QualityScorer.ScoreResult r = score("", metrics(0), "anything");
        assertEquals(0.0, r.overall(), 1e-9);
        assertTrue(hasDefect(r, "DEFECT[no-report]"));
    }

    @Test
    @DisplayName("report with ZERO hypothesis terms -> DEFECT[stale-report], content zeroed")
    void staleReport() {
        // Hypothesis terms (canagliflozin, renal, nephropathy) appear nowhere in this report.
        QualityScorer.ScoreResult r = score(GOOD_REPORT, metricsWithScouts(5),
                "canagliflozin slows renal nephropathy progression");
        assertTrue(hasDefect(r, "DEFECT[stale-report]"), "issues=" + r.issues());
        assertEquals(0.0, r.structure(), 1e-9);
        assertEquals(0.0, r.evidence(), 1e-9);
    }

    @Test
    @DisplayName("on-topic but thin overlap -> DEFECT[weak-hypothesis-overlap], NOT zeroed")
    void weakOverlap() {
        // Only "tirzepatide" (length>8) appears in the report; the rest of the hypothesis does not.
        String report = """
                ## Hypothesis
                Tirzepatide and metabolic outcomes.

                ## Methodology
                We reviewed a single trial mentioning tirzepatide.

                ## Supporting Evidence
                > "The agent reduced body weight by 15% versus placebo (p<0.001)." — *source: pubmed:35658024*
                > "Participants showed improved glucose control over 72 weeks (p=0.004)." — *source: pubmed:35763010*

                ## Contradicting Evidence
                None found.

                ## Verdict
                **INCONCLUSIVE** — because only limited evidence was retrieved.

                ## Limitations
                Single trial.

                ## References
                - pubmed:35658024
                - pubmed:35763010
                """;
        QualityScorer.ScoreResult r = score(report, metricsWithScouts(4),
                "tirzepatide improves glycemic control in obese adults with cardiovascular risk");
        assertTrue(hasDefect(r, "DEFECT[weak-hypothesis-overlap]"), "issues=" + r.issues());
        assertFalse(hasDefect(r, "DEFECT[stale-report]"));
        assertTrue(r.overall() > 0.0, "weak overlap must NOT force a 0.0; got " + r.overall());
    }

    @Test
    @DisplayName("matched domain but no landmark PMIDs -> DEFECT[golden-reference-zero-recall]")
    void goldenReferenceZeroRecall() {
        String report = """
                ## Hypothesis
                Resistance training in sarcopenia.

                ## Methodology
                Searched and ingested several non-landmark papers on resistance training and sarcopenia.

                ## Supporting Evidence
                > "Resistance training increased strength in older adults (p=0.01)." — *source: pubmed:33333333*
                > "Muscle mass improved after a sarcopenia resistance program (p=0.02)." — *source: pubmed:34444444*

                ## Contradicting Evidence
                None found.

                ## Verdict
                **SUPPORTED** — because the retrieved trials reported strength gains.

                ## Limitations
                Did not retrieve the field-defining trials.

                ## References
                - pubmed:33333333
                - pubmed:34444444
                """;
        QualityScorer.ScoreResult r = score(report, metricsWithScouts(4),
                "resistance training improves muscle strength in sarcopenia");
        assertTrue(hasDefect(r, "DEFECT[golden-reference-zero-recall]"), "issues=" + r.issues());
    }

    @Test
    @DisplayName("verdict opposite to cited evidence -> DEFECT[verdict-contradicts-evidence]")
    void verdictContradictsEvidence() {
        String report = """
                ## Hypothesis
                Vitamin D supplementation reduces migraine frequency in adults.

                ## Methodology
                Reviewed trials of vitamin D supplementation for migraine frequency.

                ## Supporting Evidence
                None found.

                ## Contradicting Evidence
                > "Vitamin D supplementation did not reduce migraine days versus placebo (p=0.62)."
                > — *source: pubmed:31111111*
                > "No significant change in migraine frequency was observed (p=0.55)." — *source: pubmed:32222222*

                ## Verdict
                **SUPPORTED** — because vitamin D supplementation reduces migraine frequency.

                ## Limitations
                Heterogeneous dosing.

                ## References
                - pubmed:31111111
                - pubmed:32222222
                """;
        QualityScorer.ScoreResult r = score(report, metricsWithScouts(3),
                "vitamin D supplementation reduces migraine frequency in adults");
        assertTrue(hasDefect(r, "DEFECT[verdict-contradicts-evidence]"), "issues=" + r.issues());
    }

    @Test
    @DisplayName("verbatim quotes absent from retrieved corpus -> DEFECT[quote-fabrication-suspected]")
    void quoteFabrication() {
        // Retrieved corpus (>200 chars) is about an unrelated topic; the report's two evidence
        // quotes appear nowhere in it.
        String corpus = "Retrieved chunk: an observational cohort examined dietary fiber intake and "
                + "colorectal adenoma recurrence across multiple centers over a ten year horizon, "
                + "reporting baseline characteristics, adherence, and loss to follow up in detail "
                + "without any mention of the intervention claimed by the report under evaluation.";
        String report = """
                ## Hypothesis
                Empagliflozin reduces heart failure hospitalization in diabetic patients.

                ## Methodology
                Reviewed empagliflozin heart failure hospitalization trials.

                ## Supporting Evidence
                > "Empagliflozin reduced hospitalization for heart failure by 35% (p<0.001)." — *source: pubmed:36363636*
                > "The intervention improved cardiovascular outcomes significantly (HR 0.65)." — *source: pubmed:37373737*

                ## Contradicting Evidence
                None found.

                ## Verdict
                **SUPPORTED** — because empagliflozin reduced heart failure hospitalization.

                ## Limitations
                Industry funded.

                ## References
                - pubmed:36363636
                - pubmed:37373737
                """;
        QualityScorer.ScoreResult r = score(report, metricsWithScouts(4, corpus),
                "empagliflozin reduces heart failure hospitalization in diabetic patients");
        assertTrue(hasDefect(r, "DEFECT[quote-fabrication-suspected]"), "issues=" + r.issues());
    }
}
