package ai.intelliswarm.researchagent.eval;

import ai.intelliswarm.researchagent.agent.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scores the final research report on multiple quality dimensions.
 * Returns a {@link ScoreResult} record for downstream use by the eval module.
 */
public final class QualityScorer {

    /**
     * Sentinel value for scoreOverall when a run entered an unrecoverable gate-loop
     * (reportWriteCalls >= 20) and produced no report at all.
     * Distinct from 0.0 (which could be a written report with zero quality).
     * The eval dashboard must display this as "LOOP_FAILURE" rather than 0/10.
     */
    public static final double LOOP_FAILURE_SCORE = -1.0;

    // Scoring weights (must sum to 1.0)
    private static final double W_STRUCTURE  = 0.20;
    private static final double W_EVIDENCE   = 0.25;
    private static final double W_CITATIONS  = 0.20;
    private static final double W_BALANCE    = 0.15;
    private static final double W_VERDICT    = 0.10;
    private static final double W_EFFICIENCY = 0.10;

    // Fabricated UUID pattern for source IDs (e.g. pubmed:0f35858f-db46-4ee5-bea8-ac6fa7379f6a)
    private static final Pattern UUID_SOURCE_PATTERN = Pattern.compile(
            "(?:pubmed|pmid|openalex|arxiv)[:/][0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE);

    // openalex: or pubmed: prefixed IDs (real provenance labels)
    // FIX3: also match openalex DOI-form labels (openalex:10.xxxx/...) which are real citations
    // but were previously skipped because OPENALEX_LABEL only matched W-number form.
    private static final Pattern OPENALEX_LABEL = Pattern.compile(
            "openalex[:/](?:W\\d{6,12}|10\\.\\d{4,}/\\S+)", Pattern.CASE_INSENSITIVE);
    // FIX[pmid-regex-trailing-punctuation]: use possessive lookahead to exclude trailing non-digit
    // chars (period, comma) that appear when a citation is at end of sentence (pubmed:38179307.).
    // The (?=[^\d]|$) lookahead is a backward-compatible tightening — it prevents false PMID
    // matches with trailing punctuation that caused downstream lookup failures.
    private static final Pattern PUBMED_LABEL = Pattern.compile(
            "(?:pubmed|pmid)[:/](\\d{5,9})(?=[^\\d]|$)", Pattern.CASE_INSENSITIVE);

    // Verdict keyword — allow em-dash, colon, space, or end-of-token as suffix,
    // AND allow markdown-bold form **WORD** (e.g. **INCONCLUSIVE**, **SUPPORTED**).
    private static final Pattern VERDICT_SUPPORTED = Pattern.compile(
            "\\bsupported[\\s\\-—:,.)]|\\bsupported$|\\*\\*supported\\*\\*",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern VERDICT_REFUTED = Pattern.compile(
            "\\b(?:contradicted|refuted)[\\s\\-—:,.)]|\\b(?:contradicted|refuted)$"
            + "|\\*\\*(?:refuted|contradicted)\\*\\*",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern VERDICT_INSUFFICIENT = Pattern.compile(
            "insufficient\\s+evidence|\\*\\*insufficient\\s+evidence\\*\\*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VERDICT_INCONCLUSIVE = Pattern.compile(
            "\\binconclusive[\\s\\-—:,.)]|\\binconclusive$|\\*\\*inconclusive\\*\\*",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // Citation-collapse: same source cited >= 2 times in the report
    // FIX4: capture both W-number and DOI-form openalex IDs for collapse detection.
    private static final Pattern INLINE_SOURCE = Pattern.compile(
            "(?:pubmed|pmid|openalex)[:/]([\\w./:]+)", Pattern.CASE_INSENSITIVE);

    private QualityScorer() {}

    public record ScoreResult(
            double structure, double evidence, double citations,
            double balance, double verdict, double efficiency, double overall,
            List<String> issues, boolean citationValidationPassed
    ) {}

    /**
     * @param reportText  the full markdown report text
     * @param metrics     accumulated metrics for the run
     * @param session     the orchestrator session (for token counts)
     * @param wallMs      elapsed wall-clock milliseconds
     * @param hypothesis  the original hypothesis string (used for cross-check consistency)
     */
    public static ScoreResult compute(String reportText, MetricsCollector metrics,
                                       Session session, long wallMs, String hypothesis) {
        // ── Critical gate: no report produced ───────────────────────────────────
        if (reportText == null || reportText.isBlank()) {
            List<String> fatalIssues = new ArrayList<>();
            fatalIssues.add("DEFECT[no-report]: run produced no report (hit max iterations or crashed before report_write). "
                          + "All content scores are 0 — they would describe a different or absent hypothesis.");
            Map<String, Long> tc = metrics.toolCallCountByName();
            long reportWriteCalls = tc.getOrDefault("report_write", 0L);
            long totalCalls = tc.values().stream().mapToLong(Long::longValue).sum();
            long papersIngestedDnf = metrics.papersIngested();
            if (reportWriteCalls > 3) {
                // Distinguish: gate-loop-terminal (many retries, evidence present) vs
                // legitimate INSUFFICIENT EVIDENCE (few/no retries, no papers).
                if (reportWriteCalls >= 10) {
                    fatalIssues.add("DEFECT[gate-loop-terminal]: report_write was attempted " + reportWriteCalls
                            + " times but every attempt was gate-blocked — the run entered an unrecoverable "
                            + "infinite citation-fix loop and produced no output. "
                            + totalCalls + " total tool calls consumed. "
                            + "Root cause: the skip-nudge does not enumerate which specific citation IDs to remove, "
                            + "so the LLM cannot self-correct. Fix: (1) cap retries at 3 in ReportWriteTool, "
                            + "(2) inject the rejected IDs list into the nudge message.");
                } else {
                    fatalIssues.add("DEFECT[gate-loop]: report_write was called " + reportWriteCalls
                            + " times but no report was saved — gate-blocked infinite loop. "
                            + totalCalls + " total tool calls, " + reportWriteCalls
                            + " wasted on gate rejection. Fix: cap report_write retries at 3 in ReportWriteTool.");
                }
            } else if (papersIngestedDnf == 0 && reportWriteCalls == 0) {
                fatalIssues.add("DEFECT[no-report-no-evidence]: run produced no report AND ingested 0 papers "
                        + "with 0 report_write attempts — pipeline exited before synthesis. "
                        + "Likely cause: all scouts returned zero results and no fallback query was attempted.");
            } else if (papersIngestedDnf == 0 && reportWriteCalls > 0) {
                fatalIssues.add("DEFECT[no-report-zero-evidence]: report_write was attempted " + reportWriteCalls
                        + " time(s) but 0 papers were ingested — the agent tried to write a report with no evidence base. "
                        + "The report_write gate correctly blocked it. Distinct from citation-fix loops.");
            }
            // DEFECT[loop-failure-sentinel]: when reportWriteCalls >= 20, the run entered an unrecoverable
            // gate-loop and produced no report. Use LOOP_FAILURE_SCORE (-1.0) instead of 0.0 for scoreOverall
            // so the eval dashboard can distinguish this from a zero-quality written report.
            if (reportWriteCalls >= 20) {
                fatalIssues.add("DEFECT[loop-failure-sentinel]: run entered an unrecoverable gate-loop "
                        + "(reportWriteCalls=" + reportWriteCalls + " >= 20) and produced no report. "
                        + "scoreOverall is set to " + LOOP_FAILURE_SCORE + " (LOOP_FAILURE sentinel) to distinguish "
                        + "from a zero-quality written report. "
                        + "This sentinel must be handled by the eval dashboard: display as LOOP_FAILURE rather than 0/10.");
                return new ScoreResult(0, 0, 0, 0, 0, 0, LOOP_FAILURE_SCORE, fatalIssues, false);
            }
            return new ScoreResult(0, 0, 0, 0, 0, 0, 0, fatalIssues, false);
        }

        // ── Run-ended-with-error detection: score must reflect the failure ───────
        // (checked before scoring; if TurnResult.error() was true the caller should
        //  pass hypothesis="" to trigger the stale-report cross-check below)

        String lower = reportText.toLowerCase();
        List<String> issues = new ArrayList<>();

        // Pre-compute section indices used by multiple checks below
        int ceIdx = lower.indexOf("contradicting evidence");

        // ── Hypothesis / report consistency cross-check ─────────────────────────
        // If a non-blank hypothesis is provided, check that the report contains enough
        // domain-specific hypothesis terms to confirm it is not a stale file from a
        // prior run. The old 2-word / length>5 threshold was too weak: generic medical
        // terms such as "patients", "cancer", "overall" are present in most medical
        // reports and produced false-positive stale-report passes.
        //
        // FIX[stale-report-threshold]: Require EITHER:
        //   (a) ≥3 hypothesis words of length>5 present in report, AND
        //   (b) at least one "specific" term (length>8) from the hypothesis present,
        //       which excludes domain-generic words shared across all medical reports.
        // When stale-report fires, ALL content scores are forced to 0 because they
        // describe an unrelated report. Previously only an issue was logged.
        boolean isStaleReport = false;
        if (hypothesis != null && !hypothesis.isBlank()) {
            String[] hypoWords = hypothesis.toLowerCase().split("[\\s,;:()/]+");
            int matchCount = 0;
            int specificMatchCount = 0; // words length > 8 (domain-specific clinical terms)
            // Generic medical terms that appear in almost every medical report and
            // must NOT be counted toward stale-report detection.
            java.util.Set<String> genericTerms = java.util.Set.of(
                    "patients", "studies", "evidence", "clinical", "overall",
                    "cancer", "treatment", "outcomes", "limited", "cohort",
                    "therapy", "analysis", "disease", "medical", "results",
                    "reported", "compared", "effects", "trials", "review");
            for (String w : hypoWords) {
                if (w.length() > 5 && !genericTerms.contains(w) && lower.contains(w)) {
                    matchCount++;
                    if (w.length() > 8) specificMatchCount++;
                }
            }
            // Require ≥3 non-generic hypothesis words AND ≥1 specific (length>8) term
            boolean matchFailed = matchCount < 3 || specificMatchCount < 1;
            if (matchFailed) {
                isStaleReport = true;
                issues.add("DEFECT[stale-report]: report content does not match hypothesis — "
                        + "likely scoring a previous run's report file. "
                        + "Found " + matchCount + " non-generic hypothesis term(s) (need ≥3) and "
                        + specificMatchCount + " specific term(s) of length>8 (need ≥1). "
                        + "All content scores forced to 0. "
                        + "Fix: tie the report filename to the run ID so findLatestReport() cannot return stale files.");
            }
        }

        // ── Structure ────────────────────────────────────────────────────────────
        List<String> missing = new ArrayList<>();
        for (String[] kv : new String[][]{
                {"hypothesis", "Hypothesis"}, {"methodology", "Methodology"},
                {"supporting evidence", "Supporting Evidence"}, {"contradicting evidence", "Contradicting Evidence"},
                {"verdict", "Verdict"}, {"limitation", "Limitations"}, {"reference", "References"}}) {
            if (!lower.contains(kv[0])) missing.add(kv[1]);
        }
        double scoreStructure = ((7 - missing.size()) / 7.0) * 10.0;
        if (!missing.isEmpty()) issues.add("Missing sections: " + missing);

        // DEFECT[empty-evidence-sections]: all three evidence sections contain only 'None found' variants
        // — structural scaffolding without substantive content should not receive a perfect structure score.
        {
            boolean supportingEmpty  = isEvidenceSectionEmpty(lower, "supporting evidence");
            boolean contradictEmpty2 = isEvidenceSectionEmpty(lower, "contradicting evidence");
            boolean tangentialEmpty  = isEvidenceSectionEmpty(lower, "tangential");
            if (supportingEmpty && contradictEmpty2 && tangentialEmpty) {
                issues.add("DEFECT[empty-evidence-sections]: all evidence sections (Supporting, Contradicting, Tangential) "
                        + "contain only 'None found' — the report has structural scaffolding but no substantive content. "
                        + "scoreStructure capped at 6.0.");
                scoreStructure = Math.min(scoreStructure, 6.0);
            } else if (supportingEmpty && contradictEmpty2 && !tangentialEmpty) {
                // DEFECT[hollow-core-sections]: both core evidence sections are empty even though
                // tangential exists — the two sections that matter most for evidential reasoning
                // are empty, so full structural marks are undeserved.
                issues.add("DEFECT[hollow-core-sections]: both the Supporting Evidence and Contradicting Evidence "
                        + "sections contain only 'None found' — the two core evidential sections have no content. "
                        + "Full structural marks are undeserved. scoreStructure capped at 5.0. "
                        + "Fix: the evidence-appraiser must populate at least one section with real ingested findings "
                        + "before report_write is called.");
                scoreStructure = Math.min(scoreStructure, 5.0);
            }
        }

        // ── Evidence depth ───────────────────────────────────────────────────────
        // FIX: extended regex to match openalex:W... and pubmed:NNN labels in addition
        // to the original "source: xxx:nnn" patterns.
        // FIX2: also matches citations embedded in markdown headers (## Source: pubmed:NNN)
        // and parenthetical forms used by the evidence-appraiser (pubmed:NNN) or numerical quotes.
        // Evidence verbatim quotes: require at least one evidence-sentence marker OR 80+ chars.
        // Paper titles in "Title" format (noun phrases without verbs) must NOT be counted as
        // verbatim evidence quotes — they are bibliographic metadata, not retrieved findings.
        // A real quote from a paper contains verb-like evidence language or is a long sentence.
        long quotesRaw = countPattern(reportText, "\"[^\"]{20,}\"");
        long quotes;
        if (quotesRaw == 0) {
            quotes = 0;
        } else {
            // Filter: only count double-quoted strings that look like actual evidence text.
            // Evidence text contains verbs/findings/stats; paper titles are noun phrases.
            java.util.regex.Matcher qm = java.util.regex.Pattern.compile("\"([^\"]{20,})\"").matcher(reportText);
            long trueQuotes = 0;
            while (qm.find()) {
                String q = qm.group(1);
                boolean hasEvidenceMarker =
                        q.matches(".*\\b(?:found|showed|showed|indicated|demonstrated|reported|suggest|reduced|improved|"
                                + "decreased|increased|associated|resulted|observed|identified|estimated|measured|"
                                + "calculated|revealed|confirmed|concluded|determined)\\b.*")
                        || q.matches(".*\\b(?:p[\\s=<>]|HR|RR|OR|CI|hazard|ratio|odds|relative risk|confidence).*")
                        || q.length() >= 80; // long enough to be a sentence rather than a title
                if (hasEvidenceMarker) trueQuotes++;
            }
            quotes = trueQuotes;
        }
        long sourceLabels = countPattern(reportText,
                "\\*source:\\s*\\S+\\*"                      // *source: xxx*
              + "|\\(source:\\s*\\S+\\)"                     // (source: xxx)
              + "|source:\\s*[a-z]+[:/]\\S+"                 // source: pubmed:... or source: pubmed/...
              + "|(?m)^#+.*(?:pubmed|pmid|openalex)[:/]\\S+"  // ## heading containing pubmed:NNN
              + "|\\bpubmed:\\d{5,9}\\b"                     // inline pubmed:NNN
              + "|\\bpmid:\\d{5,9}\\b"                       // inline pmid:NNN
              + "|\\bopenalex:W\\d{6,12}\\b"                 // inline openalex:W...
              + "|\\bopenalex:10\\.\\d{4,}/\\S+"             // inline openalex DOI-form (openalex:10.xxxx/...)
        );
        // quotes already computed above (with evidence-marker filter)
        double scoreEvidence = Math.min(10.0, sourceLabels * 1.5 + quotes * 0.5);
        if (sourceLabels < 3) issues.add("Fewer than 3 labeled citations (found " + sourceLabels + ")");
        // Verbatim quote check: log issue AND apply score penalty (previously issue-only, no penalty)
        if (quotes == 0 && sourceLabels > 0) {
            issues.add("DEFECT[no-verbatim-quotes]: report cites " + sourceLabels + " source(s) but contains "
                    + "zero verbatim quotes (≥20 chars). Evidence sections must quote verbatim from retrieved "
                    + "RAG chunks — paraphrasing prevents verification. scoreEvidence penalised -3.0. "
                    + "Fix: evidence-appraiser must include exact text from rag_search results, not paraphrase.");
            scoreEvidence = Math.max(0, scoreEvidence - 3.0);
        } else if (quotes < 2) {
            issues.add("DEFECT[insufficient-verbatim-quotes]: only " + quotes + " verbatim quote(s) found "
                    + "for " + sourceLabels + " cited source(s). Each material claim should be backed by a "
                    + "verbatim quote from the retrieved chunk. scoreEvidence penalised -1.5.");
            scoreEvidence = Math.max(0, scoreEvidence - 1.5);
        }

        // ── Citations ────────────────────────────────────────────────────────────
        // FIX 1: count openalex:W... and pubmed:NNN labels as valid citation identifiers —
        //         not just raw 7-9 digit numbers which miss the prefixed forms.
        // FIX 2: also count raw PMIDs embedded in References sections.
        long pmids      = PUBMED_LABEL.matcher(reportText).results().count()
                        + countPattern(reportText, "\\b\\d{7,9}\\b"); // raw PMIDs in references
        long openalexIds = OPENALEX_LABEL.matcher(reportText).results().count();
        long dois       = countPattern(reportText, "10\\.\\d{4,}/\\S+");

        boolean hasValidation = lower.contains("citation validation") || lower.contains("confirmed in pubmed")
                             || lower.contains("confirmed in openalex");

        // DEFECT[sequential-pmid-fabrication]: detect suspiciously round or sequential PMIDs.
        // Real PMIDs are not round multiples of 1000, and genuine literature searches don't return
        // clusters of 3+ PMIDs that differ by <200 — that pattern is consistent with LLM hallucination.
        {
            List<Long> pmidValues = new ArrayList<>();
            Matcher pmidMatcher = PUBMED_LABEL.matcher(reportText);
            while (pmidMatcher.find()) {
                try { pmidValues.add(Long.parseLong(pmidMatcher.group(1))); } catch (NumberFormatException ignored) {}
            }
            if (pmidValues.size() >= 2) {
                long roundCount = pmidValues.stream().filter(v -> v % 1000 == 0).count();
                java.util.Collections.sort(pmidValues);
                // Detect sequential cluster: 3+ consecutive IDs all within 500 of each other
                // (threshold lowered from 200 to 500 to catch step-sizes typical of LLM fabrication)
                int seqClusterMax = 1, seqCurrent = 1;
                for (int si = 1; si < pmidValues.size(); si++) {
                    if (pmidValues.get(si) - pmidValues.get(si - 1) < 500) {
                        seqCurrent++;
                        seqClusterMax = Math.max(seqClusterMax, seqCurrent);
                    } else {
                        seqCurrent = 1;
                    }
                }

                // DEFECT[evenly-spaced-pmid-cluster]: third detection path — when 4+ PMIDs are
                // present and all fall within a 400,000-unit window with uniform spacing
                // (max_gap/min_gap < 2.0 ratio), flag as evenly-spaced fabrication cluster.
                // Does NOT fire when any single PMID is within 5 of a round-1000 multiple (that
                // would already trigger the existing roundCount check).
                boolean evenlySpacedCluster = false;
                if (pmidValues.size() >= 4) {
                    long windowSize = pmidValues.get(pmidValues.size() - 1) - pmidValues.get(0);
                    if (windowSize <= 400_000L) {
                        long minGap = Long.MAX_VALUE;
                        long maxGap = Long.MIN_VALUE;
                        for (int si = 1; si < pmidValues.size(); si++) {
                            long gap = pmidValues.get(si) - pmidValues.get(si - 1);
                            if (gap < minGap) minGap = gap;
                            if (gap > maxGap) maxGap = gap;
                        }
                        if (minGap > 0 && (double) maxGap / minGap < 2.0) {
                            // Check none is within 5 of a round-1000 multiple (which would trigger roundCount)
                            boolean anyNearRound = pmidValues.stream()
                                    .anyMatch(v -> (v % 1000) <= 5 || (1000 - v % 1000) <= 5);
                            if (!anyNearRound) {
                                evenlySpacedCluster = true;
                            }
                        }
                    }
                }

                // Composite fabrication score path: catches cases where individual checks miss
                // but the overall pattern is highly suspicious (e.g. DMARD PMIDs at ~175k spacing)
                double uniformGapRatio = Double.MAX_VALUE;
                if (pmidValues.size() >= 3) {
                    long minGapC = Long.MAX_VALUE;
                    long maxGapC = Long.MIN_VALUE;
                    for (int si = 1; si < pmidValues.size(); si++) {
                        long gap = pmidValues.get(si) - pmidValues.get(si - 1);
                        if (gap < minGapC) minGapC = gap;
                        if (gap > maxGapC) maxGapC = gap;
                    }
                    if (minGapC > 0) uniformGapRatio = (double) maxGapC / minGapC;
                }
                double fabricationScore = (roundCount * 0.4) + (seqClusterMax * 0.3)
                        + (uniformGapRatio < 1.5 ? 1.0 : 0.0);

                boolean fabricationFires = roundCount >= 2 || seqClusterMax >= 3
                        || evenlySpacedCluster || fabricationScore >= 1.5;
                if (fabricationFires) {
                    String reason;
                    if (evenlySpacedCluster) {
                        reason = pmidValues.size() + " PMIDs all within a 400k window with uniform gap ratio "
                                + String.format("%.2f", uniformGapRatio) + " (evenly-spaced fabrication cluster)";
                    } else if (roundCount >= 2) {
                        reason = roundCount + " PMIDs divisible by 1000 (suspiciously round)";
                    } else if (seqClusterMax >= 3) {
                        reason = "sequential cluster of " + seqClusterMax + " PMIDs within a 500-unit range";
                    } else {
                        reason = "composite fabrication score " + String.format("%.2f", fabricationScore)
                                + " >= 1.5 (uniformGapRatio=" + String.format("%.2f", uniformGapRatio)
                                + ", seqClusterMax=" + seqClusterMax + ", roundCount=" + roundCount + ")";
                    }
                    issues.add("DEFECT[sequential-pmid-fabrication]: report contains pubmed: IDs that are " + reason
                            + " — consistent with LLM-hallucinated PMIDs. scoreCitations penalised -3.0. "
                            + "Fix: run citation_validate against PubMed for all pubmed:NNN IDs before accepting them; "
                            + "reject IDs that return 404.");
                    // scoreCitations is initialised just below; apply penalty after initialisation
                }
            }
        }

        // Cap contribution so we don't double-count pubmed prefix + raw digit
        double scoreCitations = Math.min(10.0,
                Math.min(pmids, 10) * 1.2
              + openalexIds * 1.5
              + dois * 1.5
              + (hasValidation ? 2.0 : 0));

        boolean noCitationIds = pmids == 0 && dois == 0 && openalexIds == 0;

        // Apply DEFECT[sequential-pmid-fabrication] penalty now that scoreCitations is initialised
        // (the detection logic above set a flag via the issues list; re-check by scanning issues)
        if (issues.stream().anyMatch(i -> i.startsWith("DEFECT[sequential-pmid-fabrication]"))) {
            scoreCitations = Math.max(0, scoreCitations - 3.0);
        }

        // ── Unique source ID cap ─────────────────────────────────────────────────
        // Count distinct source IDs across the full report. A high raw scoreCitations
        // (e.g. 10.0) built from a single source repeated many times is misleading;
        // citation quality must reflect breadth, not repetition count.
        {
            java.util.Set<String> uniqueSrcIds = new java.util.HashSet<>();
            Matcher uSrc = INLINE_SOURCE.matcher(reportText);
            while (uSrc.find()) uniqueSrcIds.add(uSrc.group(1).toLowerCase());
            if (!noCitationIds && uniqueSrcIds.size() < 3) {
                double capValue = uniqueSrcIds.size() <= 1 ? 3.0 : 5.0;
                if (scoreCitations > capValue) {
                    issues.add("DEFECT[few-unique-sources]: only " + uniqueSrcIds.size()
                            + " unique source ID(s) detected in report — scoreCitations capped at " + capValue
                            + " (was " + String.format("%.1f", scoreCitations) + "). "
                            + "Citation existence ≠ citation quality; breadth requires ≥3 distinct sources. "
                            + "Fix: ensure evidence-appraiser ingests and cites ≥3 independent papers.");
                    scoreCitations = capValue;
                }
            }

            // DEFECT[gate-rejected-source-cited]: cross-reference cited IDs against relevance_filter
            // rejected IDs. If a cited source was explicitly rejected during this run, the pipeline
            // integrity is broken — the appraiser cited a gate-rejected source.
            List<String> rejectedIds = metrics.rejectedSourceIds();
            if (!rejectedIds.isEmpty() && !uniqueSrcIds.isEmpty()) {
                java.util.Set<String> rejectedSet = new java.util.HashSet<>(rejectedIds);
                java.util.Set<String> citedAndRejected = new java.util.HashSet<>(uniqueSrcIds);
                citedAndRejected.retainAll(rejectedSet);
                if (!citedAndRejected.isEmpty()) {
                    int overlapCount = citedAndRejected.size();
                    double penalty = Math.min(4.0, overlapCount * 2.0);
                    issues.add("DEFECT[gate-rejected-source-cited]: " + overlapCount
                            + " citation ID(s) in the final report were explicitly rejected by the relevance_filter "
                            + "gate during this run — the appraiser cited gate-rejected source(s): " + citedAndRejected
                            + ". This is a pipeline integrity failure. scoreCitations penalised -" + penalty
                            + " (capped at -4.0). "
                            + "Fix: the evidence-appraiser must only cite IDs from the rag_status-approved set; "
                            + "pass the accepted-ID list explicitly.");
                    scoreCitations = Math.max(0, scoreCitations - penalty);
                }
            }
        }

        // When the report declares INSUFFICIENT EVIDENCE and cites no sources this is correct
        // pipeline behaviour — do not label it as a DEFECT to avoid self-contradictory messages.
        // Use INFO[...] prefix so it appears in the issues list but is clearly not a scorer alarm.
        if (noCitationIds) {
            // Force scoreCitations to 0 regardless of hasValidation bonus
            scoreCitations = 0.0;
            if (VERDICT_INSUFFICIENT.matcher(reportText).find()) {
                issues.add("INFO[no-citations-insufficient]: report declares INSUFFICIENT EVIDENCE and cites no sources "
                        + "— this is correct pipeline behaviour. "
                        + "Review the relevance filter to confirm papers were genuinely absent and "
                        + "not silently discarded by an overly strict gate.");
            } else {
                issues.add("DEFECT[no-citations]: no PMIDs, DOIs, or OpenAlex IDs found — citations may be "
                        + "fabricated or the report was written without evidence. "
                        + "The citation regex covers pubmed:NNN, pmid:NNN, openalex:W..., DOI 10.xxx, "
                        + "and markdown-header embedded IDs — if IDs exist but were not matched, "
                        + "file a regex bug with a sample from the report.");
            }
        }
        if (!hasValidation) issues.add("Citation validation not included");

        // ── Fabricated UUID source ID detection ──────────────────────────────────
        if (UUID_SOURCE_PATTERN.matcher(reportText).find()) {
            issues.add("DEFECT[fabricated-uuid-source]: report contains a UUID-format source ID "
                    + "(e.g. pubmed:0f35858f-...) — this is not a real PMID. "
                    + "Evidence score and citations score are penalised. "
                    + "Fix: gate in ReportWriteTool must block UUID-format labels before writing.");
            scoreEvidence = Math.max(0, scoreEvidence - 3.0);
            scoreCitations = Math.max(0, scoreCitations - 3.0);
        }

        // ── Citation collapse: same source ID cited for multiple distinct claims ──
        java.util.Set<String> collapseSourceIds = new java.util.HashSet<>();
        {
            Map<String, Integer> sourceCounts = new java.util.LinkedHashMap<>();
            Matcher m = INLINE_SOURCE.matcher(reportText);
            while (m.find()) {
                String id = m.group(1).toLowerCase();
                sourceCounts.merge(id, 1, Integer::sum);
            }
            List<String> collapsed = sourceCounts.entrySet().stream()
                    .filter(e -> e.getValue() >= 2)
                    .map(e -> e.getKey() + " (x" + e.getValue() + ")")
                    .toList();
            if (!collapsed.isEmpty()) {
                issues.add("DEFECT[citation-collapse]: " + collapsed.size() + " source(s) cited for multiple "
                        + "separate claims without additional supporting sources: " + collapsed
                        + ". Evidence breadth is overstated.");
                scoreEvidence = Math.max(0, scoreEvidence - 1.5);
            }
            // Note: citation-collapse-in-contradicting check (scoreBalance deduction) runs after
            // scoreBalance is initialised in the Balance section below.
            collapseSourceIds = sourceCounts.entrySet().stream()
                    .filter(e -> e.getValue() >= 2)
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
        }

        // ── Balance ──────────────────────────────────────────────────────────────
        // FIX: "None found" in the Contradicting Evidence section means we have an
        // empty section — that does NOT deserve a perfect balance score.
        boolean hasSupports    = lower.contains("supports") || lower.contains("supporting evidence");
        boolean hasContradicts = lower.contains("contradicts") || lower.contains("contradicting evidence");

        // Detect empty contradicting-evidence section
        boolean contradictingEmpty = false;
        if (ceIdx >= 0) {
            // Grab up to 500 chars after the section header
            String ceSnippet = lower.substring(ceIdx, Math.min(ceIdx + 500, lower.length()));
            contradictingEmpty = ceSnippet.contains("none found")
                    || ceSnippet.contains("no contradicting")
                    || ceSnippet.contains("no studies found")
                    || ceSnippet.contains("no evidence found");
        }

        double scoreBalance;
        if (hasSupports && hasContradicts && !contradictingEmpty) {
            scoreBalance = 10.0;
        } else if (hasSupports && hasContradicts && contradictingEmpty) {
            scoreBalance = 5.0;
            issues.add("scoreBalance capped at 5.0: 'Contradicting Evidence' section exists but contains "
                    + "'None found' with no documented adversarial search effort. "
                    + "A perfect balance score requires substantive opposing evidence OR a logged multi-source adversarial search.");
        } else if (hasSupports || hasContradicts) {
            scoreBalance = 3.0;
            issues.add("Missing " + (!hasSupports ? "supporting" : "contradicting") + " evidence section");
        } else {
            scoreBalance = 0.0;
            issues.add("Missing both supporting and contradicting evidence sections");
        }

        // Balance-source-reuse: same source ID appears in both Supporting and Contradicting sections
        {
            int seIdx = lower.indexOf("supporting evidence");
            if (seIdx >= 0 && ceIdx >= 0 && !contradictingEmpty) {
                // Find the next section header after "supporting evidence" to bound the window
                int seEnd = lower.indexOf("\n#", seIdx + 1);
                if (seEnd < 0 || seEnd > ceIdx) seEnd = ceIdx; // fall back to CE start
                // DEFECT[balance-window-collapsed]: guard against collapsed window
                // When seEnd <= seIdx (no blank line before header), fall back to seIdx + 2000.
                // Also enforce a minimum window size of 50 chars to avoid spurious empty-set overlaps.
                if (seEnd <= seIdx) seEnd = Math.min(seIdx + 2000, lower.length());
                String supportingWindow = lower.substring(seIdx, seEnd);
                if (supportingWindow.length() < 50) {
                    issues.add("DEFECT[balance-window-collapsed]: supporting-evidence window for balance-source-reuse "
                            + "check collapsed to " + supportingWindow.length() + " chars (< 50) — "
                            + "source-reuse check skipped to avoid false positives from empty-set overlap. "
                            + "scoreBalance penalised -1.0 because window reliability cannot be confirmed. "
                            + "Fix: ensure a blank line or markdown header separates the Supporting Evidence "
                            + "and Contradicting Evidence sections.");
                    scoreBalance = Math.max(0, scoreBalance - 1.0);
                } else {
                    // Find the end of the contradicting-evidence section (next H2/H3 header)
                    int ceEnd = lower.indexOf("\n#", ceIdx + 1);
                    String contradictingWindow = lower.substring(ceIdx,
                            ceEnd > ceIdx ? ceEnd : Math.min(ceIdx + 2000, lower.length()));

                    // Extract source IDs from each window
                    java.util.Set<String> supportingSrcIds = new java.util.HashSet<>();
                    Matcher sm = INLINE_SOURCE.matcher(supportingWindow);
                    while (sm.find()) supportingSrcIds.add(sm.group(1).toLowerCase());

                    java.util.Set<String> contradictingSrcIds = new java.util.HashSet<>();
                    Matcher cm2 = INLINE_SOURCE.matcher(contradictingWindow);
                    while (cm2.find()) contradictingSrcIds.add(cm2.group(1).toLowerCase());

                    java.util.Set<String> overlap = new java.util.HashSet<>(supportingSrcIds);
                    overlap.retainAll(contradictingSrcIds);
                    if (!overlap.isEmpty()) {
                        issues.add("DEFECT[balance-source-reuse-misleading]: source(s) " + overlap
                                + " appear in both Supporting and Contradicting Evidence sections, actively "
                                + "misrepresenting balance — a single paper cannot genuinely support and contradict "
                                + "the same hypothesis. This is more severe than citation-collapse. "
                                + "scoreBalance penalised -3.5 (was -2.0). "
                                + "Fix: the evidence-appraiser must source opposing evidence from independent papers; "
                                + "the report_write gate should reject reports where the same ID appears on both sides.");
                        scoreBalance = Math.max(0, scoreBalance - 3.5);
                    }
                }
            }
        }

        // Citation-collapse within Contradicting Evidence section reduces scoreBalance
        // DEFECT[citation-collapse-in-contradicting]: apparent balance built on repeated same source
        if (!collapseSourceIds.isEmpty() && ceIdx >= 0) {
            int ceEndForCollapse = lower.indexOf("\n#", ceIdx + 1);
            String ceWindow = lower.substring(ceIdx,
                    ceEndForCollapse > ceIdx ? ceEndForCollapse : Math.min(ceIdx + 2000, lower.length()));
            boolean collapseInContra = collapseSourceIds.stream().anyMatch(ceWindow::contains);
            if (collapseInContra) {
                issues.add("DEFECT[citation-collapse-in-contradicting]: citation-collapse detected within "
                        + "the Contradicting Evidence section — the apparent balance is built on repeated use "
                        + "of the same source, not independent opposing evidence.");
                scoreBalance = Math.max(0, scoreBalance - 1.5);
            }
        }

        // DEFECT[balance-with-collapse]: scoreBalance must not be 10.0 when citation-collapse
        // is active — a report where all claims (supporting AND contradicting) draw from ≤2 unique
        // sources cannot have genuine balance. Cap to 5.0 to prevent the score contradiction
        // seen in iteration 4 (scoreBalance=10.0 + DEFECT[citation-collapse] simultaneously).
        if (!collapseSourceIds.isEmpty() && scoreBalance > 5.0) {
            issues.add("DEFECT[balance-with-collapse]: scoreBalance capped at 5.0 because citation-collapse "
                    + "is active (" + collapseSourceIds.size() + " source(s) reused across multiple claims). "
                    + "Genuine balance requires independent sources for supporting and contradicting sections.");
            scoreBalance = Math.min(scoreBalance, 5.0);
        }

        // Detect internal contradiction: Contradicting Evidence says "None found" but
        // Limitations section implies opposing evidence exists.
        if (contradictingEmpty) {
            int limIdx = lower.indexOf("limitation");
            if (limIdx >= 0) {
                String limSnippet = lower.substring(limIdx, Math.min(limIdx + 800, lower.length()));
                boolean limImpliesConflict = limSnippet.contains("conflicting")
                        || limSnippet.contains("adverse effect")
                        || limSnippet.contains("inconsistent")
                        || limSnippet.contains("mixed result")
                        || limSnippet.contains("contradict");
                if (limImpliesConflict) {
                    issues.add("DEFECT[internal-contradiction]: Contradicting Evidence section says 'None found' "
                            + "but Limitations section implies opposing evidence or adverse effects exist. "
                            + "The evidence-appraiser likely did not run an adversarial search.");
                    scoreBalance = Math.max(0, scoreBalance - 2.0);
                }
            }
        }

        // ── Journal-attribution count (pre-computed for verdict and narrative-mismatch checks) ────
        // FIX[narrative-citation-mismatch]: also matches markdown-italic journal attributions
        // such as 'published in *Annals of Internal Medicine* (2019)' and
        // 'published in *The Journal of Rheumatology* (2018)'. Hoisted here so both
        // DEFECT[verdict-no-quantitative-data] and DEFECT[narrative-citation-mismatch] can use it.
        long journalAttributions = countPattern(reportText,
                "\\b(?:Journal|Ann(?:als)?|Arch(?:ives)?|Circ(?:ulation)?|Lancet|NEJM|JAMA|BMJ|BMC|"
                + "Rheumatology|Arthritis|Diabetes|Cardiol(?:ogy)?|Neurology|Oncology|Medicine|Haematologica)"
                + "\\s+(?:&\\s+\\w+\\s+)?\\((?:19|20)\\d{2}\\)"
                // markdown-italic journal attribution: *Annals of Internal Medicine* (2019)
                + "|\\*[A-Z][^*]{5,60}\\*\\s*\\((?:19|20)\\d{2}\\)");

        // ── Verdict ──────────────────────────────────────────────────────────────
        // FIX: use Pattern matching so em-dash suffix (e.g. "SUPPORTED —") is not missed.
        // FIX: also recognise "INSUFFICIENT EVIDENCE" as a valid third verdict form.
        boolean verdictKeyword = VERDICT_SUPPORTED.matcher(reportText).find()
                || VERDICT_REFUTED.matcher(reportText).find()
                || VERDICT_INSUFFICIENT.matcher(reportText).find()
                || VERDICT_INCONCLUSIVE.matcher(reportText).find();
        boolean justification = lower.contains("because") || lower.contains("therefore")
                || lower.contains("evidence suggests");
        double scoreVerdict = (verdictKeyword ? 6.0 : 0.0) + (justification ? 4.0 : 0.0);
        if (!verdictKeyword) issues.add("No explicit verdict keyword (SUPPORTED, REFUTED, INCONCLUSIVE, or INSUFFICIENT EVIDENCE)");

        // DEFECT[verdict-evidence-contradiction]: verdict refers to specific study findings
        // but the evidence sections all contain "None found" — internal inconsistency.
        {
            boolean allEvidenceEmpty = isEvidenceSectionEmpty(lower, "supporting evidence")
                    && isEvidenceSectionEmpty(lower, "contradicting evidence");
            int verdictIdx = lower.indexOf("verdict");
            if (allEvidenceEmpty && verdictIdx >= 0) {
                String verdictSnippet = lower.substring(verdictIdx,
                        Math.min(verdictIdx + 600, lower.length()));
                // Verdict mentions specific study language despite empty evidence sections
                boolean verdictClaimsStudy = verdictSnippet.contains("stud")
                        || verdictSnippet.contains("trial")
                        || verdictSnippet.contains("paper")
                        || verdictSnippet.contains("evidence suggest")
                        || verdictSnippet.contains("rct")
                        || verdictSnippet.contains("identified");
                if (verdictClaimsStudy) {
                    issues.add("DEFECT[verdict-evidence-contradiction]: the Verdict section references specific "
                            + "study findings (e.g. 'only one study identified') while all Evidence sections contain "
                            + "'None found'. This is an internal inconsistency — the verdict was authored without "
                            + "grounding in reported evidence. scoreVerdict penalised.");
                    scoreVerdict = Math.max(0, scoreVerdict - 3.0);
                }
            }
        }

        // DEFECT[verdict-unsourced-guideline-claim]: the Verdict section contains a
        // guideline-attribution phrase (e.g. "guidelines favor LMWH for this population") but
        // no citation ID (PMID/DOI/OpenAlex) appears in the same section. Such unsourced claims
        // inflate the verdict score without any verifiable backing.
        {
            int verdictIdxUsg = lower.indexOf("verdict");
            if (verdictIdxUsg >= 0) {
                int verdictEndUsg = lower.indexOf("\n#", verdictIdxUsg + 1);
                String verdictSnippetUsg = lower.substring(verdictIdxUsg,
                        verdictEndUsg > verdictIdxUsg ? verdictEndUsg
                                : Math.min(verdictIdxUsg + 800, lower.length()));
                boolean hasGuidelinePhrase =
                        Pattern.compile("guidelines?\\s+(?:favor|favour|recommend|suggest|support)|current\\s+guidelines?",
                                Pattern.CASE_INSENSITIVE).matcher(verdictSnippetUsg).find();
                long verdictCitIds = OPENALEX_LABEL.matcher(verdictSnippetUsg).results().count()
                        + PUBMED_LABEL.matcher(verdictSnippetUsg).results().count()
                        + countPattern(verdictSnippetUsg, "10\\.\\d{4,}/\\S+");
                if (hasGuidelinePhrase && verdictCitIds == 0) {
                    issues.add("DEFECT[verdict-unsourced-guideline-claim]: the Verdict section contains a "
                            + "guideline-attribution phrase ('guidelines favor/recommend/suggest/support' or "
                            + "'current guidelines') but zero citation IDs (PMID/DOI/OpenAlex) appear in that "
                            + "section. Unsourced guideline claims inflate scoreVerdict without verifiable backing. "
                            + "scoreVerdict penalised -2.0. "
                            + "Fix: cite the specific guideline document (PMID or DOI) when attributing to guidelines.");
                    scoreVerdict = Math.max(0, scoreVerdict - 2.0);
                }
            }
        }

        // ── Golden reference check ───────────────────────────────────────────────
        // Compare cited PMIDs against known landmark trials for recognised hypothesis domains.
        // Fires INFO (no score penalty) when a domain matches but landmark PMIDs are absent.
        {
            String hypoLower = hypothesis != null ? hypothesis.toLowerCase() : "";
            List<String> goldenInfos = GoldenReference.checkMisses(hypoLower, lower);
            issues.addAll(goldenInfos);
            // DEFECT[no-golden-reference]: when no domain coverage exists at all
            if (!GoldenReference.hasCoverage(hypoLower, lower)) {
                issues.add("DEFECT[no-golden-reference]: hypothesis domain is not covered by GoldenReference "
                        + "— no landmark RCT recall check was performed. "
                        + "Citation recall against known key trials cannot be assessed. "
                        + "Registered domains: " + GoldenReference.DOMAINS.stream()
                            .map(GoldenReference.Domain::name).toList() + ". "
                        + "Add a new Domain entry in GoldenReference.java for this hypothesis area.");
            }
        }

        // ── Efficiency ───────────────────────────────────────────────────────────
        long papersIngested = metrics.papersIngested();
        double costUSD      = metrics.totalCostUSD(session);
        double wallMin      = wallMs / 60_000.0;
        double effPapers    = papersIngested >= 5 ? 10 : papersIngested >= 3 ? 7 : papersIngested >= 1 ? 4 : 0;
        double effCost      = costUSD < 0.05 ? 10 : costUSD < 0.10 ? 8 : costUSD < 0.20 ? 6 : 4;
        double effTime      = wallMin < 5 ? 10 : wallMin < 10 ? 8 : wallMin < 20 ? 6 : 4;
        double scoreEfficiency = (effPapers + effCost + effTime) / 3.0;
        if (papersIngested < 3) issues.add("Only " + papersIngested + " papers ingested (need ≥5)");
        if (costUSD > 0.30)     issues.add(String.format("High cost $%.4f", costUSD));


        // ── Failure-mode diagnostics ─────────────────────────────────────────────
        Map<String, Long> tc = metrics.toolCallCountByName();
        long todoCalls       = tc.getOrDefault("todo_write", 0L);
        long relevanceCalls  = tc.getOrDefault("relevance_filter", 0L);
        long fulltextCalls   = tc.getOrDefault("europepmc_fulltext", 0L);
        long unpaywallCalls  = tc.getOrDefault("unpaywall_lookup", 0L);
        long reportWriteCalls = tc.getOrDefault("report_write", 0L);
        long totalToolCalls  = tc.values().stream().mapToLong(Long::longValue).sum();
        int  subagentCount   = metrics.subagents().size();

        // Planning paralysis — two variants:
        // (a) classic: many todo_write calls and ZERO subagents spawned → never started researching
        // (b) overhead: subagents did spawn but disproportionate todo_write count suggests planning loops
        if (todoCalls >= 4 && subagentCount == 0) {
            // The consecutive-cap (3 in a row) fired — model tried to keep planning beyond the cap.
            // Research never started despite system-level intervention.
            issues.add("DEFECT[planning-paralysis]: " + todoCalls + " todo_write call(s) but 0 sub-agents spawned — "
                     + "orchestrator never started researching (the anti-paralysis consecutive cap fired). "
                     + "Fix: orchestrator must call subagent_spawn immediately after the first todo_write. "
                     + "Reinforce the 'plan once then act' rule in orchestrator.md.");
            scoreEfficiency = Math.max(0, scoreEfficiency - 2.0);
        } else if (todoCalls >= 3 && subagentCount > 0 && todoCalls >= subagentCount * 2L) {
            // More than 2 todo_write calls per subagent suggests significant planning overhead
            issues.add("DEFECT[planning-overhead]: " + todoCalls + " todo_write calls for only "
                     + subagentCount + " sub-agents — orchestrator spent disproportionate turns planning "
                     + "before spawning. Efficiency penalised. Fix: reduce planning iterations in orchestrator.md.");
            scoreEfficiency = Math.max(0, scoreEfficiency - 1.0);
        }

        // Gate-loop waste (report_write blocked repeatedly) — severity-scaled penalty
        if (reportWriteCalls > 3 && totalToolCalls > 0) {
            long wastedPct = (reportWriteCalls * 100L) / totalToolCalls;
            // Scale penalty: >3 calls → -3.0; >=10 calls → -5.0; >=20 calls → -7.0 (floor 0)
            double gateLoopPenalty = reportWriteCalls >= 20 ? 7.0
                                   : reportWriteCalls >= 10 ? 5.0 : 3.0;
            String severity = reportWriteCalls >= 20 ? "SEVERE" : reportWriteCalls >= 10 ? "CRITICAL" : "MODERATE";
            issues.add("DEFECT[gate-loop][" + severity + "]: report_write called " + reportWriteCalls + " times ("
                     + wastedPct + "% of " + totalToolCalls + " total tool calls) — gate-blocked "
                     + "infinite loop is burning context budget (severity: " + severity
                     + ", efficiency penalty: -" + (int)gateLoopPenalty + "). "
                     + "Fix: cap retries at 3 in ReportWriteTool "
                     + "and inject a 'fix the citation then resubmit once' instruction, not a re-trigger.");
            scoreEfficiency = Math.max(0, scoreEfficiency - gateLoopPenalty);

            // DEFECT[gate-loop-evidence-score-inflation]: when the report passed the gate despite
            // many retries, the final citation content was iteratively stripped under gate pressure.
            // Apply a scoreEvidence discount: citations may reflect gate-avoidance editing, not
            // evidential quality.
            if (reportText != null && !reportText.isBlank()) {
                if (reportWriteCalls >= 20) {
                    issues.add("DEFECT[gate-loop-evidence-score-inflation]: reportWriteCalls=" + reportWriteCalls
                            + " >= 20 — content may reflect gate-avoidance editing rather than evidential quality. "
                            + "scoreEvidence multiplied by 0.6 (40% discount).");
                    scoreEvidence = scoreEvidence * 0.6;
                } else if (reportWriteCalls >= 10) {
                    issues.add("DEFECT[gate-loop-evidence-score-inflation]: reportWriteCalls=" + reportWriteCalls
                            + " in [10,19] — content may reflect gate-avoidance editing rather than evidential quality. "
                            + "scoreEvidence multiplied by 0.8 (20% discount).");
                    scoreEvidence = scoreEvidence * 0.8;
                }

                // DEFECT[gate-loop-citation-trust-discount]: citations that survived iterative
                // gate-avoidance editing may be the last citations the model couldn't strip,
                // not the most relevant ones. Apply scoreCitations trust discount.
                if (reportWriteCalls >= 20) {
                    issues.add("DEFECT[gate-loop-citation-trust-discount]: reportWriteCalls=" + reportWriteCalls
                            + " >= 20 (SEVERE gate-loop) — citations survived iterative gate-avoidance editing; "
                            + "trust is reduced. scoreCitations multiplied by 0.7 (30% discount).");
                    scoreCitations = scoreCitations * 0.7;
                } else if (reportWriteCalls >= 10) {
                    issues.add("DEFECT[gate-loop-citation-trust-discount]: reportWriteCalls=" + reportWriteCalls
                            + " in [10,19] (CRITICAL gate-loop) — citations survived iterative gate-avoidance editing; "
                            + "trust is reduced. scoreCitations multiplied by 0.85 (15% discount).");
                    scoreCitations = scoreCitations * 0.85;
                }
            }
        }

        // Scout-respawn loop: same literature-scout type spawned 5+ times with zero ingestion
        long scoutCount = metrics.subagents().stream()
                .filter(a -> {
                    String t = a.type() != null ? a.type().toLowerCase() : "";
                    return t.contains("literature") || t.contains("scout");
                })
                .count();

        // DEFECT[scout-zero-ingest]: scout(s) ran (subagent_spawn calls) but ingested 0 papers,
        // AND searches did run. Distinct from scout-zero-yield (which fires on empty search results).
        // This fires when the scout completed its searches but then skipped the mandatory fetch/ingest step.
        {
            long searchCalls = tc.getOrDefault("pubmed_search", 0L) + tc.getOrDefault("openalex_search", 0L)
                             + tc.getOrDefault("arxiv_search", 0L) + tc.getOrDefault("semantic_scholar_search", 0L);
            long ragIngests = tc.getOrDefault("rag_ingest", 0L);
            if (papersIngested == 0 && searchCalls >= 4 && ragIngests == 0 && subagentCount >= 1) {
                issues.add("DEFECT[scout-zero-ingest]: " + searchCalls + " literature search(es) ran and "
                        + subagentCount + " subagent(s) completed, but rag_ingest was never called. "
                        + "The scout(s) found papers via search but skipped the mandatory fetch+ingest step — "
                        + "possibly completing after only the search phase (2-turn early exit). "
                        + "scoreEvidence penalised -2.0; scoreEfficiency penalised -2.0. "
                        + "Fix: scout prompt must enforce MANDATORY INGEST CHECK before writing Step 4 report; "
                        + "if rag_ingest count == 0 and searches returned results, scout must go back and ingest abstracts.");
                scoreEvidence = Math.max(0, scoreEvidence - 2.0);
                scoreEfficiency = Math.max(0, scoreEfficiency - 2.0);
            }
        }

        // DEFECT[too-few-scouts]: fewer than 3 scouts ran but papers were ingested.
        // A single scout covers one sub-concept cluster — corpus is too narrow for systematic review.
        if (papersIngested > 0 && scoutCount > 0 && scoutCount < 3) {
            issues.add("DEFECT[too-few-scouts]: only " + scoutCount + " literature-scout subagent(s) ran "
                    + "(minimum 3 required for adequate corpus coverage). "
                    + "A single scout covers one sub-concept cluster — with " + papersIngested + " paper(s) from "
                    + scoutCount + " scout(s), the evidence base is too narrow for a rigorous systematic review. "
                    + "scoreEfficiency penalised -1.5; scoreEvidence penalised -1.0. "
                    + "Fix: orchestrator must spawn ≥3 scouts before calling relevance_filter.");
            scoreEfficiency = Math.max(0, scoreEfficiency - 1.5);
            scoreEvidence = Math.max(0, scoreEvidence - 1.0);
        }

        if (scoutCount >= 5 && papersIngested == 0) {
            // Scale penalty: 5–9 scouts → -2.0; 10–19 scouts → -4.0; 20+ scouts → -6.0
            double scoutPenalty = scoutCount >= 20 ? 6.0 : scoutCount >= 10 ? 4.0 : 2.0;
            String scoutSeverity = scoutCount >= 20 ? "SEVERE" : scoutCount >= 10 ? "HIGH" : "MODERATE";
            issues.add("DEFECT[scout-respawn-loop][" + scoutSeverity + "]: " + scoutCount
                    + " literature-scout subagents ran but papersIngested==0 — orchestrator is "
                    + "re-queuing identical scouts instead of decomposing the query or escalating "
                    + "(severity: " + scoutSeverity + ", efficiency penalty: -" + (int)scoutPenalty + "). "
                    + "Fix: detect rag_status==0 after first scout and pivot to query reformulation.");
            scoreEfficiency = Math.max(0, scoreEfficiency - scoutPenalty);
        }

        // DEFECT[scout-null-crash]: any literature-scout that completed in <15s with <50 output tokens
        // is consistent with a null/exception crash rather than a genuine no-results search.
        // Penalise scoreEfficiency -1.0 per null-crash scout, capped at -2.0 total.
        {
            double nullCrashPenalty = 0.0;
            for (MetricsCollector.SubagentRecord sa : metrics.subagents()) {
                String saType = sa.type() != null ? sa.type().toLowerCase() : "";
                if ((saType.contains("literature") || saType.contains("scout"))
                        && sa.elapsedMs() < 15_000 && sa.outputTokens() < 50) {
                    issues.add("DEFECT[scout-null-crash]: literature-scout subagent completed in "
                            + sa.elapsedMs() + "ms with only " + sa.outputTokens() + " output tokens "
                            + "— consistent with a null exception crash rather than a genuine no-results search. "
                            + "The scout wasted a spawn slot without executing any search. "
                            + "scoreEfficiency penalised -1.0 (max -2.0 total). "
                            + "Fix: add null/exception handling in SubagentSpawnTool; propagate the error to the "
                            + "orchestrator as rag_status=ERROR so it can retry with a different query.");
                    nullCrashPenalty = Math.min(2.0, nullCrashPenalty + 1.0);
                }
            }
            if (nullCrashPenalty > 0) {
                scoreEfficiency = Math.max(0, scoreEfficiency - nullCrashPenalty);
            }
        }

        // Strategy stagnation: multiple scouts ran with very similar input-token counts
        // (a proxy for near-identical task descriptions, since task text drives token count).
        // When scoutCount >= 5 and all scouts have input tokens within ±15% of the median,
        // the orchestrator repeated the same query structure rather than reformulating.
        if (scoutCount >= 5) {
            List<Long> scoutInputs = metrics.subagents().stream()
                    .filter(a -> { String t = a.type() != null ? a.type().toLowerCase() : "";
                                   return t.contains("literature") || t.contains("scout"); })
                    .map(MetricsCollector.SubagentRecord::inputTokens)
                    .sorted()
                    .toList();
            if (!scoutInputs.isEmpty()) {
                long median = scoutInputs.get(scoutInputs.size() / 2);
                long band   = Math.max(200, median / 7); // ±14% band, min ±200 tokens
                long withinBand = scoutInputs.stream().filter(t -> Math.abs(t - median) <= band).count();
                if (withinBand >= scoutCount * 0.75) {
                    // 75%+ of scouts have near-identical token counts → stagnant strategy
                    issues.add("DEFECT[strategy-stagnation]: " + withinBand + "/" + scoutCount
                            + " literature-scouts had near-identical input token counts (median=" + median
                            + ", band=±" + band + ") — orchestrator likely repeated the same search strategy "
                            + "without reformulating. Fix: inject a 'no results found, try a different angle' "
                            + "directive after the first zero-yield scout; enforce diverse MeSH terms per spawn.");
                    scoreEfficiency = Math.max(0, scoreEfficiency - 1.5);
                }
            }
        }

        // Scout-budget-waste: scouts constitute >=60% of all subagents with zero ingestion
        if (scoutCount >= 3 && papersIngested == 0 && subagentCount > 0
                && scoutCount >= (long) Math.ceil(0.6 * subagentCount)) {
            long pct = (scoutCount * 100L) / subagentCount;
            issues.add("DEFECT[scout-budget-waste]: " + scoutCount + "/" + subagentCount
                    + " subagents were literature-scouts with zero ingestion yield — " + pct
                    + "% of the agent budget was wasted on redundant identical searches. "
                    + "Fix: check rag_status after the first scout; if 0 papers found, pivot to "
                    + "query decomposition before re-spawning.");
            scoreEfficiency = Math.max(0, scoreEfficiency - 2.0);
        }

        // Low-yield scout: papers ingested is very low relative to rag_ingest calls —
        // the scout ran many queries but most retrieved papers were off-topic.
        // A yield < 50% when >= 4 papers were attempted indicates poor query specificity.
        {
            long ragIngests = tc.getOrDefault("rag_ingest", 0L);
            long pubmedSearchCount = tc.getOrDefault("pubmed_search", 0L) + tc.getOrDefault("openalex_search", 0L);
            if (ragIngests >= 4 && papersIngested > 0 && papersIngested < ragIngests / 2) {
                long yieldPct = (papersIngested * 100L) / ragIngests;
                issues.add("DEFECT[low-yield-scout]: " + papersIngested + "/" + ragIngests
                        + " ingested papers passed relevance screening (" + yieldPct + "% yield) — "
                        + "the majority of retrieved papers were off-topic or wrong-intervention. "
                        + "Root cause is likely over-broad or poorly-specified PubMed/OpenAlex queries. "
                        + "Fix: add MeSH-term constraints and intervention-specific filters to scout queries.");
                scoreEvidence = Math.max(0, scoreEvidence - 1.5);
                scoreEfficiency = Math.max(0, scoreEfficiency - 1.0);
            } else if (pubmedSearchCount >= 2 && papersIngested == 0 && ragIngests == 0) {
                issues.add("DEFECT[scout-zero-yield]: " + pubmedSearchCount + " PubMed/OpenAlex search(es) ran "
                        + "but 0 papers were ingested (0 rag_ingest calls) — all retrieved results were filtered out "
                        + "or the search returned empty result sets on a topic with known RCT literature. "
                        + "Fix: broaden MeSH terms, remove year filters, or decompose the query.");
                scoreEvidence = Math.max(0, scoreEvidence - 2.0);
            }
        }

        // Anti-paralysis nudge failure (nudge fired 8+ times without escaping loop)
        if (todoCalls >= 5 && reportWriteCalls >= 5 && subagentCount == 0)
            issues.add("DEFECT[nudge-escape-failure]: both todo_write (" + todoCalls
                     + ") and report_write (" + reportWriteCalls
                     + ") are high with 0 subagents — anti-paralysis nudge mechanism failed to break the loop.");

        // Nudge-ineffectiveness-ratio: gate retries far exceed 3 but no report escaped —
        // the nudge text is structurally incapable of producing self-correction.
        // We infer nudge fires as floor(reportWriteCalls / 3) when retries > 9.
        if (reportWriteCalls >= 9) {
            long inferredNudgeFires = reportWriteCalls / 3;
            issues.add("DEFECT[nudge-ineffective]: ~" + inferredNudgeFires + " skip-nudge injection(s) inferred "
                    + "from " + reportWriteCalls + " report_write retries — nudge effectiveness ratio is 0%. "
                    + "The nudge message does not enumerate which specific citation IDs to remove. "
                    + "Fix: pass the gate-rejected ID list directly into the nudge text so the LLM can act on it.");
            scoreEfficiency = Math.max(0, scoreEfficiency - 2.0);
        }

        // DEFECT[nudge-identical-resubmit]: a stronger signal than nudge-ineffective —
        // the LLM resubmitted an identical report >= 2 times after skip-nudge injection,
        // meaning partial compliance is not detected (model is completely unresponsive).
        {
            long identicalResubmits = metrics.identicalResubmitCount();
            if (identicalResubmits >= 2) {
                issues.add("DEFECT[nudge-identical-resubmit]: the LLM resubmitted an identical report at least "
                        + identicalResubmits + " time(s) after skip-nudge injection — partial compliance is not "
                        + "detected, the model is completely unresponsive to nudges. "
                        + "scoreEfficiency penalised an additional -2.0 beyond the gate-loop penalty. "
                        + "Root cause: the nudge text does not enumerate specific IDs to fix; the model "
                        + "re-generates the same report from its context window. "
                        + "Fix: the skip-nudge must list the exact rejected IDs and instruct "
                        + "'remove only these, then resubmit'.");
                scoreEfficiency = Math.max(0, scoreEfficiency - 2.0);
            }
        }

        // PDF download waste: many pdf_download calls but zero ingestion
        long pdfDownloads = metrics.pdfDownloads();
        {
            if (pdfDownloads >= 5 && papersIngested == 0) {
                issues.add("DEFECT[pdf-download-waste]: " + pdfDownloads + " pdf_download calls "
                        + "but 0 papers ingested — all PDF fetches were unproductive. "
                        + "This represents significant tool-call budget consumed without yield. "
                        + "Fix: validate DOI/URL resolution before downloading; skip if fulltext already cached.");
                scoreEfficiency = Math.max(0, scoreEfficiency - 1.5);
            } else if (pdfDownloads >= 10 && papersIngested < pdfDownloads / 5) {
                // Many downloads but very low ingestion ratio
                long pdfYieldPct = papersIngested * 100L / pdfDownloads;
                issues.add("DEFECT[pdf-download-low-yield]: " + pdfDownloads + " pdf_download calls "
                        + "yielded only " + papersIngested + " ingested papers (" + pdfYieldPct + "% yield). "
                        + "Fix: add pre-download relevance check or URL validity gate.");
                scoreEfficiency = Math.max(0, scoreEfficiency - 1.0);
            }
        }

        // DEFECT[pdf-download-partial-failure]: middle tier for partial PDF failure between the
        // pdf-download-waste (0 ingested) and pdf-download-low-yield (>=10, <20%) thresholds.
        // Fires when at least 3 downloads failed (pdfDownloads - papersIngested >= 3) with >= 5
        // downloads total AND papersIngested > 0 (so pdf-download-waste does not also fire).
        if (pdfDownloads >= 5 && (pdfDownloads - papersIngested) >= 3 && papersIngested > 0) {
            double partialFailPenalty = Math.min(2.0, (pdfDownloads - papersIngested - 2) * 0.5);
            issues.add("DEFECT[pdf-download-partial-failure]: " + pdfDownloads + " pdf_download calls but only "
                    + papersIngested + " papers ingested — " + (pdfDownloads - papersIngested)
                    + " download(s) failed. scoreEfficiency penalised -"
                    + String.format("%.1f", partialFailPenalty) + " (capped at -2.0). "
                    + "Fix: validate DOI/URL resolution before downloading; add retry-with-backoff for "
                    + "transient HTTP failures; skip already-cached fulltexts.");
            scoreEfficiency = Math.max(0, scoreEfficiency - partialFailPenalty);
        }

        // DEFECT[premature-relevance-filter]: relevance_filter was called before any rag_ingest —
        // filtering an empty store wastes a tool slot and can produce a false zero-papers-passed signal.
        {
            List<MetricsCollector.ToolCallRecord> allCallsPrf = metrics.toolCalls();
            int relevanceFilterIdx = -1;
            int ragIngestIdx = -1;
            for (int i = 0; i < allCallsPrf.size(); i++) {
                String tname = allCallsPrf.get(i).toolName();
                if (relevanceFilterIdx < 0 && "relevance_filter".equals(tname)) relevanceFilterIdx = i;
                if (ragIngestIdx < 0 && "rag_ingest".equals(tname)) ragIngestIdx = i;
            }
            if (relevanceFilterIdx >= 0 && (ragIngestIdx < 0 || relevanceFilterIdx < ragIngestIdx)
                    && papersIngested == 0) {
                issues.add("DEFECT[premature-relevance-filter]: relevance_filter was called at tool-call index "
                        + relevanceFilterIdx + " before any rag_ingest "
                        + (ragIngestIdx < 0 ? "(rag_ingest was never called)" : "(first rag_ingest at index " + ragIngestIdx + ")")
                        + " with papersIngested==0. Filtering an empty RAG store wastes a tool slot and can "
                        + "produce a false zero-papers-passed signal, leading to an artefactual INSUFFICIENT EVIDENCE verdict. "
                        + "scoreEfficiency penalised -1.5. "
                        + "Fix: gate relevance_filter on rag_status > 0; only call it after at least one rag_ingest.");
                scoreEfficiency = Math.max(0, scoreEfficiency - 1.5);
                // Additional scoreVerdict penalty applied below (premature-filter flag)
            }
        }

        // DEFECT[fast-failure-efficiency-inflation]: run finished quickly/cheaply but ALL retrieval
        // paths failed (0 papers ingested despite 5+ download/fulltext attempts). Speed is not a
        // virtue when no useful work was done — apply a zero-yield multiplier to scoreEfficiency.
        if (papersIngested == 0 && (fulltextCalls >= 5 || pdfDownloads >= 5)) {
            issues.add("DEFECT[fast-failure-efficiency-inflation]: scoreEfficiency was near-perfect because the run "
                    + "finished quickly and cheaply, but ALL retrieval paths failed (0 papers ingested despite "
                    + fulltextCalls + " fulltext call(s) and " + pdfDownloads + " PDF download(s)). "
                    + "Speed is not a virtue when no work was accomplished. scoreEfficiency multiplied by 0.3 "
                    + "(zero-yield penalty). "
                    + "Fix: add a circuit-breaker in the scout that detects repeated HTTP failures and aborts early "
                    + "rather than burning the time budget on known-failing endpoints.");
            scoreEfficiency = Math.max(0, scoreEfficiency * 0.3);
        }

        if (papersIngested > 0 && relevanceCalls == 0)
            issues.add("DEFECT[relevance-gate-skipped]: relevance_filter was never called — off-topic/wrong-species "
                     + "papers can reach the report. Fix: make the relevance step mandatory (enforce in code, not prompt).");

        if (papersIngested > 0 && fulltextCalls == 0 && unpaywallCalls == 0)
            issues.add("DEFECT[abstract-only]: no europepmc_fulltext/unpaywall calls — likely ingested abstract "
                     + "landing pages, not full text. Evidence depth will be shallow.");

        // Shallow evidence-appraiser: ran <=2 turns OR token output < 800 regardless of turns.
        // FIX5: the prior threshold (<1000 tokens) only fired when turns<=2; at 3 turns with 555
        // tokens the check was bypassed. Added a standalone low-token path that fires independently
        // of turn count, because a 3-turn appraiser with 555 output tokens is genuinely inadequate.
        for (var appraisAgent : metrics.subagents()) {
            String appraisType = appraisAgent.type() != null ? appraisAgent.type().toLowerCase() : "";
            if (appraisType.contains("appraiser") || appraisType.contains("evidence_apprais")) {
                boolean tooFewTurns  = appraisAgent.turns() <= 2;
                boolean tooFewTokens = appraisAgent.outputTokens() < 800;
                if (tooFewTurns || tooFewTokens) {
                    issues.add("DEFECT[shallow-appraisal]: evidence-appraiser ran " + appraisAgent.turns()
                            + " turn(s) and produced " + appraisAgent.outputTokens() + " output tokens "
                            + (tooFewTurns ? "(too few turns)" : "") + (tooFewTokens ? "(too few tokens)" : "")
                            + " — insufficient depth for genuine adversarial evidence appraisal. "
                            + "Increase the appraiser turn budget or improve its prompt to surface opposing studies.");
                    scoreEvidence = Math.max(0, scoreEvidence - 1.5);
                    // Extra penalty when both are true (very shallow)
                    if (tooFewTurns && tooFewTokens) scoreEvidence = Math.max(0, scoreEvidence - 1.0);
                }
            }
        }

        // DEFECT[scout-false-ingest-claim]: a rag_ingest call's resultPreview claims successful
        // ingestion (contains "Ingested Papers" or "ingested N paper" with N>=1) but the
        // pipeline-level papersIngested counter is 0 after all scouts complete. This catches the
        // scout-hallucination bug where a completion message fabricates an ingestion count without
        // the rag_ingest actually recording a paper in the metrics.
        if (papersIngested == 0) {
            Pattern falseIngestPat = Pattern.compile(
                    "ingested\\s+papers|ingested\\s+([1-9]\\d*)\\s+papers?",
                    Pattern.CASE_INSENSITIVE);
            boolean scoutClaimsIngestion = metrics.toolCalls().stream()
                    .filter(r -> "rag_ingest".equals(r.toolName()))
                    .anyMatch(r -> falseIngestPat.matcher(r.resultPreview()).find());
            if (scoutClaimsIngestion) {
                issues.add("DEFECT[scout-false-ingest-claim]: one or more rag_ingest tool calls reported "
                        + "successful ingestion in their result preview, but pipeline-level papersIngested==0. "
                        + "A scout completion message fabricated an ingestion count without the rag_ingest call "
                        + "actually recording a paper. scoreEvidence penalised -2.0; scoreStructure penalised -1.0 "
                        + "because the methodology narrative will be built from the false claim. "
                        + "Fix: MetricsCollector.papersIngested() must be the authoritative source; "
                        + "gate evidence-appraiser spawn on metrics.papersIngested() > 0, not on rag_ingest resultPreview.");
                scoreEvidence = Math.max(0, scoreEvidence - 2.0);
                scoreStructure = Math.max(0, scoreStructure - 1.0);
            }
        }

        // Full-text subsystem completely down: all calls failed with zero ingestion
        if (fulltextCalls >= 5 && papersIngested == 0) {
            issues.add("DEFECT[fulltext-subsystem-down]: " + fulltextCalls
                    + " europepmc_fulltext calls all failed (papersIngested=0). "
                    + "The full-text retrieval subsystem is completely non-functional for this run. "
                    + "All evidence relied solely on metadata/abstracts at best.");
            scoreEvidence = Math.max(0, scoreEvidence - 2.5);
        } else if (fulltextCalls >= 3 && papersIngested < (fulltextCalls / 2)) {
            // Full-text EOF / retrieval failure: if europepmc_fulltext was called but papersIngested is low
            issues.add("DEFECT[fulltext-retrieval-failures]: " + fulltextCalls + " europepmc_fulltext calls "
                     + "but only " + papersIngested + " papers ingested — majority of full-text fetches likely "
                     + "returned EOF/empty. Report is built on abstract-only or partial evidence.");
            scoreEvidence = Math.max(0, scoreEvidence - 1.5);
        }

        // Thin evidence base: many papers rejected, no re-search spawned
        if (relevanceCalls > 0 && papersIngested > 0) {
            // Heuristic: if the report mentions a high rejection rate and evidence base is tiny
            long ragIngests = tc.getOrDefault("rag_ingest", 0L);
            if (ragIngests > 0 && papersIngested <= 2 && ragIngests >= 5) {
                issues.add("DEFECT[thin-evidence-no-retry]: " + ragIngests + " rag_ingest calls but only "
                        + papersIngested + " papers passed relevance — agent accepted a thin evidence base "
                        + "without spawning a second literature-scout. Fix: trigger retry search when <3 papers remain after screening.");
                scoreEvidence = Math.max(0, scoreEvidence - 1.0);
            }
        }

        // Wrong-species / non-clinical study cited as evidence
        // Scoped to the Supporting Evidence section only to avoid false-positives from
        // rejection-log entries in Methodology that explain why papers were excluded.
        {
            int seIdxWS = lower.indexOf("supporting evidence");
            String searchScope = lower; // fall back to full text if section not found
            if (seIdxWS >= 0) {
                int seEndWS = lower.indexOf("\n#", seIdxWS + 1);
                searchScope = lower.substring(seIdxWS,
                        seEndWS > seIdxWS ? seEndWS : Math.min(seIdxWS + 3000, lower.length()));
            }
            for (String marker : new String[]{"lamb", "sheep", "bovine", "murine", " rats ", " mice ",
                    "in vitro", "in-vitro", "myotube", "carcass", "finishing diet", "feed ingredient"}) {
                if (searchScope.contains(marker)) {
                    issues.add("DEFECT[wrong-species-citation-scoped]: animal/in-vitro marker '"
                            + marker.trim() + "' found within the Supporting Evidence section "
                            + "— verify this study is not being cited as human clinical evidence "
                            + "(check: was this term in a rejection-log entry or in actual cited evidence?).");
                    break;
                }
            }
        }

        // Wrong-intervention detection: the scout returned papers whose intervention is
        // categorically different from the hypothesis intervention. Scoped to the
        // Methodology section (which typically lists retrieved papers before filtering).
        // This catches cases like a vancomycin scout returning herbal medicine papers.
        {
            int methIdx = lower.indexOf("methodology");
            if (methIdx >= 0) {
                int methEnd = lower.indexOf("\n#", methIdx + 1);
                String methScope = lower.substring(methIdx,
                        methEnd > methIdx ? methEnd : Math.min(methIdx + 3000, lower.length()));
                for (String wrongIntv : new String[]{
                        "herbal medicine", "traditional chinese medicine", "tcm",
                        "decoction", "acupuncture", "homeopath",
                        "hiv care", "cancer care", "cognitive behavioral therapy",
                        "cbt", "gynecologic", "palliative care"}) {
                    if (methScope.contains(wrongIntv)) {
                        issues.add("DEFECT[wrong-intervention-ingested]: methodology section mentions '"
                                + wrongIntv + "' — a paper with a categorically different intervention was "
                                + "retrieved by the scout. This indicates the search query was too broad or "
                                + "lacked intervention-specific MeSH terms. "
                                + "Fix: add explicit intervention constraints to PubMed/OpenAlex queries.");
                        scoreEvidence = Math.max(0, scoreEvidence - 1.0);
                        break;
                    }
                }
            }
        }

        // Ingested-count mismatch: report narrative claims N papers but metrics differ
        {
            Pattern ingestedClaimPat = Pattern.compile(
                    "(\\d+)\\s+papers?\\s+(?:ingested|included|retrieved)", Pattern.CASE_INSENSITIVE);
            Matcher icm = ingestedClaimPat.matcher(reportText);
            while (icm.find()) {
                long reported = Long.parseLong(icm.group(1));
                if (reported > papersIngested + 2) {
                    issues.add("DEFECT[ingested-count-mismatch]: Methodology section states '" + reported
                            + "' papers ingested but pipeline metrics recorded " + papersIngested
                            + " — internal inconsistency indicates a fabricated or stale methodology narrative.");
                    scoreStructure = Math.max(0, scoreStructure - 1.5);
                    break;
                }
            }
        }

        // DEFECT[no-rag-synthesis]: papers were ingested into the RAG store but the agent
        // performed zero rag_search calls during synthesis. All evidence quoted in the report
        // must therefore come from the model's prior knowledge rather than the ingested corpus
        // — this is a hallucination risk even when the paper count looks good.
        // Only fires when papersIngested>0 to avoid double-counting with scout-zero-yield.
        {
            long ragSearches = metrics.ragSearchesRun();
            if (papersIngested > 0 && ragSearches == 0) {
                issues.add("DEFECT[no-rag-synthesis]: " + papersIngested
                        + " paper(s) were ingested but rag_search was never called during synthesis "
                        + "(RAG pipeline integrity dimension). "
                        + "All evidence in the report was generated from model prior knowledge, not from "
                        + "the ingested corpus — hallucination risk is HIGH. "
                        + "Fix: the evidence-appraiser prompt must call rag_search before quoting any finding.");
                scoreEvidence = Math.max(0, scoreEvidence - 3.0);
                scoreCitations = Math.max(0, scoreCitations - 2.0);
                // DEFECT[no-rag-synthesis-balance]: balance sections cannot contain independently-retrieved
                // opposing evidence if rag_search was never called — cap scoreBalance at 3.0.
                issues.add("DEFECT[no-rag-synthesis-balance]: rag_search was never called despite papers being "
                        + "ingested — not only is evidence depth compromised (DEFECT[no-rag-synthesis] already fires), "
                        + "but the balance sections also cannot contain independently-retrieved opposing evidence. "
                        + "scoreBalance capped at 3.0 — balance sections cannot be RAG-grounded without rag_search calls.");
                scoreBalance = Math.min(scoreBalance, 3.0);
            }
        }

        // DEFECT[rag-search-empty-chunks]: rag_search was called but all result previews indicate
        // empty/zero-results despite papersIngested>0 — the ingested papers did not survive
        // chunking or were abstract-only with near-empty text. Distinct from no-rag-synthesis
        // (where rag_search is never called).
        {
            long ragSearches = metrics.ragSearchesRun();
            if (papersIngested > 0 && ragSearches > 0) {
                Pattern emptyChunkPat = Pattern.compile(
                        "no results|no matching|0 chunks|0 results|empty|no documents?\\s+found",
                        Pattern.CASE_INSENSITIVE);
                long totalRagSearchCalls = metrics.toolCalls().stream()
                        .filter(r -> "rag_search".equals(r.toolName()))
                        .count();
                long emptyRagSearchCalls = metrics.toolCalls().stream()
                        .filter(r -> "rag_search".equals(r.toolName()))
                        .filter(r -> r.resultPreview() != null
                                && emptyChunkPat.matcher(r.resultPreview()).find())
                        .count();
                if (totalRagSearchCalls > 0 && emptyRagSearchCalls == totalRagSearchCalls) {
                    issues.add("DEFECT[rag-search-empty-chunks]: all " + totalRagSearchCalls
                            + " rag_search call(s) returned empty/no-results previews despite "
                            + papersIngested + " paper(s) ingested — the ingested papers did not survive "
                            + "chunking or were abstract-only with near-empty text. "
                            + "The appraiser ran on an uninformative RAG corpus. scoreEvidence penalised -2.0. "
                            + "Fix: ensure full-text content (not just metadata) is passed to rag_ingest; "
                            + "verify chunking configuration produces non-empty chunks.");
                    scoreEvidence = Math.max(0, scoreEvidence - 2.0);
                }
            }
        }

        // DEFECT[relevance-filter-over-rejection]: pubmed_search or openalex_search returned
        // results AND relevance_filter was called, yet 0 papers survived to ingestion.
        // When a known-relevant domain has literature (searches returned hits), a zero-ingestion
        // outcome most likely means the relevance filter was over-strict — not that no evidence exists.
        // Distinct from scout-zero-yield (which fires when searches themselves return empty).
        {
            long pubmedSearches = tc.getOrDefault("pubmed_search", 0L)
                                + tc.getOrDefault("openalex_search", 0L);
            long ragIngests = tc.getOrDefault("rag_ingest", 0L);
            if (pubmedSearches >= 1 && relevanceCalls >= 1 && ragIngests == 0 && papersIngested == 0) {
                issues.add("DEFECT[relevance-filter-over-rejection]: " + pubmedSearches
                        + " literature search(es) ran and relevance_filter was called " + relevanceCalls
                        + " time(s), but 0 papers were ingested. "
                        + "If the searched domain has known RCT literature, the filter may be over-strict. "
                        + "Inspect the relevance_filter rejection log: if any paper with a directly matching "
                        + "PICO (population, intervention, comparator, outcome) was rejected, this is a "
                        + "filter false-negative. Fix: relax the evidence_level filter or log rejections "
                        + "with their titles so manual review is possible. "
                        + "scoreVerdict penalised because INSUFFICIENT EVIDENCE may be a pipeline artefact.");
                scoreVerdict = Math.max(0, scoreVerdict - 2.0);
                scoreEvidence = Math.max(0, scoreEvidence - 1.0);
            }
        }

        // DEFECT[pre-ingestion-appraiser]: evidence-appraiser ran before any papers were
        // ingested. An appraiser that runs with 0 ingested papers has no RAG context to draw
        // from — its output is necessarily model prior knowledge, not evidence appraisal.
        // This is more serious than shallow-appraisal: the output may have influenced the
        // report writer with hallucinated "evidence."
        // Detection: check whether any appraiser subagent was recorded before the first
        // rag_ingest tool call in the ordered toolCalls list.
        {
            List<MetricsCollector.ToolCallRecord> allCalls = metrics.toolCalls();
            long firstRagIngestIdx = -1;
            for (int i = 0; i < allCalls.size(); i++) {
                if ("rag_ingest".equals(allCalls.get(i).toolName())) { firstRagIngestIdx = i; break; }
            }
            if (firstRagIngestIdx < 0 && papersIngested == 0) {
                // Check if any appraiser subagent ran at all
                boolean appraiserRan = metrics.subagents().stream().anyMatch(a -> {
                    String t = a.type() != null ? a.type().toLowerCase() : "";
                    return t.contains("appraiser") || t.contains("evidence_apprais");
                });
                if (appraiserRan) {
                    issues.add("DEFECT[pre-ingestion-appraiser]: evidence-appraiser ran but 0 papers were ever "
                            + "ingested into the RAG store. The appraiser had no retrieved evidence to work with "
                            + "and its output is necessarily model prior knowledge — hallucination risk is CRITICAL. "
                            + "The report writer may have accepted hallucinated appraisal output as real evidence. "
                            + "Fix: gate the evidence-appraiser spawn on rag_status > 0.");
                    scoreEvidence = Math.max(0, scoreEvidence - 3.0);
                }
            }
        }

        // DEFECT[verdict-no-quantitative-data]: verdict is SUPPORTED or INCONCLUSIVE but contains
        // no quantitative effect measures (HR, RR, OR, CI, p-value, %), making it unverifiable.
        // A clinically valid verdict for an intervention hypothesis must cite at least one
        // numeric result. Detectable via absence of common ratio/statistic patterns in the
        // verdict section.
        {
            int verdictIdx2 = lower.indexOf("verdict");
            if (verdictIdx2 >= 0) {
                int verdictEnd2 = lower.indexOf("\n#", verdictIdx2 + 1);
                String verdictSnippet2 = lower.substring(verdictIdx2,
                        verdictEnd2 > verdictIdx2 ? verdictEnd2 : Math.min(verdictIdx2 + 800, lower.length()));
                // Use Pattern with DOTALL so '.' matches newlines in multi-line verdict snippets
                boolean hasQuantData =
                        Pattern.compile(".*\\b(?:hr|rr|or|ci|hazard|ratio|p[- =<](?:value|0\\.|<)).*",
                                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(verdictSnippet2).matches()
                        || Pattern.compile(".*\\d+\\.?\\d*\\s*%.*", Pattern.DOTALL).matcher(verdictSnippet2).matches()
                        || Pattern.compile(".*\\d+\\.\\d+\\s*(?:95%|\\(95).*", Pattern.DOTALL).matcher(verdictSnippet2).matches();
                boolean verdictMakesStrengthClaim = (VERDICT_SUPPORTED.matcher(reportText).find()
                        || VERDICT_INCONCLUSIVE.matcher(reportText).find())
                        && !VERDICT_INSUFFICIENT.matcher(reportText).find();
                // FIX[verdict-no-quantitative-data]: widen guard from 'sourceLabels >= 2' to also
                // fire when journalAttributions >= 2 — journal-name-style citations ('Rheumatology (2020)')
                // are NOT matched by INLINE_SOURCE / sourceLabels counter, so the check was suppressed
                // even when 6 fabricated journal attributions existed.
                if (verdictMakesStrengthClaim && !hasQuantData && (sourceLabels >= 2 || journalAttributions >= 2)) {
                    issues.add("DEFECT[verdict-no-quantitative-data]: verdict is "
                            + (VERDICT_SUPPORTED.matcher(reportText).find() ? "SUPPORTED" : "INCONCLUSIVE")
                            + " but the Verdict section contains no quantitative effect measures "
                            + "(HR, RR, OR, CI, p-value, or %). A hypothesis verdict without numeric data "
                            + "cannot be independently verified. scoreVerdict penalised.");
                    scoreVerdict = Math.max(0, scoreVerdict - 2.0);
                }
            }
        }

        // DEFECT[narrative-citation-mismatch]: the Supporting or Contradicting Evidence section
        // attributes findings to named journals/years (e.g. "Rheumatology (2020)") but the
        // References section contains different PMID/DOI identifiers — the narrative prose was
        // fabricated from model prior knowledge, not from the cited papers.
        // Detection heuristic: journal-attribution pattern present in evidence sections AND
        // the number of unique citation IDs in the full report is ≤ the number of journal
        // attributions (meaning many attributions lack matching IDs).
        {
            // journalAttributions is pre-computed above (before Verdict section) so both
            // DEFECT[verdict-no-quantitative-data] and DEFECT[narrative-citation-mismatch] share it.
            java.util.Set<String> uniqueIdsInReport = new java.util.HashSet<>();
            Matcher uSrc2 = INLINE_SOURCE.matcher(reportText);
            while (uSrc2.find()) uniqueIdsInReport.add(uSrc2.group(1).toLowerCase());
            // FIX[narrative-citation-mismatch]: strict '<' allowed 4 IDs vs 4 attributions to silently
            // pass even when all attributions are fabricated. Changed to also fire when
            // journalAttributions>=3 and uniqueIdsInReport.size() <= journalAttributions.
            if (journalAttributions >= 2 && (uniqueIdsInReport.size() < journalAttributions
                    || (journalAttributions >= 3 && uniqueIdsInReport.size() <= journalAttributions))) {
                issues.add("DEFECT[narrative-citation-mismatch]: " + journalAttributions
                        + " journal-attribution pattern(s) found (e.g. 'Rheumatology (2020)') but only "
                        + uniqueIdsInReport.size() + " unique citation IDs in the report. "
                        + "The evidence narrative attributes findings to journals not backed by "
                        + "matching PMIDs/DOIs — the prose may be fabricated from model prior knowledge. "
                        + "Fix: the evidence-appraiser must only cite findings from papers that have "
                        + "been retrieved and given a real PMID or OpenAlex ID.");
                scoreEvidence = Math.max(0, scoreEvidence - 2.5);
                scoreCitations = Math.max(0, scoreCitations - 2.0);
            }
        }

        // DEFECT[methodology-rag-not-called]: Methodology section claims papers were ingested
        // but rag_ingest was never called (pipeline counted 0 papers). This is distinct from
        // ingested-count-mismatch: here the report literally claims ingestion happened but the
        // tool was never called — the methodology narrative is factually fabricated.
        {
            long ragIngests2 = tc.getOrDefault("rag_ingest", 0L);
            if (ragIngests2 == 0 && papersIngested == 0) {
                int methIdx2 = lower.indexOf("methodology");
                if (methIdx2 >= 0) {
                    int methEnd2 = lower.indexOf("\n#", methIdx2 + 1);
                    String methSnippet2 = lower.substring(methIdx2,
                            methEnd2 > methIdx2 ? methEnd2 : Math.min(methIdx2 + 1000, lower.length()));
                    // Detect claims of paper ingestion in methodology when rag_ingest=0
                    // Pattern.DOTALL needed so '.' matches newlines in multi-line methodology snippets
                    boolean claimsIngestion =
                            Pattern.compile(".*\\d+\\s+papers?\\s+(?:were\\s+)?(?:ingested|screened|included|retrieved).*",
                                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(methSnippet2).matches()
                            || methSnippet2.contains("total of") && methSnippet2.contains("paper");
                    if (claimsIngestion) {
                        issues.add("DEFECT[methodology-rag-not-called]: Methodology section claims papers were "
                                + "ingested but rag_ingest was never called (pipeline papersIngested=0, ragIngests=0). "
                                + "The methodology narrative is factually fabricated — the agent applied "
                                + "relevance_filter to search metadata only, without actually ingesting any paper "
                                + "into the RAG store. scoreStructure penalised for fabricated operational claims.");
                        scoreStructure = Math.max(0, scoreStructure - 2.0);
                    }
                }
            }
        }

        // DEFECT[rate-limit-scout-loop]: scout respawn loop where scouts completed very quickly
        // (low elapsedMs per scout) despite zero ingestion — fast failures suggest HTTP 429
        // rate-limit rejections rather than genuine search exhaustion. Distinct from
        // strategy-stagnation (which fires on similar token counts regardless of speed).
        {
            List<MetricsCollector.SubagentRecord> scoutRecords = metrics.subagents().stream()
                    .filter(a -> { String t = a.type() != null ? a.type().toLowerCase() : "";
                                   return t.contains("literature") || t.contains("scout"); })
                    .toList();
            if (scoutRecords.size() >= 3 && papersIngested == 0) {
                // "Fast failure" = scout completed in < 8 seconds AND produced very few output tokens
                long fastFailScouts = scoutRecords.stream()
                        .filter(a -> a.elapsedMs() < 8_000 && a.outputTokens() < 300)
                        .count();
                if (fastFailScouts >= 2) {
                    issues.add("DEFECT[rate-limit-scout-loop]: " + fastFailScouts + "/" + scoutRecords.size()
                            + " literature-scouts completed in <8s with <300 output tokens and 0 papers ingested "
                            + "— fast failures are consistent with HTTP 429 rate-limit rejections (PubMed/OpenAlex). "
                            + "The orchestrator should detect 429 errors and apply exponential backoff before "
                            + "re-spawning scouts. Fix: add rate-limit detection in LiteratureScoutTool and "
                            + "propagate the backoff signal to the orchestrator.");
                    scoreEfficiency = Math.max(0, scoreEfficiency - 2.0);
                }
            }
        }

        // DEFECT[premature-relevance-filter] scoreVerdict penalty: an INSUFFICIENT EVIDENCE verdict
        // caused by premature filtering is a pipeline artefact, not a true absence of evidence.
        if (issues.stream().anyMatch(i -> i.startsWith("DEFECT[premature-relevance-filter]"))) {
            issues.add("DEFECT[premature-relevance-filter][verdict-penalty]: INSUFFICIENT EVIDENCE verdict "
                    + "may be a pipeline artefact caused by premature relevance_filter call. "
                    + "scoreVerdict penalised -1.0.");
            scoreVerdict = Math.max(0, scoreVerdict - 1.0);
        }

        // DEFECT[pubmed-rate-limit-no-backoff]: direct signal from HTTP 429 errors in tool call
        // resultPreviews. Replaces the proxy heuristic of rate-limit-scout-loop with a count of
        // actual 429 / rate-limit / too-many-requests occurrences across all tool call records.
        {
            long rateLimitErrors = metrics.rateLimitErrorCount();
            if (rateLimitErrors >= 10) {
                double rlPenalty = Math.min(3.0, rateLimitErrors / 10.0);
                issues.add("DEFECT[pubmed-rate-limit-no-backoff]: " + rateLimitErrors
                        + " HTTP 429 / rate-limit error(s) detected in tool call result previews "
                        + "— the pipeline did not apply backoff before retrying PubMed/OpenAlex requests. "
                        + "scoreEfficiency penalised -" + String.format("%.1f", rlPenalty) + " (capped at -3.0). "
                        + "Fix: add exponential backoff in PubmedSearchTool and OpenAlexSearchTool; "
                        + "propagate 429 signals to the orchestrator so it pauses before re-spawning scouts.");
                scoreEfficiency = Math.max(0, scoreEfficiency - rlPenalty);
            }
        }

        // DEFECT[insufficient-evidence-despite-literature]: the verdict is INSUFFICIENT EVIDENCE
        // but the report itself references real literature IDs — the verdict is an artefact of
        // ingestion failure, not a true absence of published evidence on the topic.
        // Also fires when pipeline metrics show searches ran and papers were partially found.
        if (VERDICT_INSUFFICIENT.matcher(reportText).find() && !noCitationIds) {
            issues.add("DEFECT[insufficient-evidence-despite-literature]: verdict is INSUFFICIENT EVIDENCE "
                    + "but the report contains citation IDs (" + (pmids + openalexIds + dois) + " found) — "
                    + "the INSUFFICIENT EVIDENCE outcome may be a pipeline artefact (ingestion failure, "
                    + "relevance-filter over-rejection) rather than a true absence of published evidence. "
                    + "Verify: check rag_status, relevance_filter rejection logs, and pubmed_search result counts.");
            scoreVerdict = Math.max(0, scoreVerdict - 2.0);
        }

        // Verdict not backed by enough relevant evidence
        boolean claimsSupport = (VERDICT_SUPPORTED.matcher(reportText).find()
                              || VERDICT_REFUTED.matcher(reportText).find())
                && !VERDICT_INSUFFICIENT.matcher(reportText).find();
        if (claimsSupport && sourceLabels < 2)
            issues.add("DEFECT[verdict-inflation]: a strong verdict (SUPPORTED/REFUTED) with <2 cited sources — "
                     + "verdict should be INSUFFICIENT EVIDENCE.");

        // DEFECT[verdict-direction-weak]: verdict is INCONCLUSIVE but the Supporting Evidence section
        // contains validated citations while the Contradicting Evidence has 0 citation IDs —
        // the INCONCLUSIVE label overstates uncertainty (one-sided evidence).
        // ceIds==0 is the sole gate; contradictingEmpty (textual "None found") is NOT excluded
        // because an empty CE section with 0 IDs is the primary trigger case.
        if (VERDICT_INCONCLUSIVE.matcher(reportText).find()) {
            // Weak check: if supporting evidence has many more citations than contradicting, flag it
            int seIdx2 = lower.indexOf("supporting evidence");
            if (seIdx2 >= 0 && ceIdx >= 0) {
                int seEnd2 = lower.indexOf("\n#", seIdx2 + 1);
                if (seEnd2 < 0 || seEnd2 > ceIdx) seEnd2 = ceIdx;
                String seWindow2 = reportText.substring(seIdx2, seEnd2);
                int ceEnd2 = lower.indexOf("\n#", ceIdx + 1);
                String ceWindow2 = reportText.substring(ceIdx, ceEnd2 > ceIdx ? ceEnd2 : Math.min(ceIdx + 2000, reportText.length()));
                long seIds = OPENALEX_LABEL.matcher(seWindow2).results().count()
                           + PUBMED_LABEL.matcher(seWindow2).results().count();
                long ceIds = OPENALEX_LABEL.matcher(ceWindow2).results().count()
                           + PUBMED_LABEL.matcher(ceWindow2).results().count();
                if (seIds >= 2 && ceIds == 0) {
                    issues.add("DEFECT[verdict-direction-weak]: verdict is INCONCLUSIVE but Supporting "
                            + "Evidence has " + seIds + " citation(s) while Contradicting Evidence has 0. "
                            + "An INCONCLUSIVE verdict overstates uncertainty when the evidence is "
                            + "one-sided; consider SUPPORTED with caveats. scoreVerdict penalised.");
                    scoreVerdict = Math.max(0, scoreVerdict - 2.0);
                }
            }
        }

        // Over-specific single-query search (no decomposition)
        if (papersIngested == 0 && subagentCount > 0)
            issues.add("DEFECT[zero-yield-search]: sub-agents ran but ingested 0 papers — likely an "
                     + "over-specific single query. Fix: decompose the hypothesis into sub-concepts.");

        // Context overflow risk: if total tool calls is very high, context may be near limit
        if (totalToolCalls > 100) {
            issues.add("DEFECT[context-overflow-risk]: " + totalToolCalls + " total tool calls — "
                     + "session is at risk of exceeding context window. "
                     + "Fix: add turn-budget awareness (compaction or summarisation) to ConversationEngine.");
            scoreEfficiency = Math.max(0, scoreEfficiency - 1.0);
        }

        // overall is computed after stale-report zeroing below
        double overall;

        // DEFECT[spurious-citation-valid]: citationValidationPassed=true with 0 papers ingested
        // — there is nothing to validate. Force false to remove the misleading green-check.
        boolean citationValidationPassed = hasValidation && papersIngested > 0;

        // DEFECT[all-citations-skipped]: citation_validate ran but reported 'SKIPPED' for every entry
        // (e.g. 'no recognizable PMID or arXiv ID in label'). Count 'skip' occurrences in the
        // validation block vs 'confirmed'/'valid'; if skips dominate, validation checked nothing.
        if (hasValidation && citationValidationPassed) {
            // Extract the citation validation block (up to 2000 chars after the header)
            int cvIdx = lower.indexOf("citation validation");
            if (cvIdx >= 0) {
                String cvBlock = lower.substring(cvIdx, Math.min(cvIdx + 2000, lower.length()));
                long skipCount      = countOccurrences(cvBlock, "skip");
                long confirmedCount = countOccurrences(cvBlock, "confirmed") + countOccurrences(cvBlock, "valid");
                if (skipCount > confirmedCount && skipCount >= 2) {
                    citationValidationPassed = false;
                    issues.add("DEFECT[all-citations-skipped]: citation_validation ran but every entry was SKIPPED "
                            + "(e.g. 'no recognizable PMID or arXiv ID in label') — validation checked nothing "
                            + "(skipCount=" + skipCount + ", confirmedCount=" + confirmedCount + "). "
                            + "citationValidationPassed forced to false. "
                            + "Fix: citation_validate must handle DOI-format labels; "
                            + "add a PMID-lookup fallback for pubmed:NNN labels.");
                }
            }
        }

        // DEFECT[malformed-citation-id-namespace]: citation_validate was called with a DOI in the
        // pubmed: namespace (e.g. pubmed:10.1056/nejmoa...) — the tool will SKIP such entries
        // because it expects a numeric PMID. Detectable by scanning citation_validate call results
        // for 'skipped' combined with a 'pubmed:10.' pattern.
        {
            double malformedNsPenalty = 0.0;
            for (MetricsCollector.ToolCallRecord tcr : metrics.toolCalls()) {
                if ("citation_validate".equals(tcr.toolName())) {
                    String preview = tcr.resultPreview() != null ? tcr.resultPreview().toLowerCase() : "";
                    if (preview.contains("skipped") && preview.contains("pubmed:10.")) {
                        malformedNsPenalty = Math.min(2.0, malformedNsPenalty + 1.0);
                    }
                }
            }
            if (malformedNsPenalty > 0) {
                citationValidationPassed = false;
                issues.add("DEFECT[malformed-citation-id-namespace]: citation_validate was called with DOI-format "
                        + "IDs in the pubmed: namespace (e.g. pubmed:10.xxxx/...) — the validator SKIPPED these "
                        + "because it expects a numeric PMID. citationValidationPassed forced to false. "
                        + "scoreCitations penalised -" + String.format("%.1f", malformedNsPenalty) + " (capped at -2.0). "
                        + "Fix: use openalex: or doi: namespace for DOI-format identifiers, not pubmed:.");
                scoreCitations = Math.max(0, scoreCitations - malformedNsPenalty);
            }
        }

        // DEFECT[citation-validation-body-mismatch]: the IDs that appear in the body evidence
        // sections (Supporting/Contradicting) differ from the IDs cited in the References/Validation
        // block. When body IDs and references-section IDs are completely disjoint (and both non-empty),
        // the citations were not validated for the actual evidence claimed.
        if (citationValidationPassed) {
            // Collect IDs from body sections (supporting evidence + contradicting evidence windows)
            java.util.Set<String> bodyIds = new java.util.HashSet<>();
            int seIdxBm = lower.indexOf("supporting evidence");
            int ceIdxBm = lower.indexOf("contradicting evidence");
            if (seIdxBm >= 0) {
                int seEndBm = lower.indexOf("\n#", seIdxBm + 1);
                if (seEndBm < 0) seEndBm = Math.min(seIdxBm + 3000, lower.length());
                String seWindowBm = reportText.substring(seIdxBm, seEndBm);
                Matcher bmSe = INLINE_SOURCE.matcher(seWindowBm);
                while (bmSe.find()) bodyIds.add(bmSe.group(1).toLowerCase());
            }
            if (ceIdxBm >= 0) {
                int ceEndBm = lower.indexOf("\n#", ceIdxBm + 1);
                if (ceEndBm < 0) ceEndBm = Math.min(ceIdxBm + 2000, lower.length());
                String ceWindowBm = reportText.substring(ceIdxBm, ceEndBm);
                Matcher bmCe = INLINE_SOURCE.matcher(ceWindowBm);
                while (bmCe.find()) bodyIds.add(bmCe.group(1).toLowerCase());
            }
            // Collect IDs from the References section
            java.util.Set<String> refIds = new java.util.HashSet<>();
            int refIdx = lower.indexOf("references");
            if (refIdx >= 0) {
                String refWindow = reportText.substring(refIdx, Math.min(refIdx + 3000, reportText.length()));
                Matcher bmRef = INLINE_SOURCE.matcher(refWindow);
                while (bmRef.find()) refIds.add(bmRef.group(1).toLowerCase());
            }
            // Normalize: strip slug suffix — compare only database:id prefix (first two colon-parts).
            // Body may have "w2118104542" and References "w2118104542:title-slug" — both should match.
            // Also strip "openalex:" prefix if body only captured the W-number.
            java.util.Set<String> bodyIdsNorm = bodyIds.stream()
                    .map(id -> id.replaceFirst(":.*$", ""))  // drop slug
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> refIdsNorm = refIds.stream()
                    .map(id -> id.replaceFirst(":.*$", ""))  // drop slug
                    .collect(java.util.stream.Collectors.toSet());
            // Fire when both sets are non-empty and completely disjoint
            if (!bodyIdsNorm.isEmpty() && !refIdsNorm.isEmpty()) {
                java.util.Set<String> overlap = new java.util.HashSet<>(bodyIdsNorm);
                overlap.retainAll(refIdsNorm);
                if (overlap.isEmpty()) {
                    citationValidationPassed = false;
                    issues.add("DEFECT[citation-validation-body-mismatch]: the normalized citation IDs in the body "
                            + "evidence sections (" + bodyIdsNorm + ") are completely disjoint from those in "
                            + "the References section (" + refIdsNorm + "). The validated citations do not match "
                            + "the evidence cited in the body — validated IDs provide no quality assurance "
                            + "for the actual claims. citationValidationPassed forced to false. "
                            + "scoreCitations penalised -2.0. "
                            + "Fix: the evidence-appraiser must cite the same IDs in the body and in References; "
                            + "use identical format (same prefix and slug) throughout.");
                    scoreCitations = Math.max(0, scoreCitations - 2.0);
                }
            }
        }

        // DEFECT[vacuous-citation-validation]: the report contains a citation_validation block
        // but zero citation IDs — validation on an empty set trivially passes and provides no
        // quality assurance. Flag this so it doesn't register as a positive signal.
        if (hasValidation && noCitationIds) {
            issues.add("DEFECT[vacuous-citation-validation]: citation_validation passed but the report "
                    + "contains zero PMIDs, DOIs, or OpenAlex IDs — validation of an empty reference list "
                    + "trivially succeeds and provides no quality assurance. "
                    + "citationValidationPassed is forced to false.");
        }

        // DEFECT[gate-resolution-strip-all]: the gate-loop was resolved by stripping ALL citations
        // rather than fixing individual invalid IDs — a worse outcome than the original collapse.
        // Detect: many report_write retries followed by a report with zero citation IDs.
        if (reportWriteCalls >= 4 && noCitationIds && papersIngested > 0) {
            issues.add("DEFECT[gate-resolution-strip-all]: " + reportWriteCalls
                    + " report_write retries led to a report with 0 citation IDs, despite "
                    + papersIngested + " paper(s) having been ingested. "
                    + "The agent resolved the citation gate by removing ALL citations — "
                    + "a worse outcome than the original citation-collapse. "
                    + "Fix: gate rejection messages must enumerate the specific IDs to remove, "
                    + "not trigger a wholesale citation purge.");
            scoreCitations = 0.0;
            scoreEvidence = Math.max(0, scoreEvidence - 2.0);
        }

        // ── Stale-report score zeroing ───────────────────────────────────────────
        // When the report does not correspond to the current hypothesis, all content
        // scores are meaningless — they describe an unrelated prior run's report.
        // Force them to 0 so the overall score reflects the true failure mode.
        if (isStaleReport) {
            scoreStructure = 0;
            scoreEvidence  = 0;
            scoreCitations = 0;
            scoreBalance   = 0;
            scoreVerdict   = 0;
            // Keep scoreEfficiency as-is: it reflects real pipeline cost even if the
            // wrong report was scored; set to 0 only when report file was blank (above).
        }

        overall = scoreStructure * W_STRUCTURE + scoreEvidence * W_EVIDENCE
                + scoreCitations * W_CITATIONS + scoreBalance * W_BALANCE
                + scoreVerdict * W_VERDICT + scoreEfficiency * W_EFFICIENCY;

        return new ScoreResult(scoreStructure, scoreEvidence, scoreCitations,
                scoreBalance, scoreVerdict, scoreEfficiency, overall,
                issues, citationValidationPassed);
    }

    /** Convenience overload — no hypothesis cross-check. */
    public static ScoreResult compute(String reportText, MetricsCollector metrics,
                                       Session session, long wallMs) {
        return compute(reportText, metrics, session, wallMs, null);
    }

    /** Render a human-readable quality report for console output. */
    public static String render(ScoreResult s, MetricsCollector metrics, Session session, long wallMs) {
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

    /** Counts non-overlapping occurrences of a plain substring (case-sensitive after caller lowercases). */
    private static long countOccurrences(String text, String sub) {
        if (text == null || sub == null || sub.isEmpty()) return 0;
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) { count++; idx += sub.length(); }
        return count;
    }

    /** Returns true when the named evidence section exists but contains only 'None found' variants. */
    private static boolean isEvidenceSectionEmpty(String lower, String sectionKeyword) {
        int idx = lower.indexOf(sectionKeyword);
        if (idx < 0) return false; // section absent, not "empty"
        int end = lower.indexOf("\n#", idx + 1);
        String window = lower.substring(idx, end > idx ? end : Math.min(idx + 500, lower.length()));
        return window.contains("none found")
                || window.contains("no studies found")
                || window.contains("no relevant")
                || window.contains("no evidence found");
    }
}
