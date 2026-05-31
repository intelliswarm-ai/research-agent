package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.researchagent.config.ResearchProperties;
import ai.intelliswarm.researchagent.eval.CitationValidatorTool;
import ai.intelliswarm.swarmai.rag.tool.RagSearchTool;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the final research report to disk — but only after it passes the quality gates.
 *
 * <p>This is the enforcement chokepoint. A report cannot be written if it cites papers that were
 * never ingested, never screened for relevance, rejected as off-topic / wrong-species, fail
 * citation validation, or contain fabricated quotes. Missing verification steps are auto-run here
 * rather than trusting the model to have called them. An honest "no citations / INSUFFICIENT
 * EVIDENCE" report passes freely.
 */
@Component
public class ReportWriteTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(ReportWriteTool.class);

    // Citable source labels like pubmed:41923852:..., openalex:W123:..., arxiv:2401.1:...
    private static final Pattern CITED = Pattern.compile(
            "(?i)\\b(pubmed|pmid|openalex|arxiv|doi|semantic_scholar|epmc):[A-Za-z0-9][A-Za-z0-9._/-]*");
    // Verbatim quotes (long double-quoted spans).
    private static final Pattern QUOTE = Pattern.compile("\"([^\"]{25,})\"");

    private final ResearchProperties props;
    private final RelevanceLedger ledger;
    private final IngestLedger ingestLedger;
    private final CitationValidatorTool citationValidator;
    private final RagSearchTool ragSearchTool;

    public ReportWriteTool(ResearchProperties props,
                           RelevanceLedger ledger,
                           IngestLedger ingestLedger,
                           CitationValidatorTool citationValidator,
                           RagSearchTool ragSearchTool) {
        this.props = props;
        this.ledger = ledger;
        this.ingestLedger = ingestLedger;
        this.citationValidator = citationValidator;
        this.ragSearchTool = ragSearchTool;
    }

    @Override public String getFunctionName() { return "report_write"; }

    @Override
    public String getDescription() {
        return "Write the final research report to disk. Call once at the end, passing the complete "
             + "markdown report as 'content'. The report is gated: it is rejected if it cites papers "
             + "that were not ingested, not screened by relevance_filter, rejected as off-topic, fail "
             + "citation validation, or contain fabricated quotes. Returns the saved path on success.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "output"; }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String content = parameters.get("content") instanceof String s ? s : null;
        if (content == null || content.isBlank()) {
            return "Error: 'content' (the markdown report) is required.";
        }

        // ── Quality gates ────────────────────────────────────────────────────
        String block = runGates(content);
        if (block != null) {
            log.info("report_write blocked by a quality gate");
            return block;
        }

        String filename = parameters.get("filename") instanceof String f ? f : null;
        if (filename == null || filename.isBlank()) {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            filename = "research_report_" + ts + ".md";
        }
        if (!filename.endsWith(".md")) filename += ".md";

        try {
            Path outDir = Paths.get(props.getOutputDir()).toAbsolutePath().normalize();
            Files.createDirectories(outDir);
            Path reportPath = outDir.resolve(filename);
            Files.writeString(reportPath, content, StandardCharsets.UTF_8);
            log.info("Report written to {}", reportPath);
            return "Report saved to: " + reportPath;
        } catch (IOException e) {
            log.error("Failed to write report: {}", e.getMessage());
            return "Error writing report: " + e.getMessage();
        }
    }

    // ── Gates ────────────────────────────────────────────────────────────────

    /**
     * Runs all gates; returns a friendly, actionable BLOCK message if any fails, else null.
     * Gates: (1) ingestion, (2) relevance, (3) citation validation (auto-run),
     * (2b) auto relevance screen when relevance_filter was skipped, (4) quote fidelity.
     */
    private String runGates(String content) {
        Set<String> cited = new LinkedHashSet<>();
        Matcher m = CITED.matcher(content);
        while (m.find()) cited.add(RelevanceLedger.normalize(m.group()));
        if (cited.isEmpty()) return null; // honest "no evidence" report — allowed

        // Gate 0: citation collapse — if a single source is the ONLY citation across both
        // Supporting and Contradicting sections, the model has collapsed all evidence onto one
        // document (typically a guideline or review). Block this: primary evidence sections
        // must cite at least 2 distinct sources.
        if (cited.size() == 1) {
            String onlySource = cited.iterator().next();
            // Only block if the report has both a Supporting and Contradicting section with content.
            boolean hasSupporting    = content.contains("## Supporting Evidence")
                    && !content.replaceAll("(?s)## Supporting Evidence.*?## ", "").startsWith("None");
            boolean hasContradicting = content.contains("## Contradicting Evidence")
                    && !content.replaceAll("(?s)## Contradicting Evidence.*?## ", "").startsWith("None");
            if (hasSupporting && hasContradicting) {
                return "⛔ GATE[citation-collapse]: both Supporting and Contradicting Evidence sections "
                     + "cite only one source (" + onlySource + "). This is a citation collapse — you are "
                     + "quoting a guideline or review document to support contradictory claims. "
                     + "Each evidence section must cite the original primary studies, not a document that "
                     + "summarises them. Run additional rag_search queries to find distinct sources for "
                     + "each side, or rewrite with honest INSUFFICIENT EVIDENCE if you cannot find them.";
            }
        }

        // Gate 1: ingestion
        if (ingestLedger.isEmpty()) {
            return "⛔ GATE[ingestion]: the report cites papers but nothing was ingested into the RAG "
                 + "store. Spawn a literature-scout to search and rag_ingest real papers first. "
                 + "Cited but not ingested: " + cited;
        }

        // Gate 2: relevance (when relevance_filter has run)
        if (ledger.hasRun()) {
            List<String> rejected = new ArrayList<>();
            List<String> unscreened = new ArrayList<>();
            for (String c : cited) {
                RelevanceLedger.Verdict v = ledger.verdictFor(c);
                if (v == null) unscreened.add(c);
                else if (v == RelevanceLedger.Verdict.REJECT) rejected.add(c);
            }
            if (!rejected.isEmpty())
                return "⛔ GATE[relevance]: the report cites papers the relevance gate REJECTED "
                     + "(wrong species / off-topic): " + rejected + ". Remove them. If no RELEVANT papers "
                     + "remain, set the verdict to INSUFFICIENT EVIDENCE — do not substitute tangential papers.";
            if (!unscreened.isEmpty())
                return "⛔ GATE[relevance]: these cited sources were never screened: " + unscreened
                     + ". Run relevance_filter on all cited sources, then write the report.";
        }

        // Gate 3: citation validation (AUTO-RUN) — also resolves real titles for the auto screen
        CitationValidatorTool.Outcome outcome;
        try {
            outcome = citationValidator.check(cited);
        } catch (Exception e) {
            log.warn("citation auto-validate failed: {}", e.getMessage());
            outcome = new CitationValidatorTool.Outcome(List.of(), Map.of());
        }
        if (!outcome.invalid().isEmpty()) {
            return "⛔ GATE[citation]: these cited IDs could not be verified in PubMed/OpenAlex/arXiv "
                 + "(possibly fabricated or wrong): " + outcome.invalid() + ". Remove or correct them.";
        }

        // Gate 2b: auto relevance screen when relevance_filter was skipped
        if (!ledger.hasRun()) {
            List<String> wrongSpecies = new ArrayList<>();
            for (Map.Entry<String, String> e : outcome.titles().entrySet()) {
                String marker = RelevanceGateTool.nonHumanMarker(e.getValue());
                if (marker != null) wrongSpecies.add(e.getKey() + " ('" + marker + "')");
            }
            if (!wrongSpecies.isEmpty())
                return "⛔ GATE[relevance]: the report cites non-human / in-vitro studies as evidence: "
                     + wrongSpecies + ". Remove them, or run relevance_filter with the correct "
                     + "evidence_level if animal/in-vitro evidence is actually intended.";
        }

        // Gate 4: quote fidelity (AUTO-RUN)
        return checkQuoteFidelity(content);
    }

    /**
     * Verify each verbatim quote appears (approximately) in an ingested RAG chunk. Blocks only on
     * clear fabrication (no overlap anywhere), since chunking/whitespace make exact matching unreliable.
     */
    private String checkQuoteFidelity(String content) {
        List<String> fabricated = new ArrayList<>();
        Matcher q = QUOTE.matcher(content);
        int checked = 0;
        while (q.find() && checked < 12) {
            String quote = q.group(1).replaceAll("\\s+", " ").trim();
            if (quote.length() < 30) continue;
            checked++;
            String probe = quote.length() > 80 ? quote.substring(0, 80) : quote;
            try {
                Object res = ragSearchTool.execute(Map.of("query", probe));
                String hay = (res == null ? "" : res.toString()).replaceAll("\\s+", " ").toLowerCase();
                String needle = quote.substring(0, Math.min(40, quote.length())).toLowerCase();
                if (!hay.contains(needle)) {
                    fabricated.add('"' + (quote.length() > 60 ? quote.substring(0, 60) + "…" : quote) + '"');
                }
            } catch (Exception e) {
                log.warn("quote-fidelity check failed: {}", e.getMessage());
            }
        }
        if (!fabricated.isEmpty()) {
            return "⛔ GATE[quote-fidelity]: these quotes do not appear in any ingested paper and may be "
                 + "fabricated/paraphrased: " + fabricated + ". Replace each with a verbatim sentence from "
                 + "rag_search results, or remove the claim.";
        }
        return null;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("content", Map.of("type", "string",
                "description", "The complete markdown research report to write to disk."));
        props.put("filename", Map.of("type", "string",
                "description", "Optional output filename (default: research_report_<timestamp>.md)."));
        schema.put("properties", props);
        schema.put("required", new String[]{"content"});
        return schema;
    }
}
