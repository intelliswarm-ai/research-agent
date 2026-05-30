package ai.intelliswarm.researchagent.eval;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-checks source labels from the RAG against PubMed's eSummary API.
 *
 * <p>Source label format expected: {@code pubmed:38234567:short-title} or
 * {@code pmid:38234567:...}. Extracts the numeric ID and verifies it exists
 * in PubMed, returning the real title so the caller can spot fabricated IDs.
 *
 * <p>Also accepts arXiv IDs in format {@code arxiv:2401.12345:...}.
 */
@Component
public class CitationValidatorTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(CitationValidatorTool.class);
    private static final Pattern PMID_PATTERN = Pattern.compile("(?:pubmed|pmid)[:/](\\d{5,9})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARXIV_PATTERN = Pattern.compile("arxiv[:/](\\d{4}\\.\\d{4,5}(?:v\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENALEX_PATTERN = Pattern.compile("openalex[:/](W\\d{6,12})", Pattern.CASE_INSENSITIVE);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override public String getFunctionName() { return "citation_validate"; }

    @Override
    public String getDescription() {
        return "Cross-check source labels against PubMed (for PMIDs) and arXiv (for arXiv IDs). "
             + "Pass the list of source labels from rag_ingest calls. Returns validation status, "
             + "real titles, and flags any fabricated or incorrect IDs.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "evaluation"; }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Object sourcesParam = parameters.get("sources");
        List<String> sources = new ArrayList<>();

        if (sourcesParam instanceof List<?> list) {
            for (Object o : list) sources.add(String.valueOf(o));
        } else if (sourcesParam instanceof String s) {
            for (String part : s.split("[,\n]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) sources.add(trimmed);
            }
        }

        if (sources.isEmpty()) return "Error: 'sources' (list of source labels) is required.";

        StringBuilder report = new StringBuilder();
        report.append("## Citation Validation Report\n\n");

        int valid = 0, invalid = 0, notFound = 0, skipped = 0;

        for (String source : sources) {
            report.append("### `").append(source).append("`\n");

            Matcher pmidMatcher = PMID_PATTERN.matcher(source);
            Matcher arxivMatcher = ARXIV_PATTERN.matcher(source);
            Matcher openalexMatcher = OPENALEX_PATTERN.matcher(source);

            if (openalexMatcher.find()) {
                String workId = openalexMatcher.group(1);
                ValidationResult result = validateOpenAlex(workId);
                report.append("- **Type:** OpenAlex `").append(workId).append("`\n");
                report.append("- **Status:** ").append(result.status()).append("\n");
                if (result.title() != null) {
                    report.append("- **Real title:** ").append(result.title()).append("\n");
                }
                if (result.pubDate() != null) {
                    report.append("- **Published:** ").append(result.pubDate()).append("\n");
                }
                if (result.status().startsWith("VALID")) valid++;
                else { invalid++; if (result.status().startsWith("NOT_FOUND")) notFound++; }
            } else if (pmidMatcher.find()) {
                String pmid = pmidMatcher.group(1);
                ValidationResult result = validatePmid(pmid);
                report.append("- **Type:** PubMed PMID `").append(pmid).append("`\n");
                report.append("- **Status:** ").append(result.status()).append("\n");
                if (result.title() != null) {
                    report.append("- **Real title:** ").append(result.title()).append("\n");
                }
                if (result.authors() != null) {
                    report.append("- **Authors:** ").append(result.authors()).append("\n");
                }
                if (result.pubDate() != null) {
                    report.append("- **Published:** ").append(result.pubDate()).append("\n");
                }
                if (result.status().startsWith("VALID")) valid++;
                else if (result.status().startsWith("NOT_FOUND")) { invalid++; notFound++; }
                else invalid++;
            } else if (arxivMatcher.find()) {
                String arxivId = arxivMatcher.group(1);
                ValidationResult result = validateArxiv(arxivId);
                report.append("- **Type:** arXiv `").append(arxivId).append("`\n");
                report.append("- **Status:** ").append(result.status()).append("\n");
                if (result.title() != null) {
                    report.append("- **Real title:** ").append(result.title()).append("\n");
                }
                if (result.status().startsWith("VALID")) valid++;
                else invalid++;
            } else {
                report.append("- **Status:** SKIPPED — no recognizable PMID or arXiv ID in label\n");
                skipped++;
            }
            report.append("\n");
        }

        report.append("---\n");
        report.append("**Summary:** ")
              .append(valid).append(" valid, ")
              .append(invalid).append(" invalid (").append(notFound).append(" not found in PubMed), ")
              .append(skipped).append(" skipped\n");

        if (invalid > 0) {
            report.append("\n> ⚠️ **WARNING:** ").append(invalid)
                  .append(" source(s) could not be verified. These may be fabricated citations.\n");
        } else if (valid > 0) {
            report.append("\n> ✅ All validated sources confirmed in PubMed/arXiv.\n");
        }

        return report.toString();
    }

    private ValidationResult validatePmid(String pmid) {
        try {
            String url = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi"
                       + "?db=pubmed&id=" + pmid + "&retmode=json";
            String body = get(url);
            if (body == null) return new ValidationResult("ERROR — network failure", null, null, null);

            // Simple JSON parsing without a full library
            if (body.contains("\"error\"")) {
                return new ValidationResult("NOT_FOUND — PMID does not exist in PubMed", null, null, null);
            }
            if (!body.contains("\"" + pmid + "\"")) {
                return new ValidationResult("NOT_FOUND — PMID not returned by eSummary", null, null, null);
            }

            String title   = extractJson(body, "title");
            String source  = extractJson(body, "source");
            String pubDate = extractJson(body, "pubdate");
            // Authors: extract first author from AuthorList
            String authLine = extractJson(body, "name");

            return new ValidationResult("VALID — confirmed in PubMed", title,
                    authLine != null ? authLine + " et al." : null,
                    pubDate != null ? pubDate + (source != null ? " (" + source + ")" : "") : null);

        } catch (Exception e) {
            log.warn("PubMed validation failed for PMID {}: {}", pmid, e.getMessage());
            return new ValidationResult("ERROR — " + e.getMessage(), null, null, null);
        }
    }

    private ValidationResult validateOpenAlex(String workId) {
        try {
            String url = "https://api.openalex.org/works/" + workId
                       + "?mailto=thodoris.messinis@gmail.com";
            String body = get(url);
            if (body == null) return new ValidationResult("NOT_FOUND — OpenAlex work does not exist", null, null, null);
            if (body.contains("\"error\"") || !body.contains("\"id\"")) {
                return new ValidationResult("NOT_FOUND — OpenAlex work not returned", null, null, null);
            }
            String title   = extractJson(body, "title");
            String pubDate = extractJson(body, "publication_date");
            return new ValidationResult("VALID — confirmed in OpenAlex", title, null, pubDate);
        } catch (Exception e) {
            log.warn("OpenAlex validation failed for {}: {}", workId, e.getMessage());
            return new ValidationResult("ERROR — " + e.getMessage(), null, null, null);
        }
    }

    private ValidationResult validateArxiv(String arxivId) {
        try {
            String url = "https://export.arxiv.org/abs/" + arxivId;
            String body = get(url);
            if (body == null) return new ValidationResult("ERROR — network failure", null, null, null);
            if (body.contains("Error 404") || body.contains("not found")) {
                return new ValidationResult("NOT_FOUND — arXiv ID does not exist", null, null, null);
            }
            // Extract title from <title> tag (arXiv HTML)
            Pattern titlePattern = Pattern.compile("<title>\\[.*?\\]\\s*(.*?)</title>", Pattern.DOTALL);
            Matcher m = titlePattern.matcher(body);
            String title = m.find() ? m.group(1).trim().replaceAll("\\s+", " ") : null;
            return new ValidationResult("VALID — confirmed on arXiv", title, null, null);
        } catch (Exception e) {
            log.warn("arXiv validation failed for {}: {}", arxivId, e.getMessage());
            return new ValidationResult("ERROR — " + e.getMessage(), null, null, null);
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "ResearchAgent/1.0 (thodoris.messinis@gmail.com)")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 ? resp.body() : null;
    }

    /** Very naive JSON field extractor — avoids pulling in a JSON library. */
    private static String extractJson(String json, String field) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]{1,300})\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private record ValidationResult(String status, String title, String authors, String pubDate) {}

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sources", Map.of(
                "type", "array", "items", Map.of("type", "string"),
                "description", "List of source labels to validate (e.g. 'pubmed:38234567:amyloid-beta')."));
        schema.put("properties", props);
        schema.put("required", new String[]{"sources"});
        return schema;
    }
}
