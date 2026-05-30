package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic relevance screen run BEFORE evidence is trusted.
 *
 * <p>Catches the classic failure where keyword search returns a paper that shares words with the
 * hypothesis but is the wrong species / wrong study type (e.g. "potato chips → visceral fat in
 * LAMBS" surfacing for a human-metabolism hypothesis). For each candidate paper it returns:
 * <ul>
 *   <li><b>REJECT</b> — wrong species or non-clinical model when human/clinical evidence was requested</li>
 *   <li><b>TANGENTIAL</b> — on-species but weak topical overlap with the hypothesis</li>
 *   <li><b>RELEVANT</b> — right population and meaningful topical overlap</li>
 * </ul>
 * The orchestrator must drop REJECTED papers and, if nothing is RELEVANT, report INSUFFICIENT
 * EVIDENCE rather than citing tangential matches.
 */
@Component
public class RelevanceGateTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(RelevanceGateTool.class);

    private final RelevanceLedger ledger;

    public RelevanceGateTool(RelevanceLedger ledger) {
        this.ledger = ledger;
    }

    // Markers that indicate a non-human / non-clinical study.
    private static final Set<String> ANIMAL_INVITRO = Set.of(
            "lamb", "lambs", "sheep", "ovine", "bovine", "cattle", "calf", "calves",
            "swine", "pig", "pigs", "porcine", "poultry", "broiler", "chicken",
            "mouse", "mice", "murine", "rat", "rats", "rodent", "rodents",
            "zebrafish", "drosophila", "c. elegans", "canine", "feline",
            "in vitro", "in-vitro", "myotube", "myotubes", "cell line", "cell-line",
            "cultured cells", "carcass", "finishing diet", "finishing diets",
            "feed ingredient", "feedingredient", "feeding ingredient");

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "are", "with", "how", "can", "be", "of", "in", "to", "a", "an",
            "is", "on", "if", "as", "by", "that", "this", "from", "context", "role", "focusing",
            "evaluated", "through", "evidence", "inform", "paper", "writing", "study", "studies",
            "clinical", "outcomes", "human", "humans", "patients", "effect", "effects", "associated");

    @Override public String getFunctionName() { return "relevance_filter"; }

    @Override
    public String getDescription() {
        return "Screen candidate papers for relevance to the hypothesis BEFORE trusting them as "
             + "evidence. Pass the hypothesis, the desired evidence_level (e.g. 'human clinical "
             + "studies'), and a list of papers ({source, title}). Returns RELEVANT / TANGENTIAL / "
             + "REJECT per paper with reasons. REJECTED papers (wrong species/model) MUST NOT be "
             + "cited as evidence. If none are RELEVANT, report INSUFFICIENT EVIDENCE.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "evaluation"; }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> parameters) {
        String hypothesis   = str(parameters.get("hypothesis"));
        String evidenceLvl  = str(parameters.get("evidence_level"));
        Object papersParam  = parameters.get("papers");

        if (hypothesis == null || hypothesis.isBlank()) return "Error: 'hypothesis' is required.";
        if (!(papersParam instanceof List<?> rawList) || rawList.isEmpty())
            return "Error: 'papers' must be a non-empty array of {source, title} objects.";

        boolean requireHuman = evidenceLvl == null
                || evidenceLvl.toLowerCase().contains("human")
                || evidenceLvl.toLowerCase().contains("clinical");

        Set<String> hypoTerms = contentWords(hypothesis);

        List<String> relevant = new ArrayList<>();
        StringBuilder report = new StringBuilder("## Relevance Screen\n\n");
        report.append("Hypothesis terms: ").append(hypoTerms).append("\n")
              .append("Require human/clinical: ").append(requireHuman).append("\n\n");

        int rejected = 0, tangential = 0;
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> paper = (Map<String, Object>) m;
            String source = str(paper.get("source"));
            String title  = str(paper.get("title"));
            String hay    = ((title == null ? "" : title) + " " + (source == null ? "" : source)).toLowerCase();

            String verdict, reason;
            String animalHit = firstMatch(hay, ANIMAL_INVITRO);
            if (requireHuman && animalHit != null) {
                verdict = "REJECT";
                reason  = "non-human / non-clinical model detected ('" + animalHit + "') but human "
                        + "clinical evidence was requested";
                rejected++;
            } else {
                long overlap = contentWords(title == null ? "" : title).stream()
                        .filter(hypoTerms::contains).count();
                if (overlap >= 2) {
                    verdict = "RELEVANT";
                    reason  = overlap + " key hypothesis terms present in title";
                    relevant.add(source);
                } else if (overlap == 1) {
                    verdict = "TANGENTIAL";
                    reason  = "only 1 hypothesis term in title — weak topical match";
                    tangential++;
                } else {
                    verdict = "TANGENTIAL";
                    reason  = "no strong hypothesis term in title — likely off-topic keyword hit";
                    tangential++;
                }
            }
            // Record into the ledger so report_write can enforce the gate.
            ledger.record(source, RelevanceLedger.Verdict.valueOf(verdict));

            report.append("- **").append(verdict).append("** `").append(source).append("`\n")
                  .append("  - title: ").append(title).append("\n")
                  .append("  - reason: ").append(reason).append("\n");
        }

        report.append("\n**Summary:** ").append(relevant.size()).append(" RELEVANT, ")
              .append(tangential).append(" TANGENTIAL, ").append(rejected).append(" REJECT\n");
        if (relevant.isEmpty()) {
            report.append("\n> ⚠️ NO RELEVANT papers. Report **INSUFFICIENT EVIDENCE** — do not cite ")
                  .append("tangential or rejected papers as supporting evidence.\n");
        } else {
            report.append("\n> Cite ONLY these as evidence: ").append(relevant).append("\n");
        }
        log.info("relevance_filter: {} relevant, {} tangential, {} rejected",
                relevant.size(), tangential, rejected);
        return report.toString();
    }

    private static Set<String> contentWords(String text) {
        if (text == null) return Set.of();
        Set<String> words = new HashSet<>();
        for (String w : text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (w.length() >= 4 && !STOPWORDS.contains(w)) words.add(w);
        }
        return words;
    }

    private static String firstMatch(String haystack, Set<String> needles) {
        for (String n : needles) if (haystack.contains(n)) return n;
        return null;
    }

    /**
     * Returns the matched animal/in-vitro marker if the text looks like a non-human / non-clinical
     * study, else null. Exposed so {@link ReportWriteTool} can auto-screen cited titles even when the
     * model skipped {@code relevance_filter}.
     */
    public static String nonHumanMarker(String text) {
        return text == null ? null : firstMatch(text.toLowerCase(), ANIMAL_INVITRO);
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hypothesis", Map.of("type", "string", "description", "The research hypothesis."));
        props.put("evidence_level", Map.of("type", "string",
                "description", "Desired evidence level, e.g. 'human clinical studies', 'animal models', 'any'."));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", Map.of(
                "source", Map.of("type", "string"),
                "title", Map.of("type", "string")));
        props.put("papers", Map.of("type", "array", "items", item,
                "description", "Candidate papers to screen, each {source, title}."));
        schema.put("properties", props);
        schema.put("required", new String[]{"hypothesis", "papers"});
        return schema;
    }
}
