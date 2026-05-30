package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a DOI to a legal open-access full-text PDF URL via
 * <a href="https://unpaywall.org">Unpaywall</a> (free, ToS-friendly — unlike Google Scholar,
 * which has no API and blocks automation).
 *
 * <p>Given a DOI, returns the best OA PDF URL (if any) so the scout can {@code pdf_download} it.
 * This is the right way to "download the actual paper" for open-access articles.
 */
@Component
public class UnpaywallTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(UnpaywallTool.class);
    private static final String API = "https://api.unpaywall.org/v2/";
    private static final String EMAIL = "thodoris.messinis@gmail.com";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    @Override public String getFunctionName() { return "unpaywall_lookup"; }

    @Override
    public String getDescription() {
        return "Resolve a DOI to a legal open-access full-text PDF URL via Unpaywall. Use this when "
             + "a paper has a DOI but no obvious PDF link, to find a downloadable OA copy. Returns the "
             + "best OA PDF URL (pass it to pdf_download) or reports that no OA copy exists.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "rag"; }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String doi = parameters.get("doi") instanceof String s ? s.trim() : null;
        if (doi == null || doi.isBlank()) return "Error: 'doi' is required.";
        // Normalise: strip a leading https://doi.org/ if present
        doi = doi.replaceFirst("(?i)^https?://(dx\\.)?doi\\.org/", "");

        try {
            String url = API + URLEncoder.encode(doi, StandardCharsets.UTF_8) + "?email=" + EMAIL;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "ResearchAgent/1.0 (" + EMAIL + ")")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return "Unpaywall: DOI '" + doi + "' not found.";
            if (resp.statusCode() != 200) return "Unpaywall: HTTP " + resp.statusCode() + " for DOI " + doi;

            String body = resp.body();
            String isOa  = extract(body, "is_oa");
            String title = extract(body, "title");
            String pdfUrl = extractBestPdf(body);

            if (!"true".equalsIgnoreCase(isOa) || pdfUrl == null) {
                return "Unpaywall: no open-access PDF available for DOI " + doi
                     + " (is_oa=" + isOa + "). Title: " + title;
            }
            return "**Unpaywall OA copy found**\n"
                 + "- doi: " + doi + "\n"
                 + "- title: " + title + "\n"
                 + "- oa_pdf_url: " + pdfUrl + "\n"
                 + "Next: call pdf_download with url=" + pdfUrl;
        } catch (Exception e) {
            log.warn("Unpaywall lookup failed for {}: {}", doi, e.getMessage());
            return "Error: Unpaywall lookup failed — " + e.getMessage();
        }
    }

    /** Prefer best_oa_location.url_for_pdf; fall back to the first url_for_pdf in the doc. */
    private static String extractBestPdf(String json) {
        Matcher best = Pattern.compile("\"best_oa_location\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL).matcher(json);
        if (best.find()) {
            String pdf = extract(best.group(1), "url_for_pdf");
            if (pdf != null) return pdf;
        }
        return extract(json, "url_for_pdf");
    }

    private static String extract(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]{1,1000})\"").matcher(json);
        if (m.find()) return m.group(1);
        Matcher b = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(true|false)").matcher(json);
        return b.find() ? b.group(1) : null;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("doi", Map.of("type", "string", "description", "The DOI to resolve to an OA PDF (with or without the doi.org prefix)."));
        schema.put("properties", props);
        schema.put("required", new String[]{"doi"});
        return schema;
    }
}
