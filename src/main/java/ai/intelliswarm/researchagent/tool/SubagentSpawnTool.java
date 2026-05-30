package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.researchagent.agent.PermissionPrompter;
import ai.intelliswarm.researchagent.agent.Prompts;
import ai.intelliswarm.researchagent.agent.ToolRouter;
import ai.intelliswarm.researchagent.config.ResearchProperties;
import ai.intelliswarm.swarmai.agent.llm.LlmClient;
import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRequest;
import ai.intelliswarm.swarmai.agent.llm.LlmResponse;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spawns an isolated research sub-agent in-process.
 *
 * <p>The sub-agent gets:
 * <ul>
 *   <li>Its own ephemeral message history (parent context not shared)</li>
 *   <li>A restricted tool subset (caller specifies which tools by name)</li>
 *   <li>A role-specific system prompt (persona)</li>
 * </ul>
 *
 * <p>This is the "dynamic" core that replaces the static 5-stage pipeline — the
 * orchestrator can spawn any number of sub-agents in any order, adapting as it
 * learns what the evidence says.
 *
 * <p>Default research personas:
 * <ul>
 *   <li>{@code literature-scout} — searches PubMed/Arxiv/etc., downloads and ingests PDFs</li>
 *   <li>{@code evidence-appraiser} — queries RAG, classifies passages SUPPORTS/CONTRADICTS/NEUTRAL</li>
 *   <li>{@code synthesizer} — writes structured report sections from evidence summaries</li>
 * </ul>
 */
