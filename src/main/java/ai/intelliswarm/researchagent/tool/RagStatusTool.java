package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.swarmai.rag.tool.RagSearchTool;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports whether the RAG store actually contains ingested content.
 *
 * <p>The orchestrator calls this after a {@code literature-scout} sub-agent finishes
 * to verify ingestion really happened — sub-agents (especially small models) can
 * <em>claim</em> success without the underlying tools succeeding. This gives the
 * orchestrator a deterministic ground-truth check so it can re-spawn the scout with
 * a different strategy when the store is empty.
 *
 * <p>Implementation: probes the store with a few broad queries via {@link RagSearchTool}
 * and reports whether any chunks come back.
 */
@Component
public class RagStatusTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(RagStatusTool.class);

    private final RagSearchTool ragSearchTool;

    public RagStatusTool(RagSearchTool ragSearchTool) {
        this.ragSearchTool = ragSearchTool;
    }

    @Override public String getFunctionName() { return "rag_status"; }

    @Override
    public String getDescription() {
        return "Check whether the RAG store contains ingested papers. Call this AFTER a "
             + "literature-scout sub-agent finishes to verify ingestion actually worked before "
             + "appraising evidence. Returns POPULATED (with a chunk sample) or EMPTY.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "evaluation"; }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String probe = parameters.get("probe_query") instanceof String s && !s.isBlank()
                ? s : "study results methods findings";
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("query", probe);
            Object out = ragSearchTool.execute(args);
            String result = out == null ? "" : out.toString();

            boolean empty = result.isBlank()
                    || result.toLowerCase().contains("no results")
                    || result.toLowerCase().contains("no matching")
                    || result.toLowerCase().contains("empty")
                    || result.length() < 40;

            if (empty) {
                return "RAG STATUS: EMPTY — no chunks retrieved for probe query. "
                     + "Ingestion did NOT succeed. Re-spawn the literature-scout with a different "
                     + "strategy (try web_search for full-text PDFs, or different databases).";
            }
            String sample = result.length() > 400 ? result.substring(0, 400) + "…" : result;
            return "RAG STATUS: POPULATED — chunks are present. Sample:\n" + sample;
        } catch (Exception e) {
            log.warn("rag_status probe failed: {}", e.getMessage());
            return "RAG STATUS: ERROR — could not probe store: " + e.getMessage();
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("probe_query", Map.of("type", "string",
                "description", "Optional probe query (defaults to a generic term)."));
        schema.put("properties", props);
        schema.put("required", List.of());
        return schema;
    }
}
