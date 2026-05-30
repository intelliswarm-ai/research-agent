package ai.intelliswarm.researchagent.cli;

import ai.intelliswarm.researchagent.config.ResearchProperties;
import ai.intelliswarm.swarmai.agent.llm.LlmClient;
import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRequest;
import ai.intelliswarm.swarmai.agent.llm.LlmResponse;
import org.jline.reader.LineReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptive research intake.
 *
 * <p>Instead of asking the same fixed field/aspect/evidence/goal questions for every topic, the
 * agent itself decides whether the request is specific enough. If it is, it drafts a testable
 * hypothesis and proceeds. If not, it asks ONLY the 1-3 clarifying questions that actually matter
 * for that topic. The accepted hypothesis seeds the investigation.
 */
public class IntakeDialog {

    private static final String CLARIFIER_SYSTEM = """
            You are a research intake assistant for a medical/scientific literature agent.
            The user states what they want to research. Decide if it is specific enough to begin a
            rigorous literature investigation.

            - If it IS specific enough, reply with exactly one line:
              READY: <one-sentence testable hypothesis>
            - If it needs clarification, reply with:
              CLARIFY:
              1. <question>
              2. <question>
              (1 to 3 questions max)

            Ask ONLY questions that genuinely sharpen THIS topic — e.g. population/species, the
            specific intervention or exposure, the comparator, the outcome measured, timeframe, or
            scope. Never ask generic boilerplate. Prefer fewer questions. Be concise.""";

    private static final String SYNTH_SYSTEM = """
            Combine the research topic and the user's clarifying answers into ONE crisp, testable
            research hypothesis — a single declarative sentence suitable for a systematic review.
            Reply with ONLY the hypothesis sentence, no preamble.""";

    private final LineReader reader;
    private final LlmClient llm;
    private final ResearchProperties props;

    public IntakeDialog(LineReader reader, LlmClient llm, ResearchProperties props) {
        this.reader = reader;
        this.llm = llm;
        this.props = props;
    }

    /** Run the adaptive intake and return the seed message for the orchestrator. */
    public String run() {
        print("");
        print("  ╔═══════════════════════════════════════════════════════╗");
        print("  ║          Research Agent — New Investigation           ║");
        print("  ╚═══════════════════════════════════════════════════════╝");
        print("");

        String topic = ask("  What would you like to research?");
        if (topic == null || topic.isBlank()) topic = "general medical research";

        // Ask the model to either accept the topic or propose targeted clarifying questions.
        String clarifierOut = callLlm(CLARIFIER_SYSTEM, topic);
        List<String> qaPairs = new ArrayList<>();
        String hypothesis;

        if (clarifierOut != null && clarifierOut.toUpperCase().contains("CLARIFY")) {
            List<String> questions = parseQuestions(clarifierOut);
            if (questions.isEmpty()) {
                hypothesis = topic;
            } else {
                print("");
                print("  A few quick questions to focus the search (press Enter to skip any):");
                for (String q : questions) {
                    String a = ask("  • " + q);
                    if (a != null && !a.isBlank()) qaPairs.add(q + " → " + a.trim());
                }
                StringBuilder synthInput = new StringBuilder("Topic: ").append(topic).append("\n");
                if (!qaPairs.isEmpty()) {
                    synthInput.append("Answers:\n");
                    for (String qa : qaPairs) synthInput.append("- ").append(qa).append("\n");
                }
                String synth = callLlm(SYNTH_SYSTEM, synthInput.toString());
                hypothesis = (synth == null || synth.isBlank()) ? topic : synth.trim();
            }
        } else {
            hypothesis = stripPrefix(clarifierOut == null ? topic : clarifierOut, "READY:");
            if (hypothesis.isBlank()) hypothesis = topic;
        }

        // Confirm / edit loop.
        hypothesis = confirm(hypothesis);

        return buildSeed(topic, qaPairs, hypothesis);
    }

    // ── LLM ──────────────────────────────────────────────────────────────────

    private String callLlm(String system, String user) {
        try {
            LlmRequest req = LlmRequest.builder()
                    .model(props.getModel().getPrimary())
                    .system(system)
                    .maxOutputTokens(400)
                    .message(LlmMessage.user(user))
                    .build();
            LlmResponse resp = llm.send(req);
            return resp.text() == null ? "" : resp.text().trim();
        } catch (Exception e) {
            // Network/LLM hiccup — fall back to using the raw topic as the hypothesis.
            return null;
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String confirm(String draft) {
        print("");
        print("  ── Proposed hypothesis ──────────────────────────────────");
        print("  " + draft);
        print("  ─────────────────────────────────────────────────────────");
        while (true) {
            String a = ask("  Accept? [y]es / [e]dit").trim().toLowerCase();
            if (a.isEmpty() || a.equals("y") || a.equals("yes")) return draft;
            if (a.equals("e") || a.equals("edit")) {
                String edited = ask("  Enter your hypothesis");
                if (edited != null && !edited.isBlank()) return edited.trim();
            }
        }
    }

    private static List<String> parseQuestions(String text) {
        List<String> qs = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            // Lines like "1. ...", "2) ...", "- ..."
            if (t.matches("^(\\d+[.)]|[-*•]).*")) {
                String q = t.replaceFirst("^(\\d+[.)]|[-*•])\\s*", "").trim();
                if (!q.isEmpty()) qs.add(q);
            }
        }
        return qs.size() > 3 ? qs.subList(0, 3) : qs;
    }

    private static String stripPrefix(String s, String prefix) {
        String t = s.trim();
        int idx = t.toUpperCase().indexOf(prefix.toUpperCase());
        if (idx >= 0) t = t.substring(idx + prefix.length()).trim();
        return t;
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

    private static String buildSeed(String topic, List<String> qaPairs, String hypothesis) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Research Investigation Request\n\n");
        sb.append("**Original topic:** ").append(topic).append("\n");
        if (!qaPairs.isEmpty()) {
            sb.append("**Clarifications:**\n");
            for (String qa : qaPairs) sb.append("- ").append(qa).append("\n");
        }
        sb.append("\n**Hypothesis to investigate:**\n> ").append(hypothesis).append("\n\n");
        sb.append("Follow the mandatory workflow: plan, decompose into sub-questions, spawn a "
                + "literature-scout (prefer europepmc_fulltext / unpaywall for real full text), verify "
                + "ingestion with rag_status, run relevance_filter, appraise evidence, validate citations, "
                + "then report_write with supporting vs contradicting evidence and a clear verdict "
                + "(including INSUFFICIENT EVIDENCE if no relevant studies exist).\n");
        return sb.toString();
    }
}
