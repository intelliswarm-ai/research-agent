package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.researchagent.config.ResearchProperties;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the final research report to disk and returns the path.
 * The orchestrator calls this at the end of the investigation.
 */
@Component
public class ReportWriteTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(ReportWriteTool.class);

    private final ResearchProperties props;

    public ReportWriteTool(ResearchProperties props) {
        this.props = props;
    }

    @Override public String getFunctionName() { return "report_write"; }

    @Override
    public String getDescription() {
        return "Write the final research report to disk. Call this once at the end of the investigation, "
             + "passing the complete markdown report as 'content'. Returns the path where the report was saved.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "output"; }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String content = parameters.get("content") instanceof String s ? s : null;
        if (content == null || content.isBlank()) {
            return "Error: 'content' (the markdown report) is required.";
        }
        String filename = parameters.get("filename") instanceof String f ? f : null;
        if (filename == null || filename.isBlank()) {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            filename = "research_report_" + ts + ".md";
        }
        if (!filename.endsWith(".md")) filename += ".md";

        try {
            Path outDir = Paths.get(props.getOutputDir()).toAbsolutePath().normalize();
            Files.createDirectories(outDir);
            Path reportPath = outDir.resolve(filename);
            Files.writeString(reportPath, content, StandardCharsets.UTF_8);
            log.info("Report written to {}", reportPath);
            return "Report saved to: " + reportPath;
        } catch (IOException e) {
            log.error("Failed to write report: {}", e.getMessage());
            return "Error writing report: " + e.getMessage();
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("content", Map.of("type", "string",
                "description", "The complete markdown research report to write to disk."));
        props.put("filename", Map.of("type", "string",
                "description", "Optional output filename (default: research_report_<timestamp>.md)."));
        schema.put("properties", props);
        schema.put("required", new String[]{"content"});
        return schema;
    }
}
