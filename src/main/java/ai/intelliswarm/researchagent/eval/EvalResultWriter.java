package ai.intelliswarm.researchagent.eval;

import ai.intelliswarm.researchagent.agent.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a structured JSON result file after each batch run so the
 * {@code research-agent-eval} module can read it without parsing terminal output.
 *
 * <p>Output path: {@code output/eval_result_<timestamp>.json}
 */
@Component
public class EvalResultWriter {

    private static final Logger log = LoggerFactory.getLogger(EvalResultWriter.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public void write(String hypothesis, String model, String reportFilePath,
                       MetricsCollector metrics, Session session,
                       QualityScorer.ScoreResult scores, long wallMs) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hypothesis",     hypothesis);
        result.put("model",          model);
        result.put("reportFile",     reportFilePath);
        result.put("wallClockMs",    wallMs);

        // Metrics
        result.put("totalInputTokens",  metrics.totalInputTokens(session));
        result.put("totalOutputTokens", metrics.totalOutputTokens(session));
        result.put("costUsd",           metrics.totalCostUSD(session));
        result.put("papersIngested",    metrics.papersIngested());
        result.put("ragSearches",       metrics.ragSearchesRun());
        result.put("subagentsSpawned",  metrics.subagents().size());
        result.put("toolCallCounts",    metrics.toolCallCountByName());

        // Scores
        result.put("scoreStructure",   scores.structure());
        result.put("scoreEvidence",    scores.evidence());
        result.put("scoreCitations",   scores.citations());
        result.put("scoreBalance",     scores.balance());
        result.put("scoreVerdict",     scores.verdict());
        result.put("scoreEfficiency",  scores.efficiency());
        result.put("scoreOverall",     scores.overall());
        result.put("qualityIssues",    scores.issues());
        result.put("citationValidationPassed", scores.citationValidationPassed());

        Path outDir = Paths.get("output").toAbsolutePath();
        Files.createDirectories(outDir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path file = outDir.resolve("eval_result_" + ts + ".json");
        JSON.writeValue(file.toFile(), result);
        log.info("Eval result written to {}", file);
    }
}
