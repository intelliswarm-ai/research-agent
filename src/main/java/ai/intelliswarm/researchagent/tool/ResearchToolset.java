package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.swarmai.tool.base.BaseTool;

import java.util.List;

/**
 * Container for the full set of research tools available to the orchestrator.
 * Assembled by {@link ai.intelliswarm.researchagent.tool.ResearchAgentToolsConfiguration}.
 */
public class ResearchToolset {
    private final List<BaseTool> tools;

    public ResearchToolset(List<BaseTool> tools) {
        this.tools = List.copyOf(tools);
    }

    public List<BaseTool> tools() { return tools; }
}
