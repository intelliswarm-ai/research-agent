package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drop-in replacement for swarmai-rag's {@code PdfDownloadTool} that picks the saved file's
 * extension from the <em>actual content</em> rather than the URL tail.
 *
 * <p>The shipped tool always appends {@code .pdf} when the URL has no extension, which leads to
 * {@code papers/42155670.pdf} actually containing an HTML PubMed landing page that no PDF viewer
 * can open. This implementation:
 *
 * <ol>
 *     <li>Downloads to a temp file under the configured papers directory.</li>
 *     <li>Sniffs the first 5 bytes plus the {@code Content-Type} response header.</li>
 *     <li>Renames the file with the right extension: {@code .pdf} for real PDFs,
 *         {@code .html} for HTML, {@code .txt} otherwise.</li>
 *     <li>Returns the final renamed path.</li>
 * </ol>
 *
 * <p>Registered as {@code @Primary} so it wins over the bean from {@code SwarmaiRagAutoConfiguration}
 * via {@code @ConditionalOnMissingBean}.
 */
public class ContentAwarePdfDownloadTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ContentAwarePdfDownloadTool.class);
    private static final long MAX_BYTES = 100L * 1024L * 1024L; // 100 MB
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final Path defaultDir;

    public ContentAwarePdfDownloadTool(Path defaultDir) {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build(),
            defaultDir);
    }

    public ContentAwarePdfDownloadTool(HttpClient httpClient, Path defaultDir) {
        this.httpClient = httpClient;
        this.defaultDir = defaultDir;
    }

    @Override public String getFunctionName() { return "pdf_download"; }

    @Override
    public String getDescription() {
        return "Download a document (PDF or web page) from an HTTP(S) URL. Detects whether the "
             + "downloaded bytes are an actual PDF, HTML, or plain text and saves with the matching "
             + "extension under the application's papers directory. Returns the saved absolute path "
             + "for use with rag_ingest. Pass ONLY the `url` parameter.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "rag"; }
    @Override public List<String> getTags() { return List.of("pdf", "download", "binary", "http"); }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String url = asString(parameters.get("url"));
        if (url == null || url.isBlank()) return "Error: 'url' is required";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Error: url must start with http:// or https://";
        }
        String filenameOverride = asString(parameters.get("filename"));

        try {
            Path dir = defaultDir.toAbsolutePath().normalize();
            Files.createDirectories(dir);

            // Stage to a temp filename — we only commit to the final extension after sniffing the bytes.
            String baseName = filenameOverride != null && !filenameOverride.isBlank()
                    ? stripExt(filenameOverride)
                    : stripExt(deriveBaseName(url));
            Path staging = dir.resolve(baseName + ".download");

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "SwarmAI-Rag/1.0 (+https://intelliswarm.ai)")
                    .header("Accept", "application/pdf,text/html;q=0.9,application/octet-stream;q=0.8,*/*;q=0.5")
                    .GET().build();

            HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(
                    staging, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));

            if (resp.statusCode() / 100 != 2) {
                Files.deleteIfExists(staging);
                return "Error: HTTP " + resp.statusCode() + " for " + url;
            }
            long size = Files.size(staging);
            if (size > MAX_BYTES) {
                Files.deleteIfExists(staging);
                return "Error: download exceeded max size (" + MAX_BYTES + " bytes)";
            }
            if (size == 0) {
                Files.deleteIfExists(staging);
                return "Error: downloaded file is empty";
            }

            String contentType = resp.headers().firstValue("content-type").orElse("");
            String ext = pickExtension(staging, contentType);
            Path target = dir.resolve(baseName + ext);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);

            logger.info("PdfDownload: {} -> {} ({} bytes, content-type={}, ext={})",
                    url, target, size, contentType.isBlank() ? "(unknown)" : contentType, ext);

            return buildResponse(url, target, size, contentType, ext);
        } catch (Exception e) {
            logger.error("PdfDownload failed for {}", url, e);
            return "Error: download failed — " + e.getMessage();
        }
    }

    /** Returns ".pdf" / ".html" / ".txt" based on file magic bytes first, content-type as a fallback. */
    private static String pickExtension(Path file, String contentType) throws IOException {
        byte[] head = readHeader(file, 256);
        // 1. Magic bytes (most reliable)
        if (head.length >= 5
                && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-') {
            return ".pdf";
        }
        String headStr = new String(head).toLowerCase();
        if (headStr.contains("<html") || headStr.contains("<!doctype html") || headStr.contains("<head") || headStr.contains("<body")) {
            return ".html";
        }
        // 2. Content-Type header
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.contains("application/pdf")) return ".pdf";
        if (ct.contains("text/html") || ct.contains("application/xhtml")) return ".html";
        if (ct.startsWith("text/")) return ".txt";
        // 3. Unknown — use .bin so it's obvious downstream
        return ".bin";
    }

    private static byte[] readHeader(Path path, int n) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[n];
            int read = in.read(buf);
            if (read <= 0) return new byte[0];
            if (read == n) return buf;
            byte[] out = new byte[read];
            System.arraycopy(buf, 0, out, 0, read);
            return out;
        }
    }

    private static String deriveBaseName(String url) {
        String tail = url;
        int q = tail.indexOf('?');
        if (q >= 0) tail = tail.substring(0, q);
        int slash = tail.lastIndexOf('/');
        if (slash >= 0 && slash < tail.length() - 1) tail = tail.substring(slash + 1);
        if (tail.isBlank()) tail = "download";
        return tail.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String buildResponse(String url, Path target, long size, String contentType, String ext) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Download complete**\n");
        sb.append("- url: ").append(url).append('\n');
        sb.append("- saved_to: ").append(target).append('\n');
        sb.append("- size: ").append(size).append(" bytes\n");
        sb.append("- content_type: ").append(contentType.isBlank() ? "(unknown)" : contentType).append('\n');
        sb.append("- detected_format: ").append(ext.substring(1).toUpperCase()).append('\n');
        return sb.toString();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("url", Map.of("type", "string", "description", "http(s) URL"));
        props.put("filename", Map.of("type", "string", "description", "Optional override filename base (extension is set from detected content type)"));
        schema.put("properties", props);
        schema.put("required", new String[]{"url"});
        return schema;
    }

    @Override public String getTriggerWhen() { return "User has a URL pointing to an article (PDF or HTML landing page) that should be saved locally before indexing."; }
    @Override public String getAvoidWhen() { return "User wants extracted text from a URL (use web_fetch) or a search engine query (use web_search)."; }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
}
