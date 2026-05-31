package ai.intelliswarm.researchagent;

import ai.intelliswarm.researchagent.cli.ResearchRepl;
import ai.intelliswarm.researchagent.config.DotenvLoader;
import ai.intelliswarm.researchagent.eval.BatchResearchRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;
import java.util.List;

/**
 * Entry point — runs either the interactive REPL or headless batch mode.
 *
 * <p>Interactive (default):
 * <pre>./run.sh</pre>
 *
 * <p>Batch evaluation mode (for research-agent-eval):
 * <pre>java -jar research-agent.jar --batch "my hypothesis here"</pre>
 */
@SpringBootApplication(scanBasePackages = {
    "ai.intelliswarm.researchagent",
    "ai.intelliswarm.swarmai.tool",
    "ai.intelliswarm.swarmai.rag"
})
public class ResearchAgentApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ResearchAgentApplication.class);

    private final ResearchRepl repl;
    private final BatchResearchRunner batchRunner;

    public ResearchAgentApplication(ResearchRepl repl, BatchResearchRunner batchRunner) {
        this.repl        = repl;
        this.batchRunner = batchRunner;
    }

    public static void main(String[] args) {
        DotenvLoader.load();
        SpringApplication.run(ResearchAgentApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        List<String> argList = Arrays.asList(args);

        int batchIdx = argList.indexOf("--batch");
        int csvIdx   = argList.indexOf("--csv");

        if (batchIdx >= 0 && batchIdx + 1 < argList.size()) {
            String hypothesis = argList.get(batchIdx + 1);
            log.info("Batch mode — hypothesis: {}", hypothesis);
            batchRunner.run(hypothesis);
        } else if (batchIdx >= 0) {
            System.err.println("Usage: --batch \"<hypothesis text>\"");
            System.exit(1);
        } else if (csvIdx >= 0 && csvIdx + 1 < argList.size()) {
            String csvPath = argList.get(csvIdx + 1);
            log.info("Interactive mode with CSV input — starting REPL, path: {}", csvPath);
            repl.runWithCsv(csvPath);
        } else {
            log.info("Interactive mode — starting REPL");
            repl.run();
        }
    }
}
