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
        // RAG isolation for batch/eval runs: wipe the persistent Lucene index BEFORE the
        // Spring context (and its IndexWriter) opens it, so each evaluation run starts from a
        // clean store. Without this, papers — and stale relevance rejections — from previous
        // runs leak into rag_search, causing gate-loops and non-reproducible eval scores.
        // Skip with --keep-index (e.g. to debug against an accumulated store).
        List<String> argList = Arrays.asList(args);
        boolean batch = argList.contains("--batch");
        boolean keepIndex = argList.contains("--keep-index");
        if (batch && !keepIndex) {
            wipeRagIndex();
        }
        SpringApplication.run(ResearchAgentApplication.class, args);
    }

    /**
     * Recursively deletes the Lucene RAG index directory so a batch run starts clean.
     * Resolves the path exactly as application.yml does:
     * {@code ${RESEARCH_AGENT_INDEX:./.research-agent-index}}.
     */
    private static void wipeRagIndex() {
        String indexPath = System.getenv("RESEARCH_AGENT_INDEX");
        if (indexPath == null || indexPath.isBlank()) {
            indexPath = "./.research-agent-index";
        }
        java.nio.file.Path dir = java.nio.file.Paths.get(indexPath);
        if (!java.nio.file.Files.exists(dir)) {
            log.info("RAG isolation: no existing index at {} — starting clean.", dir.toAbsolutePath());
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { java.nio.file.Files.delete(p); }
                    catch (java.io.IOException e) { log.warn("RAG isolation: could not delete {}: {}", p, e.getMessage()); }
                });
            log.info("RAG isolation: wiped index at {} for a fresh batch run.", dir.toAbsolutePath());
        } catch (java.io.IOException e) {
            log.warn("RAG isolation: failed to wipe index at {}: {}", dir.toAbsolutePath(), e.getMessage());
        }
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
