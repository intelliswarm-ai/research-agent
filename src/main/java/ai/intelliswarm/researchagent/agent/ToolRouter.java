package ai.intelliswarm.researchagent.agent;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gates tool execution behind a permission check.
 *
 * <p>{@link PermissionLevel#READ_ONLY} tools always run. Anything that writes files
 * or hits the network (search, download, ingest, report, subagent) is routed through
 * the supplied {@link PermissionPrompter} unless it was pre-approved for the session.
 */
@Component
public class ToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    private final Set<String> sessionApproved = ConcurrentHashMap.newKeySet();

    /**
     * The prompter representing the user's active permission policy for this session.
     * Set once at startup by the REPL (interactive console prompter) or the batch
     * runner (auto-approve). Sub-agents route their tool calls through this so that
     * approving a single {@code subagent_spawn} does NOT silently grant every
     * write/network tool the sub-agent then calls.
     */
    private volatile PermissionPrompter sessionPrompter = PermissionPrompter.denyAll();

    public void setSessionPrompter(PermissionPrompter prompter) {
        if (prompter != null) this.sessionPrompter = prompter;
    }

    public PermissionPrompter sessionPrompter() { return sessionPrompter; }

    public void approveForSession(String toolName) { sessionApproved.add(toolName); }

    public boolean isPreApproved(String toolName) { return sessionApproved.contains(toolName); }

    public void reset() { sessionApproved.clear(); }

    /** READ_ONLY tools and session-approved tools run without prompting. */
    public boolean autoAllow(BaseTool tool) {
        if (tool.getPermissionLevel() == PermissionLevel.READ_ONLY) return true;
        return isPreApproved(tool.getFunctionName());
    }

    public Object executeWithRouting(BaseTool tool, Map<String, Object> args, PermissionPrompter prompter) {
        Map<String, Object> currentArgs = args == null ? Map.of() : args;

        if (!autoAllow(tool)) {
            PermissionPrompter.Decision d = prompter.prompt(tool, currentArgs);
            if (d == PermissionPrompter.Decision.DENY) {
                return "Error: user denied permission to run " + tool.getFunctionName();
            }
            if (d == PermissionPrompter.Decision.ALLOW_SESSION) {
                approveForSession(tool.getFunctionName());
            }
        }

        try {
            Object result = tool.execute(currentArgs);
            return result == null ? "(no output)" : result;
        } catch (Exception e) {
            log.warn("Tool {} threw: {}", tool.getFunctionName(), e.getMessage());
            return "Error executing " + tool.getFunctionName() + ": " + e.getMessage();
        }
    }
}
