package ai.intelliswarm.researchagent;

import ai.intelliswarm.researchagent.cli.ResearchRepl;
import ai.intelliswarm.researchagent.config.DotenvLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point — launches the interactive research agent REPL.
 *
 * <p>Loads {@code .env} first so {@code OPENAI_API_KEY} is available before
 * Spring autoconfiguration runs.
 */
@SpringBootApplication(scanBasePackages = {
    "ai.intelliswarm.researchagent",
    "ai.intelliswarm.swarmai.tool",    // swarmai-tools: ArxivTool, WebSearchTool, …
    "ai.intelliswarm.swarmai.rag"      // swarmai-rag: PubMedTool, RagIngestTool, … + Lucene autoconfig
    // Deliberately not scanning ai.intelliswarm.swarmai.memory / .knowledge — those
    // @Configuration classes have hard Redis/JDBC deps we don't ship here.
})
public class ResearchAgentApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ResearchAgentApplication.class);

    private final ResearchRepl repl;

    public ResearchAgentApplication(ResearchRepl repl) {
        this.repl = repl;
    }

    public static void main(String[] args) {
        DotenvLoader.load();
        SpringApplication.run(ResearchAgentApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Research Agent starting — interactive mode");
        repl.run();
    }
}
