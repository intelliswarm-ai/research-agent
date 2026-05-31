package ai.intelliswarm.researchagent.cli;

import ai.intelliswarm.researchagent.agent.ConversationEngine;
import ai.intelliswarm.researchagent.agent.ConversationEngine.ToolCallObserver;
import ai.intelliswarm.researchagent.agent.ConversationEngine.TurnResult;
import ai.intelliswarm.researchagent.agent.PermissionPrompter;
import ai.intelliswarm.researchagent.agent.Session;
import ai.intelliswarm.researchagent.config.ResearchProperties;
import ai.intelliswarm.researchagent.eval.MetricsCollector;
import ai.intelliswarm.researchagent.tool.TodoList;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import ai.intelliswarm.swarmai.tool.common.CSVAnalysisTool;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * Interactive JLine REPL for the dynamic research agent.
 *
 * <p>On startup, an {@link IntakeDialog} walks the researcher through
 * field / topic / aspect / evidence-level / goal questions and formalizes a
 * testable hypothesis. The accepted hypothesis seeds the orchestrator's first turn.
 *
 * <p>Slash commands:
 * <ul>
 *   <li>{@code /help}       — show available commands</li>
 *   <li>{@code /plan}       — show current investigation plan (todo list)</li>
 *   <li>{@code /hypothesis} — show the current hypothesis</li>
 *   <li>{@code /new}        — restart the intake dialog with a new hypothesis</li>
 *   <li>{@code /cost}       — show cumulative token usage</li>
 *   <li>{@code /clear}      — clear conversation history</li>
 *   <li>{@code /exit}       — quit</li>
 * </ul>
 */
@Component
public class ResearchRepl {

    private static final Logger log = LoggerFactory.getLogger(ResearchRepl.class);

    private final ConversationEngine engine;
    private final Session session;
    private final TodoList todoList;
    private final ResearchProperties props;
    private final ai.intelliswarm.researchagent.agent.ToolRouter router;
    private final ai.intelliswarm.researchagent.tool.RelevanceLedger relevanceLedger;
    private final ai.intelliswarm.researchagent.tool.IngestLedger ingestLedger;
    private final ai.intelliswarm.swarmai.agent.llm.LlmClient llm;
    private final MetricsCollector metrics;
    private final CSVAnalysisTool csvTool; // nullable

    private String currentHypothesis = "";

    public ResearchRepl(ConversationEngine engine,
                        Session session,
                        TodoList todoList,
                        ResearchProperties props,
                        ai.intelliswarm.researchagent.agent.ToolRouter router,
                        ai.intelliswarm.researchagent.tool.RelevanceLedger relevanceLedger,
                        ai.intelliswarm.researchagent.tool.IngestLedger ingestLedger,
                        ai.intelliswarm.swarmai.agent.llm.LlmClient llm,
                        MetricsCollector metrics,
                        org.springframework.beans.factory.ObjectProvider<CSVAnalysisTool> csvProvider) {
        this.engine = engine;
        this.session = session;
        this.todoList = todoList;
        this.props = props;
        this.router = router;
        this.relevanceLedger = relevanceLedger;
        this.ingestLedger = ingestLedger;
        this.llm = llm;
        this.metrics = metrics;
        this.csvTool = csvProvider.getIfAvailable();
    }

    private void resetSessionState() {
        session.clear();
        todoList.clear();
        relevanceLedger.clear();
        ingestLedger.clear();
    }

    /** Run the REPL, immediately loading the given CSV as input. */
    public void runWithCsv(String csvPath) throws IOException {
        run(csvPath);
    }

    public void run() throws IOException {
        run(null);
    }

