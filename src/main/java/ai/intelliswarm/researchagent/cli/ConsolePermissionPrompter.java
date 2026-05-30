package ai.intelliswarm.researchagent.cli;

import ai.intelliswarm.researchagent.agent.PermissionPrompter;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.jline.reader.LineReader;

import java.util.Map;

/** Asks the user yes / no / always before running a write or network tool. */
public class ConsolePermissionPrompter implements PermissionPrompter {

    private final LineReader reader;

    public ConsolePermissionPrompter(LineReader reader) {
        this.reader = reader;
    }

    @Override
    public Decision prompt(BaseTool tool, Map<String, Object> args) {
        String argsStr = String.valueOf(args);
        if (argsStr.length() > 120) argsStr = argsStr.substring(0, 120) + "…";

        reader.getTerminal().writer().println();
        reader.getTerminal().writer().println(
                "  Approve: " + tool.getFunctionName() + " (" + tool.getPermissionLevel() + ")");
        reader.getTerminal().writer().println("  Args: " + argsStr);
        reader.getTerminal().writer().flush();

        while (true) {
            String line;
            try {
                line = reader.readLine("  [y]es / [n]o / [a]lways this session > ").trim().toLowerCase();
            } catch (Exception e) {
                return Decision.DENY;
            }
            if (line.isEmpty() || line.equals("n") || line.equals("no")) return Decision.DENY;
            if (line.equals("y") || line.equals("yes"))                   return Decision.ALLOW_ONCE;
            if (line.equals("a") || line.equals("always"))                return Decision.ALLOW_SESSION;
        }
    }
}
