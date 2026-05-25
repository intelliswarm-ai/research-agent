package ai.intelliswarm.researchagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point — runs the research-agent workflow once with the CSV path passed as args[0]
 * (or the bundled sample if no path is given).
 */
@SpringBootApplication(scanBasePackages = {
    "ai.intelliswarm.researchagent",
    "ai.intelliswarm.swarmai.tool", // swarmai-tools' @Component tools (CSVAnalysisTool, ArxivTool, …)
    "ai.intelliswarm.swarmai.rag"   // swarmai-rag's tools + Lucene autoconfig
    // Deliberately *not* scanning ai.intelliswarm.swarmai.memory / .knowledge / etc. — those
    // @Configuration classes have hard references to Redis/JDBC that we don't ship here.
})
public class ResearchAgentApplication implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ResearchAgentApplication.class);

    @Autowired private ResearchAgentWorkflow workflow;

    public static void main(String[] args) {
        SpringApplication.run(ResearchAgentApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        String csvPath = args.length > 0 ? args[0] : "data/sample.csv";
        logger.info("Research Agent starting — CSV: {}", csvPath);
        workflow.run(csvPath);
    }
}
