package ai.intelliswarm.researchagent.cli;

import org.jline.reader.LineReader;

import java.util.List;

/**
 * Guides the researcher through a structured intake to formalize the research hypothesis.
 *
 * <p>Interaction pattern (mirrors the UX in the design screenshots):
 * <ol>
 *   <li>Ask the medical / research field.</li>
 *   <li>Ask the specific topic or question within that field.</li>
 *   <li>Present field-specific, numbered aspect choices (e.g. "Pathology / Risk factors / …").</li>
 *   <li>Ask the level of evidence of interest (basic/animal/clinical/any).</li>
 *   <li>Ask the goal of the research (grant, paper, exploratory, teaching).</li>
 *   <li>Draft a one-sentence testable hypothesis from the answers; loop until the user accepts or edits it.</li>
 * </ol>
 *
 * <p>The accepted hypothesis + metadata become the seed user message for {@link
 * ai.intelliswarm.researchagent.agent.ConversationEngine}.
 */
public class IntakeDialog {

    private final LineReader reader;

    // Aspect choices keyed by normalized field name fragment
    private static final java.util.Map<String, List<String>> ASPECT_CHOICES = java.util.Map.ofEntries(
            java.util.Map.entry("alzheimer",  List.of("Pathology (amyloid/tau)", "Risk factors / prevention",
                    "Treatment / drug targets", "Diagnosis / biomarkers")),
            java.util.Map.entry("cancer",     List.of("Tumour biology / oncogenesis", "Immunotherapy",
                    "Early detection / screening", "Chemotherapy / resistance")),
            java.util.Map.entry("diabetes",   List.of("Insulin signalling / beta-cell biology", "Type 1 vs Type 2 mechanisms",
                    "Complications (nephropathy, retinopathy)", "Novel therapeutics")),
            java.util.Map.entry("cardiovascular", List.of("Atherosclerosis / plaque", "Heart failure mechanisms",
                    "Arrhythmias", "Biomarkers / diagnostics")),
            java.util.Map.entry("parkinson",  List.of("Alpha-synuclein / Lewy bodies", "Dopaminergic pathways",
                    "Genetic risk factors", "Neuroprotective strategies")),
            java.util.Map.entry("covid",      List.of("Viral entry / replication", "Immune response / cytokine storm",
                    "Long COVID mechanisms", "Vaccine efficacy")),
            java.util.Map.entry("default",    List.of("Molecular mechanisms", "Clinical outcomes",
                    "Risk factors / epidemiology", "Therapeutic targets"))
    );

    private static final List<String> EVIDENCE_LEVELS = List.of(
            "Basic / molecular mechanisms",
            "Animal models",
            "Human clinical studies",
            "Any / no preference"
    );

    private static final List<String> RESEARCH_GOALS = List.of(
            "Grant / proposal",
            "A paper I'm writing",
            "Exploratory / generate ideas",
            "Teaching / review"
    );

    public IntakeDialog(LineReader reader) {
        this.reader = reader;
    }

