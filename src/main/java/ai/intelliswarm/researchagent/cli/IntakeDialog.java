package ai.intelliswarm.researchagent.cli;

import ai.intelliswarm.researchagent.config.ResearchProperties;
import ai.intelliswarm.swarmai.agent.llm.LlmClient;
import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRequest;
import ai.intelliswarm.swarmai.agent.llm.LlmResponse;
import ai.intelliswarm.swarmai.tool.common.CSVAnalysisTool;
import org.jline.reader.LineReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adaptive research intake.
 *
 * <p>Supports two input modes:
 * <ol>
 *   <li><b>Free text</b> — user types a topic; the agent clarifies and drafts a hypothesis.</li>
 *   <li><b>CSV file</b> — user provides a path; the agent profiles the data with
 *       {@link CSVAnalysisTool} and generates 3–5 testable hypotheses for the user to pick from.</li>
 * </ol>
 *
 * <p>In both cases the accepted hypothesis seeds the orchestrator's first turn.
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

    private static final String CSV_HYPO_SYSTEM = """
            You are a medical/scientific research assistant. A researcher has provided a dataset profile
            (column names, types, statistics, and sample rows). Generate exactly 3 to 5 distinct,
            testable research hypotheses that could be investigated using this dataset as a starting
            point. Each hypothesis should be a single declarative sentence suitable for a systematic
            literature review. Number them 1. 2. 3. etc. Output ONLY the numbered list, no preamble.""";

    private final LineReader reader;
    private final LlmClient llm;
    private final ResearchProperties props;
    private final CSVAnalysisTool csvTool; // nullable — only present when commons-csv is on classpath

    public IntakeDialog(LineReader reader, LlmClient llm, ResearchProperties props,
                        CSVAnalysisTool csvTool) {
        this.reader  = reader;
        this.llm     = llm;
        this.props   = props;
        this.csvTool = csvTool;
    }

    // ── Entry points ──────────────────────────────────────────────────────────

    /** Interactive intake — shows mode selection, then runs the chosen flow. */
    public String run() {
        print("");
        print("  ╔═══════════════════════════════════════════════════════╗");
        print("  ║          Research Agent — New Investigation           ║");
        print("  ╚═══════════════════════════════════════════════════════╝");
        print("");

        String mode = selectMode();
        return "csv".equals(mode) ? runCsvMode(null) : runTextMode();
    }

    /** Start directly in CSV mode with a known path (e.g. from a CLI arg or /load command). */
    public String runFromCsv(String csvPath) {
        print("");
        print("  ╔═══════════════════════════════════════════════════════╗");
        print("  ║     Research Agent — CSV-Driven Investigation         ║");
        print("  ╚═══════════════════════════════════════════════════════╝");
        print("");
        return runCsvMode(csvPath);
    }

    // ── Mode selection ────────────────────────────────────────────────────────

    private String selectMode() {
        print("  How would you like to define your research input?");
        print("  [1] Type a topic or hypothesis  (default)");
        if (csvTool != null) {
            print("  [2] Load a CSV data file  (agent profiles data and suggests hypotheses)");
        }
        print("");
        String choice = ask("  Select").trim();
        return (csvTool != null && (choice.equals("2") || choice.equalsIgnoreCase("csv")))
                ? "csv" : "text";
    }

    // ── Text mode (existing flow) ─────────────────────────────────────────────

    private String runTextMode() {
        String topic = ask("  What would you like to research?");
        if (topic == null || topic.isBlank()) topic = "general medical research";

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

        hypothesis = confirm(hypothesis);
        return buildTextSeed(topic, qaPairs, hypothesis);
    }

    // ── CSV mode ──────────────────────────────────────────────────────────────

    private String runCsvMode(String preloadedPath) {
        // 1. Get file path — loop until a valid file is provided or user cancels
        String csvPath = preloadedPath;
        Path path = null;
        while (path == null) {
            while (csvPath == null || csvPath.isBlank()) {
                csvPath = ask("  CSV file path  (or [b]ack to switch mode)").trim();
            }
            if (csvPath.equalsIgnoreCase("b") || csvPath.equalsIgnoreCase("back")) {
                return run(); // back to mode selection
            }
            Path candidate = Paths.get(csvPath).toAbsolutePath().normalize();
            if (Files.exists(candidate)) {
                path = candidate;
            } else {
                print("  ⚠ File not found: " + candidate);
                print("  Enter a valid path, or [b]ack to switch mode.");
                csvPath = null; // re-ask
            }
        }

        // 2. Profile with csv_analysis describe + stats
        print("");
        print("  ── Profiling data… ─────────────────────────────────");
        String describe = (String) csvTool.execute(Map.of("path", path.toString(), "operation", "describe"));
        String stats    = (String) csvTool.execute(Map.of("path", path.toString(), "operation", "stats"));
        String profile  = truncate(describe + "\n\n" + stats, 4000);

        print(describe.lines().limit(20).reduce("", (a, b) -> a + "\n  " + b));
        print("");

        // 3. Generate hypotheses
        print("  ── Generating hypotheses… ──────────────────────────");
        String hypothesesRaw = callLlm(CSV_HYPO_SYSTEM, "Dataset profile:\n\n" + profile);
        List<String> hypotheses = parseNumberedList(hypothesesRaw);

        if (hypotheses.isEmpty()) {
            print("  ⚠ Could not generate hypotheses from dataset. Switching to text mode.");
            return runTextMode();
        }

        // 4. User picks a hypothesis
        print("");
        print("  ── Suggested hypotheses ────────────────────────────");
        for (int i = 0; i < hypotheses.size(); i++) {
            print("  " + (i + 1) + ". " + hypotheses.get(i));
        }
        print("");

        String hypothesis = pickHypothesis(hypotheses);
        hypothesis = confirm(hypothesis);

        return buildCsvSeed(path.toString(), profile, hypothesis);
    }

    private String pickHypothesis(List<String> hypotheses) {
        while (true) {
            String input = ask("  Select [1-" + hypotheses.size() + "] or [e]dit").trim();
            if (input.equalsIgnoreCase("e") || input.equalsIgnoreCase("edit")) {
                String custom = ask("  Enter your hypothesis");
                if (custom != null && !custom.isBlank()) return custom.trim();
                continue;
            }
            try {
                int idx = Integer.parseInt(input) - 1;
                if (idx >= 0 && idx < hypotheses.size()) return hypotheses.get(idx);
            } catch (NumberFormatException ignored) {}
            if (!input.isBlank()) {
                // treat non-empty non-number as a custom hypothesis
                return input;
            }
            print("  Please enter a number between 1 and " + hypotheses.size() + ", or [e]dit.");
        }
    }

    // ── LLM ──────────────────────────────────────────────────────────────────

    private String callLlm(String system, String user) {
        try {
            LlmRequest req = LlmRequest.builder()
                    .model(props.getModel().getPrimary())
                    .system(system)
                    .maxOutputTokens(600)
                    .message(LlmMessage.user(user))
                    .build();
            LlmResponse resp = llm.send(req);
            return resp.text() == null ? "" : resp.text().trim();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Seed builders ─────────────────────────────────────────────────────────

    private static String buildTextSeed(String topic, List<String> qaPairs, String hypothesis) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Research Investigation Request\n\n");
        sb.append("**Original topic:** ").append(topic).append("\n");
        if (!qaPairs.isEmpty()) {
            sb.append("**Clarifications:**\n");
            for (String qa : qaPairs) sb.append("- ").append(qa).append("\n");
        }
        sb.append("\n**Hypothesis to investigate:**\n> ").append(hypothesis).append("\n\n");
        sb.append(WORKFLOW_INSTRUCTIONS);
        return sb.toString();
    }

    private static String buildCsvSeed(String csvPath, String profile, String hypothesis) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Research Investigation Request\n\n");
        sb.append("**Input type:** CSV dataset\n");
        sb.append("**Data file:** ").append(csvPath).append("\n\n");
        sb.append("**Dataset profile (use csv_analysis for deeper queries):**\n");
        sb.append("```\n").append(truncate(profile, 2000)).append("\n```\n\n");
        sb.append("**Hypothesis to investigate:**\n> ").append(hypothesis).append("\n\n");
        sb.append("The dataset is available at `").append(csvPath).append("`. ")
          .append("You may call `csv_analysis` with operation='describe', 'stats', 'head', 'filter', ")
          .append("or 'count' to query specific columns as needed during investigation.\n\n");
        sb.append(WORKFLOW_INSTRUCTIONS);
        return sb.toString();
    }

    private static final String WORKFLOW_INSTRUCTIONS =
            "Follow the mandatory workflow: plan, decompose into sub-questions, spawn a "
            + "literature-scout (prefer europepmc_fulltext / unpaywall for real full text), verify "
            + "ingestion with rag_status, run relevance_filter, appraise evidence, validate citations, "
            + "then report_write with supporting vs contradicting evidence and a clear verdict "
            + "(including INSUFFICIENT EVIDENCE if no relevant studies exist).\n";

    // ── Helpers ───────────────────────────────────────────────────────────────

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
            if (t.matches("^(\\d+[.)]|[-*•]).*")) {
                String q = t.replaceFirst("^(\\d+[.)]|[-*•])\\s*", "").trim();
                if (!q.isEmpty()) qs.add(q);
            }
        }
        return qs.size() > 3 ? qs.subList(0, 3) : qs;
    }

    private static List<String> parseNumberedList(String text) {
        List<String> items = new ArrayList<>();
        if (text == null) return items;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.matches("^\\d+[.):].*")) {
                String item = t.replaceFirst("^\\d+[.):] *", "").trim();
                if (!item.isEmpty()) items.add(item);
            }
        }
        return items;
    }

    private static String stripPrefix(String s, String prefix) {
        String t = s.trim();
        int idx = t.toUpperCase().indexOf(prefix.toUpperCase());
        if (idx >= 0) t = t.substring(idx + prefix.length()).trim();
        return t;
    }

    private static String truncate(String s, int maxChars) {
        return s.length() > maxChars ? s.substring(0, maxChars) + "\n[truncated]" : s;
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
}
