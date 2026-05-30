package ai.intelliswarm.researchagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the dynamic research engine, bound from the {@code research-agent.*}
 * block in {@code application.yml}.
 */
@ConfigurationProperties("research-agent")
public class ResearchProperties {

    private Model model = new Model();
    private Agents agents = new Agents();
    private String outputDir = "./output";
    private boolean autoApprove = false;

    public Model getModel() { return model; }
    public void setModel(Model model) { this.model = model; }

    public Agents getAgents() { return agents; }
    public void setAgents(Agents agents) { this.agents = agents; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public boolean isAutoApprove() { return autoApprove; }
    public void setAutoApprove(boolean autoApprove) { this.autoApprove = autoApprove; }

    public static class Model {
        /** Model id sent on every LLM request. Mirrors the Spring AI OpenAI chat model. */
        private String primary = "gpt-4o-mini";
        private int maxOutputTokens = 4096;

        public String getPrimary() { return primary; }
        public void setPrimary(String primary) { this.primary = primary; }

        public int getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    }

    public static class Agents {
        /** Hard cap on orchestrator reasoning turns per user message. */
        private int maxTurnsPerTask = 50;
        /** Default cap on a spawned sub-agent's reasoning turns. */
        private int subagentMaxTurns = 20;

        public int getMaxTurnsPerTask() { return maxTurnsPerTask; }
        public void setMaxTurnsPerTask(int maxTurnsPerTask) { this.maxTurnsPerTask = maxTurnsPerTask; }

        public int getSubagentMaxTurns() { return subagentMaxTurns; }
        public void setSubagentMaxTurns(int subagentMaxTurns) { this.subagentMaxTurns = subagentMaxTurns; }
    }
}