    private void run(String initialCsvPath) throws IOException {
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).dumb(true).build();
        } catch (Exception e) {
            // Fallback for piped / non-TTY environments (CI, tests)
            terminal = TerminalBuilder.builder().streams(System.in, System.out).dumb(true).build();
        }
        PrintWriter out = terminal.writer();

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter(
                        "/help", "/plan", "/hypothesis", "/new", "/load",
                        "/cost", "/clear", "/exit"))
                .variable(LineReader.HISTORY_FILE, System.getProperty("user.home") + "/.research-agent-history")
                .build();

        PermissionPrompter prompter = props.isAutoApprove()
                ? PermissionPrompter.allowAll()
                : new ConsolePermissionPrompter(reader);
        // Sub-agents route their tool calls through this same policy.
        router.setSessionPrompter(prompter);

        // Activate the live status bar (bottom line). Shows model + running cost as tools fire.
        String model = props.getModel().getPrimary();
        try {
            Status status = Status.getStatus(terminal, true);
            if (status != null) {
                AttributedString initial = new AttributedStringBuilder()
                        .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold())
                        .append(" " + model)
                        .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE))
                        .append("  │  ")
                        .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold())
                        .append("$0.0000")
                        .toAttributedString();
                status.update(List.of(initial));
            }
        } catch (Exception ignored) {}

        ToolCallObserver observer = new CliToolCallObserver(out, terminal, metrics, session, model);

        // ── Intake ──────────────────────────────────────────────────────────
        IntakeDialog intake = new IntakeDialog(reader, llm, props, csvTool);
        String seedMessage = (initialCsvPath != null && csvTool != null)
                ? intake.runFromCsv(initialCsvPath)
                : intake.run();
        // Extract hypothesis line for /hypothesis command
        for (String line : seedMessage.split("\n")) {
            if (line.startsWith("> ")) {
                currentHypothesis = line.substring(2).trim();
                break;
            }
        }

        out.println();
        out.println("  Starting investigation. Type /help for commands.");
        out.println();
        out.flush();

        // Run the first turn automatically with the intake seed
        runTurn(seedMessage, prompter, observer, out);

        // ── REPL loop ────────────────────────────────────────────────────────
        while (true) {
            String line;
            try {
                double cost = metrics.totalCostUSD(session, model);
                String prompt = String.format("\n  [%s · $%.4f]\n  You > ", model, cost);
                line = reader.readLine(prompt).trim();
            } catch (UserInterruptException | EndOfFileException e) {
                break;
            }

            if (line.isEmpty()) continue;

            if (line.startsWith("/")) {
                if (!handleSlashCommand(line, reader, out, prompter, observer)) break;
            } else {
                runTurn(line, prompter, observer, out);
            }
        }

        out.println();
        out.println("  Goodbye.");
        out.flush();
    }

    // ── slash command dispatch ───────────────────────────────────────────────

    /** Returns false to signal exit. */
    private boolean handleSlashCommand(String line, LineReader reader, PrintWriter out,
                                        PermissionPrompter prompter, ToolCallObserver observer) {
        String cmd = line.split("\\s+")[0].toLowerCase();
        switch (cmd) {
            case "/help" -> printHelp(out);
            case "/plan" -> {
                out.println();
                out.println("  Current investigation plan:");
                out.println(todoList.render().indent(4));
                out.flush();
            }
            case "/hypothesis" -> {
                out.println();
                out.println("  Current hypothesis: " + currentHypothesis);
                out.println();
                out.flush();
            }
            case "/new" -> {
                resetSessionState();
                IntakeDialog intake = new IntakeDialog(reader, llm, props, csvTool);
                String seed = intake.run();
                for (String l : seed.split("\n")) {
                    if (l.startsWith("> ")) { currentHypothesis = l.substring(2).trim(); break; }
                }
                runTurn(seed, prompter, observer, out);
            }
            case "/load" -> {
                if (csvTool == null) {
                    out.println("  csv_analysis is not available (commons-csv not on classpath).");
                    out.flush();
                    break;
                }
                String csvPath = line.length() > 5 ? line.substring(5).trim() : "";
                resetSessionState();
                IntakeDialog intake = new IntakeDialog(reader, llm, props, csvTool);
                String seed = intake.runFromCsv(csvPath.isBlank() ? null : csvPath);
                for (String l : seed.split("\n")) {
                    if (l.startsWith("> ")) { currentHypothesis = l.substring(2).trim(); break; }
                }
                runTurn(seed, prompter, observer, out);
            }
            case "/cost" -> {
                String model = props.getModel().getPrimary();
                double[] pricing = MetricsCollector.pricingFor(model);
                long in  = metrics.totalInputTokens(session);
                long out2 = metrics.totalOutputTokens(session);
                double cost = metrics.totalCostUSD(session, model);
                out.println();
                out.println("  ── Cost breakdown ─────────────────────────────────────");
                out.printf("  Model:   %s%n", model);
                out.printf("  Pricing: $%.2f in / $%.2f out  per 1M tokens%n", pricing[0], pricing[1]);
                out.printf("  Tokens:  %,d in  +  %,d out  =  %,d total%n", in, out2, in + out2);
                out.printf("  Cost:    $%.4f  (estimated)%n", cost);
                out.println("  ───────────────────────────────────────────────────────");
                out.println();
                out.flush();
            }
            case "/clear" -> {
                resetSessionState();
                out.println("  Conversation and plan cleared.");
                out.flush();
            }
            case "/exit", "/quit" -> { return false; }
            default -> {
                out.println("  Unknown command: " + cmd + "  (type /help)");
                out.flush();
            }
        }
        return true;
    }

    private void runTurn(String userMessage, PermissionPrompter prompter,
                          ToolCallObserver observer, PrintWriter out) {
        try {
            TurnResult result = engine.runTurn(userMessage, prompter, observer);
            if (!result.finalText().isBlank()) {
                out.println();
                out.println("  ───────────────────────────────────────────────────────");
                for (String line : result.finalText().split("\n")) {
                    out.println("  " + line);
                }
                out.println("  ───────────────────────────────────────────────────────");
            }
            // Running cost — shown after every turn so the user can see spend accumulate.
            out.printf("  💰 %s%n", metrics.costSummary(session, props.getModel().getPrimary()));
            out.println();
            out.flush();
        } catch (OutOfMemoryError oom) {
            // A single heavy turn (big PDF + large accumulated context) can exhaust the
            // heap. Survive it: drop the history so the session can keep going.
            session.clear();
            todoList.clear();
            out.println();
            out.println("  ⚠ This turn ran out of memory and was aborted. History was cleared to");
            out.println("    recover — start a fresh investigation with /new, or relaunch with more");
            out.println("    heap (run.sh now sets -Xmx4g).");
            out.println();
            out.flush();
        } catch (Throwable t) {
            out.println();
            out.println("  ⚠ This turn failed: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? " — " + t.getMessage() : ""));
            out.println("    The session is still alive — try again, or /new to reset.");
            out.println();
            out.flush();
            log.warn("Turn failed", t);
        }
    }

    private static void printHelp(PrintWriter out) {
        out.println();
        out.println("  Commands:");
        out.println("    /plan            Show the current investigation plan");
        out.println("    /hypothesis      Show the current research hypothesis");
        out.println("    /new             Start a new investigation (clears history)");
        out.println("    /load [path]     Load a CSV file and start a data-driven investigation");
        out.println("    /cost            Show cumulative token usage");
        out.println("    /clear           Clear conversation history and plan");
        out.println("    /exit            Quit");
        out.println();
        out.flush();
    }

    // ── inner observer ───────────────────────────────────────────────────────

    /**
     * Renders orchestrator tool activity as human-readable progress lines and keeps
     * a live status bar at the bottom of the terminal showing the running cost and model.
     */
    private static class CliToolCallObserver implements ToolCallObserver {
        private final PrintWriter out;
        private final Terminal terminal;
        private final MetricsCollector metrics;
        private final Session session;
        private final String model;

        CliToolCallObserver(PrintWriter out, Terminal terminal,
                            MetricsCollector metrics, Session session, String model) {
            this.out      = out;
            this.terminal = terminal;
            this.metrics  = metrics;
            this.session  = session;
            this.model    = model;
        }

        @Override
        public void onToolCallStart(LlmToolCall call) {
            if ("subagent_spawn".equals(call.toolName())) {
                Object type = call.arguments() == null ? null : call.arguments().get("type");
                out.println("  ◆ delegating to " + (type == null ? "sub-agent" : type) + " …");
                out.flush();
            }
        }

        @Override
        public void onToolCallEnd(LlmToolCall call, String resultPreview, long elapsedMs) {
            String r = resultPreview == null ? "" : resultPreview;
            String secs = elapsedMs >= 1000 ? (elapsedMs / 1000) + "s" : elapsedMs + "ms";

            // Surface gate blocks prominently — they are actionable.
            if (r.startsWith("⛔") || r.contains("BLOCKED")) {
                String msg = r.replaceAll("\\s+", " ").trim();
                if (msg.length() > 240) msg = msg.substring(0, 240) + "…";
                out.println("  ⛔ " + msg);
                out.flush();
                updateStatusBar();
                return;
            }

            String line = switch (call.toolName()) {
                case "todo_write"        -> "  ✎ updated the plan";
                case "subagent_spawn"    -> "  ◆ sub-agent finished (" + secs + ")";
                case "relevance_filter"  -> "  🔎 screened paper relevance";
                case "citation_validate" -> "  ✅ validated citations";
                case "rag_status"        -> "  📚 checked the knowledge base";
                case "report_write"      -> "  📄 report written";
                default                  -> "  · " + call.toolName() + " (" + secs + ")";
            };
            out.println(line);
            out.flush();
            updateStatusBar();
        }

        private void updateStatusBar() {
            try {
                Status status = Status.getStatus(terminal, false);
                if (status == null) return;
                long in   = metrics.totalInputTokens(session);
                long out2 = metrics.totalOutputTokens(session);
                double cost = metrics.totalCostUSD(session, model);
                AttributedStringBuilder asb = new AttributedStringBuilder();
                asb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold());
                asb.append(" " + model);
                asb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE));
                asb.append("  │  ");
                asb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold());
                asb.append(String.format("$%.4f", cost));
                asb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE));
                asb.append(String.format("  (%,d in + %,d out tokens)", in, out2));
                status.update(List.of(asb.toAttributedString()));
            } catch (Exception ignored) {}
        }
    }
}