    /**
     * Run the full intake dialog and return a seed context string that is used as the
     * first user message in the {@link ai.intelliswarm.researchagent.agent.ConversationEngine}.
     */
    public String run() {
        print("");
        print("  ╔═══════════════════════════════════════════════════════╗");
        print("  ║          Research Agent — New Investigation           ║");
        print("  ╚═══════════════════════════════════════════════════════╝");
        print("");

        // 1. Field
        String field = ask("  What is your medical / research field? (e.g. neurology, oncology, cardiology)");
        if (field.isBlank()) field = "medicine";

        // 2. Topic
        String topic = ask("  What is your specific topic or question? (e.g. 'role of tau in early Alzheimer')");

        // 3. Aspect — field-specific numbered choices
        List<String> aspects = aspectsFor(field + " " + topic);
        String aspect = choiceQuestion("  Which aspect of " + capitalize(field) + " is your research focused on?",
                aspects, "1 of 3");
        if (aspect == null) aspect = "";

        // 4. Evidence level
        String evidenceLevel = choiceQuestion("  What level of evidence are you most interested in?",
                EVIDENCE_LEVELS, "2 of 3");
        if (evidenceLevel == null) evidenceLevel = "Any / no preference";

        // 5. Goal
        String goal = choiceQuestion("  What's the goal for these hypotheses?",
                RESEARCH_GOALS, "3 of 3");
        if (goal == null) goal = "Exploratory / generate ideas";

        // 6. Draft hypothesis + confirm loop
        String hypothesis = draftAndConfirmHypothesis(field, topic, aspect, evidenceLevel, goal);

        // Build seed context
        return buildSeedContext(field, topic, aspect, evidenceLevel, goal, hypothesis);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String draftAndConfirmHypothesis(String field, String topic, String aspect,
                                              String evidenceLevel, String goal) {
        // Auto-draft from inputs
        String draft = "In the context of " + field + ", " + topic.trim()
                + (aspect.isBlank() ? "" : ", focusing on " + aspect.toLowerCase())
                + ", can be evaluated through " + evidenceLevel.toLowerCase() + " evidence"
                + " to inform " + goal.toLowerCase() + ".";

        print("");
        print("  ── Proposed hypothesis ──────────────────────────────────");
        print("  " + draft);
        print("  ─────────────────────────────────────────────────────────");
        print("");

        while (true) {
            String answer = ask("  Accept this hypothesis? [y]es / [e]dit / [r]ewrite").trim().toLowerCase();
            if (answer.equals("y") || answer.equals("yes") || answer.isEmpty()) {
                return draft;
            } else if (answer.equals("e") || answer.equals("edit")) {
                String edited = ask("  Enter your hypothesis");
                if (!edited.isBlank()) return edited;
            } else if (answer.equals("r") || answer.equals("rewrite")) {
                String rewritten = ask("  Describe the hypothesis you want to test");
                if (!rewritten.isBlank()) return rewritten;
            }
        }
    }

    /**
     * Present a numbered-choice question (styled like the design screenshots).
     * Returns the chosen label, a custom free-text entry, or {@code null} if skipped.
     */
    private String choiceQuestion(String question, List<String> choices, String progress) {
        print("");
        print("  " + question + "    < " + progress + " >");
        print("");
        for (int i = 0; i < choices.size(); i++) {
            print("  " + (i + 1) + "  " + choices.get(i));
        }
        print("  ✎  Something else");
        print("                                                   [Skip]");
        print("");

        while (true) {
            String raw = ask("  Enter number, custom text, or press Enter to skip").trim();
            if (raw.isEmpty() || raw.equalsIgnoreCase("skip")) return null;
            try {
                int idx = Integer.parseInt(raw) - 1;
                if (idx >= 0 && idx < choices.size()) return choices.get(idx);
            } catch (NumberFormatException ignored) {
                // treat as free-text
                if (!raw.isBlank()) return raw;
            }
        }
    }

    private List<String> aspectsFor(String fieldAndTopic) {
        String lower = fieldAndTopic.toLowerCase();
        for (var entry : ASPECT_CHOICES.entrySet()) {
            if (!entry.getKey().equals("default") && lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return ASPECT_CHOICES.get("default");
    }

    private String ask(String prompt) {
        try {
            return reader.readLine(prompt + "\n  > ");
        } catch (Exception e) {
            return "";
        }
    }

    private void print(String msg) {
        reader.getTerminal().writer().println(msg);
        reader.getTerminal().writer().flush();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String buildSeedContext(String field, String topic, String aspect,
                                            String evidenceLevel, String goal, String hypothesis) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Research Investigation Request\n\n");
        sb.append("**Field:** ").append(field).append("\n");
        sb.append("**Topic:** ").append(topic).append("\n");
        if (!aspect.isBlank()) sb.append("**Aspect:** ").append(aspect).append("\n");
        sb.append("**Evidence level of interest:** ").append(evidenceLevel).append("\n");
        sb.append("**Goal:** ").append(goal).append("\n\n");
        sb.append("**Hypothesis to investigate:**\n> ").append(hypothesis).append("\n\n");
        sb.append("Please begin by creating a research plan with `todo_write`, then systematically:\n");
        sb.append("1. Spawn `literature-scout` sub-agents to search relevant databases and ingest papers into RAG.\n");
        sb.append("2. Spawn `evidence-appraiser` sub-agents to retrieve and classify evidence as SUPPORTS / CONTRADICTS / NEUTRAL.\n");
        sb.append("3. Synthesize findings and call `report_write` with a full markdown report including hypothesis, supporting evidence, contradicting evidence, and limitations.\n");
        return sb.toString();
    }
}
