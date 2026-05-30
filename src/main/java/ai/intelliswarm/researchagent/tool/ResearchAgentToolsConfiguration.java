package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.swarmai.rag.tool.PdfDownloadTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Paths;

/**
 * Overrides for tools shipped by swarmai-rag.
 *
 * <p>{@link ContentAwarePdfDownloadTool} replaces {@link PdfDownloadTool} so saved files get the
 * right extension based on actual content (PubMed URLs return HTML, not PDFs — the shipped tool
 * would save them as {@code .pdf} and confuse downstream consumers / users).
 */
@Configuration
public class ResearchAgentToolsConfiguration {

    @Bean
    @Primary
    public PdfDownloadTool contentAwarePdfDownloadTool(
            @Value("${swarmai.rag.papers-dir:./papers}") String papersDir) {
        // We expose the override under the swarmai-rag interface type so it satisfies the
        // @Autowired constructor parameter on ResearchAgentWorkflow.
        ContentAwarePdfDownloadTool override = new ContentAwarePdfDownloadTool(
                Paths.get(papersDir).toAbsolutePath().normalize());
        // The workflow uses the swarmai-rag type — we need a real PdfDownloadTool. The simplest
        // path is to subclass; but since the source for PdfDownloadTool isn't on our classpath as
        // source, we delegate at runtime via the BaseTool surface (which is what the framework
        // actually invokes) and just hand back the override cast.
        return new DelegatingPdfDownloadTool(override);
    }

    /** Tiny shim so the @Primary bean satisfies callers typed against the shipped class. */
    static final class DelegatingPdfDownloadTool extends PdfDownloadTool {
        private final ContentAwarePdfDownloadTool delegate;
        DelegatingPdfDownloadTool(ContentAwarePdfDownloadTool delegate) {
            super(); // base class no-arg ctor — defaultDir ./papers, default HttpClient
            this.delegate = delegate;
        }
        @Override public String getFunctionName() { return delegate.getFunctionName(); }
        @Override public String getDescription() { return delegate.getDescription(); }
        @Override public Object execute(java.util.Map<String, Object> parameters) { return delegate.execute(parameters); }
        @Override public java.util.Map<String, Object> getParameterSchema() { return delegate.getParameterSchema(); }
        @Override public boolean isAsync() { return delegate.isAsync(); }
        @Override public boolean isDynamic() { return delegate.isDynamic(); }
        @Override public ai.intelliswarm.swarmai.tool.base.PermissionLevel getPermissionLevel() { return delegate.getPermissionLevel(); }
        @Override public String getCategory() { return delegate.getCategory(); }
        @Override public java.util.List<String> getTags() { return delegate.getTags(); }
        @Override public String getTriggerWhen() { return delegate.getTriggerWhen(); }
        @Override public String getAvoidWhen() { return delegate.getAvoidWhen(); }
    }
}
