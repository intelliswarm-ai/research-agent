package ai.intelliswarm.researchagent.agent;

import ai.intelliswarm.swarmai.tool.base.BaseTool;

import java.util.Map;

/** Strategy for asking the user whether a tool call may proceed. */
public interface PermissionPrompter {

    enum Decision { ALLOW_ONCE, ALLOW_SESSION, DENY }

    /**
     * Called before invoking a tool whose permission level requires confirmation.
     * Implementations may block on console input, persist allow-lists, etc.
     */
    Decision prompt(BaseTool tool, Map<String, Object> args);

    /** Always denies — safe fallback used by spawned sub-agents. */
    static PermissionPrompter denyAll() {
        return (tool, args) -> Decision.DENY;
    }

    /** Always allows for this session — used in auto-approve / unattended mode. */
    static PermissionPrompter allowAll() {
        return (tool, args) -> Decision.ALLOW_SESSION;
    }
}