@Component
public class SubagentSpawnTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(SubagentSpawnTool.class);
    private static final int DEFAULT_MAX_TURNS = 15;

    private final LlmClient llm;
    private final ObjectProvider<ResearchToolset> toolsetProvider;
    private final ResearchProperties props;
    private final ToolRouter parentRouter;

    private volatile Map<String, BaseTool> cachedToolsByName;

    @Autowired
    public SubagentSpawnTool(LlmClient llm,
                             ObjectProvider<ResearchToolset> toolsetProvider,
                             ResearchProperties props,
                             ToolRouter parentRouter) {
        this.llm = llm;
        this.toolsetProvider = toolsetProvider;
        this.props = props;
        this.parentRouter = parentRouter;
    }

    private Map<String, BaseTool> toolsByName() {
        Map<String, BaseTool> snapshot = cachedToolsByName;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (cachedToolsByName != null) return cachedToolsByName;
            Map<String, BaseTool> built = new HashMap<>();
            for (BaseTool t : toolsetProvider.getObject().tools()) {
                if (t instanceof SubagentSpawnTool) continue; // prevent nesting
                built.put(t.getFunctionName(), t);
            }
            cachedToolsByName = built;
            return built;
        }
    }

    @Override public String getFunctionName() { return "subagent_spawn"; }

    @Override
    public String getDescription() {
        return "Delegate a self-contained research task to a fresh sub-agent with its own history. "
             + "Use to parallelise or isolate investigation steps (search PubMed, appraise evidence, "
             + "synthesize a section). The sub-agent does NOT see the parent's conversation — make the "
             + "task fully self-contained. Returns the sub-agent's final report.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "orchestration"; }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }
    @Override public int getMaxResponseLength() { return 16_000; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String type = asString(parameters.get("type"), "literature-scout");
        String task = asString(parameters.get("task"), null);
        String persona = asString(parameters.get("persona"), defaultPersona(type));
        int maxTurns = parameters.get("max_turns") instanceof Number n
                ? Math.min(Math.max(1, n.intValue()), 30)
                : Math.min(DEFAULT_MAX_TURNS, props.getAgents().getSubagentMaxTurns());

        @SuppressWarnings("unchecked")
        List<String> requestedTools = parameters.get("tools") instanceof List<?> raw
                ? raw.stream().map(String::valueOf).toList()
                : defaultToolsFor(type);

        if (task == null || task.isBlank()) return "Error: 'task' is required";

        List<BaseTool> tools = new ArrayList<>();
        for (String name : requestedTools) {
            BaseTool t = toolsByName().get(name);
            if (t == null) {
                return "Error: unknown tool '" + name + "' — available: " + toolsByName().keySet();
            }
            tools.add(t);
        }

        log.info("Spawning sub-agent type='{}' max_turns={} tools={}", type, maxTurns, requestedTools);

        List<LlmMessage> history = new ArrayList<>();
        history.add(LlmMessage.user(task));

        int turns = 0;
        String finalText = "";
        long inputTokens = 0, outputTokens = 0;

        while (turns++ < maxTurns) {
            LlmRequest.Builder rb = LlmRequest.builder()
                    .model(props.getModel().getPrimary())
                    .system(persona)
                    .maxOutputTokens(props.getModel().getMaxOutputTokens())
                    .tools(tools);
            for (LlmMessage m : history) rb.message(m);

            LlmResponse resp;
            try {
                resp = llm.send(rb.build());
            } catch (Exception e) {
                log.warn("Sub-agent LLM call failed: {}", e.getMessage());
                return "Sub-agent failed: " + e.getMessage();
            }

            inputTokens += resp.tokenUsage().inputTokens();
            outputTokens += resp.tokenUsage().outputTokens();

            if (!resp.needsToolExecution()) {
                finalText = resp.text() == null ? "" : resp.text();
                break;
            }

            history.add(LlmMessage.assistantWithToolCalls(resp.text(), resp.toolCalls()));
            for (LlmToolCall call : resp.toolCalls()) {
                BaseTool tool = toolsByName().get(call.toolName());
                String result;
                if (tool == null) {
                    result = "Error: unknown tool '" + call.toolName() + "'";
                } else {
                    // Sub-agents inherit the parent's permission posture but never prompt the user.
                    Object out = parentRouter.executeWithRouting(tool, call.arguments(),
                            PermissionPrompter.allowAll());
                    result = out == null ? "" : out.toString();
                }
                history.add(LlmMessage.toolResult(call.id(), result));
            }
        }

        return "Sub-agent [" + type + "] finished in " + turns + " turn"
                + (turns == 1 ? "" : "s") + " (tokens: " + inputTokens + " in / "
                + outputTokens + " out)\n---\n" + finalText;
    }

    private static String defaultPersona(String type) {
        String loaded = Prompts.load("subagent-" + type + ".md", null);
        if (loaded != null) return loaded;
        return switch (type.toLowerCase()) {
            case "literature-scout" -> """
                    You are a medical literature scout. Search PubMed, Arxiv, Semantic Scholar, and OpenAlex
                    for papers relevant to the given topic. For each paper found:
                    1. Call pdf_download to retrieve the full text.
                    2. Call rag_ingest with a unique source label (format: source:PMID_or_ID:short-title).
                    Ingest up to 5 papers. Never fabricate PMIDs, DOIs, or URLs — copy them verbatim from
                    tool results. If a download fails, skip it and move on. Report all successfully ingested
                    source labels.""";
            case "evidence-appraiser" -> """
                    You are an evidence appraiser. Query the RAG store with rag_search using 2-4 different
                    phrasings of the hypothesis. For each retrieved passage, classify it as:
                    SUPPORTS / CONTRADICTS / NEUTRAL — and quote the key sentence. Always cite the source label.
                    Conclude with an overall verdict: SUPPORTED / CONTRADICTED / INCONCLUSIVE, and a one-sentence
                    justification. If evidence is insufficient, say INCONCLUSIVE — do not overstate.""";
            case "synthesizer" -> """
                    You are a research synthesizer. Given a set of evidence summaries, write a structured
                    section of a research report in markdown. Include: the hypothesis, supporting evidence
                    (with verbatim quotes and source citations), contradicting evidence (same format),
                    and a verdict paragraph. Write for a skeptical scientific audience. Every claim needs a
                    citation.""";
            default -> "You are a research sub-agent. Complete the assigned task using the supplied tools "
                     + "and report your findings concisely with citations.";
        };
    }

    private static List<String> defaultToolsFor(String type) {
        return switch (type.toLowerCase()) {
            case "literature-scout" -> List.of("pubmed_search", "arxiv_search",
                    "semantic_scholar_search", "openalex_search", "web_search",
                    "pdf_download", "rag_ingest");
            case "evidence-appraiser" -> List.of("rag_search");
            case "synthesizer" -> List.of("rag_search");
            default -> List.of("rag_search");
        };
    }

    private static String asString(Object v, String dflt) {
        return v == null ? dflt : String.valueOf(v);
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", Map.of("type", "string",
                "description", "Sub-agent role: 'literature-scout', 'evidence-appraiser', 'synthesizer', "
                             + "or any custom label. Selects the default persona and tool set.",
                "default", "literature-scout"));
        props.put("task", Map.of("type", "string",
                "description", "Self-contained task description. The sub-agent does NOT see the parent's "
                             + "conversation — include all necessary context here."));
        props.put("persona", Map.of("type", "string",
                "description", "Optional custom system prompt overriding the type default."));
        props.put("tools", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "Tool names the sub-agent may use. Defaults to role-appropriate tools."));
        props.put("max_turns", Map.of("type", "integer",
                "description", "Max reasoning turns (default 15, cap 30)."));
        schema.put("properties", props);
        schema.put("required", new String[]{"task"});
        return schema;
    }
}
