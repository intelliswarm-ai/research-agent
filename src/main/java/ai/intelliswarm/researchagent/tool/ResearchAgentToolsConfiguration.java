package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.researchagent.eval.CitationValidatorTool;
import ai.intelliswarm.swarmai.rag.tool.OpenAlexTool;
import ai.intelliswarm.swarmai.rag.tool.PdfDownloadTool;
import ai.intelliswarm.swarmai.rag.tool.PubMedTool;
import ai.intelliswarm.swarmai.rag.tool.RagIngestTool;
import ai.intelliswarm.swarmai.rag.tool.RagSearchTool;
import ai.intelliswarm.swarmai.rag.tool.SemanticScholarTool;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import ai.intelliswarm.swarmai.tool.research.ArxivTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool wiring for the dynamic research agent.
 *
 * <p>Assembles the {@link ResearchToolset} from all research-specific tool beans
 * (literature search, RAG, PDF download, plan tracking, report writing, sub-agent spawn).
 * The {@link ContentAwarePdfDownloadTool} override ensures PubMed HTML pages get the
 * correct file extension rather than being saved as {@code .pdf}.
 */
@Configuration
public class ResearchAgentToolsConfiguration {

    /**
     * Content-aware PDF download that detects actual file type.
     * Marked {@code @Primary} so it shadows swarmai-rag's default {@link PdfDownloadTool}.
     */
    @Bean
    @Primary
    public PdfDownloadTool contentAwarePdfDownloadTool(
            @Value("${swarmai.rag.papers-dir:./papers}") String papersDir) {
        ContentAwarePdfDownloadTool override = new ContentAwarePdfDownloadTool(
                Paths.get(papersDir).toAbsolutePath().normalize());
        return new DelegatingPdfDownloadTool(override);
    }

    /**
     * Assembles the full research toolset.
     *
     * <p>The {@link SubagentSpawnTool} is injected via {@link ObjectProvider} to break
     * the circular dependency (toolset → subagent → toolset).
     */
    @Bean
    public ResearchToolset researchToolset(
            // Literature search
            ArxivTool arxivTool,
            PubMedTool pubMedTool,
            SemanticScholarTool semanticScholarTool,
            OpenAlexTool openAlexTool,
            WebSearchTool webSearchTool,
            // RAG + PDF + full text
            PdfDownloadTool pdfDownloadTool,
            EuropePmcFullTextTool europePmcFullTextTool,
            UnpaywallTool unpaywallTool,
            RagIngestTool ragIngestTool,
            RagSearchTool ragSearchTool,
            // Planning + output
            TodoWriteTool todoWriteTool,
            ReportWriteTool reportWriteTool,
            // Evaluation: relevance gate + RAG ground-truth check + citation cross-validation
            RelevanceGateTool relevanceGateTool,
            RagStatusTool ragStatusTool,
            CitationValidatorTool citationValidatorTool,
            // Sub-agent spawning (lazy to avoid circular dep)
            ObjectProvider<SubagentSpawnTool> subagentProvider) {

        List<BaseTool> all = new ArrayList<>(List.of(
                arxivTool, pubMedTool, semanticScholarTool, openAlexTool, webSearchTool,
                pdfDownloadTool, europePmcFullTextTool, unpaywallTool, ragIngestTool, ragSearchTool,
                todoWriteTool, reportWriteTool,
                relevanceGateTool, ragStatusTool, citationValidatorTool));

        SubagentSpawnTool sub = subagentProvider.getIfAvailable();
        if (sub != null) all.add(sub);

        return new ResearchToolset(all);
    }

    // ── Inner shim ──────────────────────────────────────────────────────────

    /** Satisfies callers typed against the swarmai-rag {@link PdfDownloadTool} class. */
    static class DelegatingPdfDownloadTool extends PdfDownloadTool {
        private final ContentAwarePdfDownloadTool delegate;

        DelegatingPdfDownloadTool(ContentAwarePdfDownloadTool delegate) {
            super();
            this.delegate = delegate;
        }

        @Override public String getFunctionName()                            { return delegate.getFunctionName(); }
        @Override public String getDescription()                             { return delegate.getDescription(); }
        @Override public Object execute(java.util.Map<String, Object> p)    { return delegate.execute(p); }
        @Override public java.util.Map<String, Object> getParameterSchema() { return delegate.getParameterSchema(); }
        @Override public boolean isAsync()                                   { return delegate.isAsync(); }
        @Override public boolean isDynamic()                                 { return delegate.isDynamic(); }
        @Override public ai.intelliswarm.swarmai.tool.base.PermissionLevel getPermissionLevel() { return delegate.getPermissionLevel(); }
        @Override public String getCategory()                                { return delegate.getCategory(); }
        @Override public java.util.List<String> getTags()                   { return delegate.getTags(); }
        @Override public String getTriggerWhen()                             { return delegate.getTriggerWhen(); }
        @Override public String getAvoidWhen()                               { return delegate.getAvoidWhen(); }
    }
}
