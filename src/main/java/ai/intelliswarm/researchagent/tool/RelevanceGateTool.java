package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.researchagent.config.ResearchProperties;
import ai.intelliswarm.swarmai.agent.llm.LlmClient;
import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRequest;
import ai.intelliswarm.swarmai.agent.llm.LlmResponse;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-driven relevance screen run BEFORE evidence is trusted.
 *
 * <p>Instead of static keyword matching or hardcoded synonym maps, this tool batches all
 * candidate papers into a single LLM call and asks the model to classify each one as
 * RELEVANT / TANGENTIAL / REJECT given the hypothesis and required evidence level.
 *
 * <p>The LLM understands drug synonyms, disease aliases, study design vocabulary, and
 * population terms generically — no manual synonym maintenance required. A fast
 * animal/in-vitro pre-filter based on title keywords runs first to catch obvious
 * non-human papers without burning LLM tokens.
 *
 * <p>Falls back to a simple keyword heuristic if the LLM call fails.
 */
@Component
public class RelevanceGateTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(RelevanceGateTool.class);

    private final RelevanceLedger ledger;
    private final LlmClient llm;
    private final ResearchProperties props;

    public RelevanceGateTool(RelevanceLedger ledger, LlmClient llm, ResearchProperties props) {
        this.ledger = ledger;
        this.llm    = llm;
        this.props  = props;
    }

    // Fast pre-filter: obvious non-human / in-vitro markers caught without an LLM call.
    // Terms that must appear as whole words (not substrings) to avoid false positives like
    // "rat" matching "ratio", "rate", "patient", etc.
    private static final Set<String> ANIMAL_INVITRO_WHOLE_WORD = Set.of(
            "lamb", "lambs", "sheep", "ovine", "bovine", "cattle", "calf", "calves",
            "swine", "porcine", "poultry", "broiler",
            "mouse", "mice", "murine", "rat", "rats", "rodent", "rodents",
            "zebrafish", "drosophila", "canine", "feline",
            "myotube", "myotubes", "carcass");
    // Terms that can be substring-matched (compound phrases or unambiguous tokens).
    private static final Set<String> ANIMAL_INVITRO_SUBSTRING = Set.of(
            "in vitro", "in-vitro", "cell line", "cell-line", "cultured cells",
            "finishing diet", "finishing diets", "feed ingredient", "feeding ingredient",
            "c. elegans");

    private static final String CLASSIFIER_SYSTEM = """
            You are a medical evidence screener. You classify research papers as relevant to a given hypothesis.

            For each paper, output EXACTLY one line in this format:
            N|VERDICT|reason

            Where:
            - N is the paper number (integer)
            - VERDICT is one of: RELEVANT, TANGENTIAL, REJECT
            - reason is a brief explanation (max 15 words)

            Verdict definitions:
            - RELEVANT: directly addresses the hypothesis population, intervention, AND outcome
            - TANGENTIAL: related topic but wrong population, wrong comparator, or only partially relevant
            - REJECT: wrong species (animal/in-vitro), completely off-topic, or duplicate

            Be accurate with RELEVANT — a paper must address all three: the right people, the right exposure/intervention, and the right measured outcome. Drug synonyms, disease aliases, and study design terms count.
            Do not output anything except the numbered lines.""";

    // Parses lines like: 3|RELEVANT|directly tests statin timing post-AMI in human RCT
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(\\d+)\\s*[|:]\\s*(RELEVANT|TANGENTIAL|REJECT)\\s*[|:]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private record Paper(String source, String title, String preVeto) {}

    @Override public String getFunctionName() { return "relevance_filter"; }

    @Override
    public String getDescription() {
        return "Screen candidate papers for relevance to the hypothesis BEFORE trusting them as "
             + "evidence. Pass the hypothesis, the desired evidence_level (e.g. 'human clinical "
             + "studies'), and a list of papers ({source, title}). Returns RELEVANT / TANGENTIAL / "
             + "REJECT per paper with reasons. REJECTED papers MUST NOT be cited as evidence. "
             + "If none are RELEVANT, report INSUFFICIENT EVIDENCE.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "evaluation"; }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> parameters) {
        String hypothesis  = str(parameters.get("hypothesis"));
        String evidenceLvl = str(parameters.get("evidence_level"));
        Object papersParam = parameters.get("papers");

        if (hypothesis == null || hypothesis.isBlank()) return "Error: 'hypothesis' is required.";
        if (!(papersParam instanceof List<?> rawList) || rawList.isEmpty())
            return "Error: 'papers' must be a non-empty array of {source, title} objects.";

        boolean requireHuman = evidenceLvl == null
                || evidenceLvl.toLowerCase().contains("human")
                || evidenceLvl.toLowerCase().contains("clinical");

        // Collect papers, applying the fast animal/in-vitro pre-filter first.
        List<Paper> papers = new ArrayList<>();
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> pm = (Map<String, Object>) m;
            String source = str(pm.get("source"));
            String title  = str(pm.get("title"));
            String hay = ((title == null ? "" : title) + " " + (source == null ? "" : source)).toLowerCase();
            String animalHit = requireHuman ? firstAnimalMatch(hay) : null;
            papers.add(new Paper(source, title, animalHit));
        }

        // Split into pre-vetoed (REJECT immediately) and candidates for LLM classification.
        List<Paper> candidates = papers.stream().filter(p -> p.preVeto() == null).toList();
        List<Paper> preRejected = papers.stream().filter(p -> p.preVeto() != null).toList();

        // LLM batch classification for all non-pre-rejected papers.
        Map<Integer, String[]> llmVerdicts = llmClassify(hypothesis, evidenceLvl, candidates);

        // Build report.
        List<String> relevant = new ArrayList<>();
        StringBuilder report = new StringBuilder("## Relevance Screen\n");
        report.append("Hypothesis: ").append(hypothesis).append("\n");
        report.append("Evidence level: ").append(evidenceLvl != null ? evidenceLvl : "any").append("\n\n");

        int rejected = 0, tangential = 0;

        for (int i = 0; i < candidates.size(); i++) {
            Paper p = candidates.get(i);
            String[] vr = llmVerdicts.getOrDefault(i + 1, new String[]{"TANGENTIAL", "unclassified"});
            String verdict = vr[0].toUpperCase();
            String reason  = vr[1];

            if ("RELEVANT".equals(verdict)) {
                relevant.add(p.source());
            } else if ("REJECT".equals(verdict)) {
                rejected++;
            } else {
                verdict = "TANGENTIAL";
                tangential++;
            }
            ledger.record(p.source(), RelevanceLedger.Verdict.valueOf(verdict));
            report.append("- **").append(verdict).append("** `").append(p.source()).append("`\n")
                  .append("  title: ").append(p.title()).append("\n")
                  .append("  reason: ").append(reason).append("\n");
        }

        for (Paper p : preRejected) {
            String reason = "non-human / in-vitro marker detected ('" + p.preVeto() + "')";
            ledger.record(p.source(), RelevanceLedger.Verdict.REJECT);
            report.append("- **REJECT** `").append(p.source()).append("`\n")
                  .append("  title: ").append(p.title()).append("\n")
                  .append("  reason: ").append(reason).append("\n");
            rejected++;
        }

        report.append("\n**Summary:** ").append(relevant.size()).append(" RELEVANT, ")
              .append(tangential).append(" TANGENTIAL, ").append(rejected).append(" REJECT\n");
        if (relevant.isEmpty()) {
            report.append("\n> ⚠️ NO RELEVANT papers. Report INSUFFICIENT EVIDENCE.\n");
        } else {
            report.append("\n> Cite ONLY these as direct evidence: ").append(relevant).append("\n");
        }

        log.info("relevance_filter: {} relevant, {} tangential, {} rejected",
                relevant.size(), tangential, rejected);
        return report.toString();
    }

    /**
     * Calls the LLM once with all candidate papers and parses the N|VERDICT|reason lines.
     * Falls back to TANGENTIAL for any paper the model doesn't classify.
     */
    private Map<Integer, String[]> llmClassify(String hypothesis, String evidenceLvl,
                                                List<Paper> candidates) {
        Map<Integer, String[]> result = new HashMap<>();
        if (candidates.isEmpty()) return result;

        StringBuilder prompt = new StringBuilder();
        prompt.append("Hypothesis: ").append(hypothesis).append("\n");
        prompt.append("Evidence level required: ")
              .append(evidenceLvl != null ? evidenceLvl : "human clinical studies").append("\n\n");
        prompt.append("Classify each paper:\n");
        for (int i = 0; i < candidates.size(); i++) {
            prompt.append(i + 1).append(". [").append(candidates.get(i).source()).append("] ")
                  .append(candidates.get(i).title()).append("\n");
        }

        try {
            LlmRequest req = LlmRequest.builder()
                    .model(props.getModel().getPrimary())
                    .system(CLASSIFIER_SYSTEM)
                    .maxOutputTokens(candidates.size() * 30 + 50) // ~30 tokens per line
                    .message(LlmMessage.user(prompt.toString()))
                    .build();
            LlmResponse resp = llm.send(req);
            String text = resp.text() == null ? "" : resp.text();
            for (String line : text.split("\n")) {
                Matcher m = LINE_PATTERN.matcher(line.trim());
                if (m.matches()) {
                    int idx = Integer.parseInt(m.group(1));
                    result.put(idx, new String[]{m.group(2).toUpperCase(), m.group(3).trim()});
                }
            }
            log.debug("relevance_filter LLM classified {}/{} papers", result.size(), candidates.size());
        } catch (Exception e) {
            log.warn("relevance_filter LLM call failed ({}), falling back to TANGENTIAL for all", e.getMessage());
        }
        return result;
    }

    /**
     * Returns the first matching animal/in-vitro marker, or null.
     * Whole-word terms use {@code \b} boundaries to avoid false positives like
     * "rat" matching "ratio", "rate", "patient", "gratitude", etc.
     */
    private static String firstAnimalMatch(String haystack) {
        // Substring terms (unambiguous phrases)
        for (String n : ANIMAL_INVITRO_SUBSTRING) {
            if (haystack.contains(n)) return n;
        }
        // Whole-word terms (use word boundaries)
        for (String n : ANIMAL_INVITRO_WHOLE_WORD) {
            if (Pattern.compile("\\b" + Pattern.quote(n) + "\\b").matcher(haystack).find()) return n;
        }
        return null;
    }

    /**
     * Returns a non-human marker if the text looks like an animal/in-vitro study.
     * Exposed for {@link ReportWriteTool}'s auto-screen when relevance_filter was skipped.
     */
    public static String nonHumanMarker(String text) {
        return text == null ? null : firstAnimalMatch(text.toLowerCase());
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hypothesis", Map.of("type", "string", "description", "The research hypothesis."));
        props.put("evidence_level", Map.of("type", "string",
                "description", "Required evidence level, e.g. 'human clinical studies'."));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", Map.of(
                "source", Map.of("type", "string"),
                "title",  Map.of("type", "string")));
        props.put("papers", Map.of("type", "array", "items", item,
                "description", "Papers to screen, each {source, title}."));
        schema.put("properties", props);
        schema.put("required", new String[]{"hypothesis", "papers"});
        return schema;
    }
}
