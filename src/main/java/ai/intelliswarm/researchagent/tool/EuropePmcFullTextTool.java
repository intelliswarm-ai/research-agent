package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches real OA full text from <a href="https://europepmc.org">Europe PMC</a>.
 *
 * <p>PubMed itself only serves abstract landing pages — it does not host PDFs. Europe PMC
 * mirrors PubMed and additionally serves the full text (as XML) for open-access articles.
 * This tool:
 * <ol>
 *   <li>Searches Europe PMC by PMID / DOI / free-text query.</li>
 *   <li>If the best match is open-access with a PMCID, downloads its {@code fullTextXML},
 *       strips the markup to plain text, and saves it to the papers directory.</li>
 *   <li>Otherwise saves the abstract text as a fallback.</li>
 * </ol>
 * Returns the saved path for {@code rag_ingest}, so downstream chunks contain real article
 * body text (methods, results) instead of navigation boilerplate.
 */
@Component
public class EuropePmcFullTextTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(EuropePmcFullTextTool.class);
    private static final String SEARCH = "https://www.ebi.ac.uk/europepmc/webservices/rest/search";
    private static final String REST   = "https://www.ebi.ac.uk/europepmc/webservices/rest/";

    // Fail fast: if Europe PMC is slow/unreachable, the scout should move on to
    // pdf_download rather than burning the whole turn budget on retries.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final Path papersDir;

    public EuropePmcFullTextTool(@Value("${swarmai.rag.papers-dir:./papers}") String papersDir) {
        this.papersDir = Paths.get(papersDir).toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "europepmc_fulltext"; }

    @Override
    public String getDescription() {
        return "Fetch the REAL open-access full text of an article from Europe PMC (preferred over "
             + "pdf_download for PubMed papers, which only have abstract pages). Provide a pmid, doi, "
             + "or a free-text query. Saves the full text (or abstract if no OA full text) to a file "
             + "and returns the path for rag_ingest, plus whether full text was available.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "rag"; }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String pmid  = str(parameters.get("pmid"));
        String doi   = str(parameters.get("doi"));
        String query = str(parameters.get("query"));

        String q;
        if (pmid != null && !pmid.isBlank())      q = "EXT_ID:" + pmid + " AND SRC:MED";
        else if (doi != null && !doi.isBlank())   q = "DOI:" + doi;
        else if (query != null && !query.isBlank()) q = query;
        else return "Error: provide one of 'pmid', 'doi', or 'query'.";

        try {
            String searchUrl = SEARCH + "?query=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                    + "&format=json&resultType=core&pageSize=1";
            String body = get(searchUrl);
            if (body == null) return "Error: Europe PMC search failed (network).";

            String foundPmid  = extract(body, "pmid");
            String pmcid      = extract(body, "pmcid");
            String title      = extract(body, "title");
            String isOa       = extract(body, "isOpenAccess");
            String abstractTx = extract(body, "abstractText");
            String resolvedDoi = extract(body, "doi");

            if (title == null && pmcid == null && abstractTx == null) {
                return "Europe PMC: no article found for " + q;
            }

            String label = foundPmid != null ? "pmid_" + foundPmid
                    : pmcid != null ? pmcid
                    : "epmc_" + Integer.toHexString(q.hashCode());

            String fullText = null;
            boolean gotFullText = false;
            if ("Y".equalsIgnoreCase(isOa) && pmcid != null && !pmcid.isBlank()) {
                String xml = get(REST + pmcid + "/fullTextXML");
                if (xml != null && xml.length() > 500) {
                    fullText = stripXml(xml);
                    gotFullText = fullText.length() > 800; // sanity: real body, not a stub
                }
            }

            String content = gotFullText ? fullText
                    : (abstractTx != null ? stripXml(abstractTx) : null);
            if (content == null || content.isBlank()) {
                return "Europe PMC: found '" + title + "' but no OA full text or abstract available "
                     + "(pmcid=" + pmcid + ", isOpenAccess=" + isOa + ").";
            }

            // Prepend a small header so citations stay traceable in the chunk text.
            StringBuilder doc = new StringBuilder();
            doc.append("TITLE: ").append(title == null ? "(unknown)" : title).append("\n");
            if (foundPmid != null)  doc.append("PMID: ").append(foundPmid).append("\n");
            if (resolvedDoi != null) doc.append("DOI: ").append(resolvedDoi).append("\n");
            doc.append("FULL_TEXT_AVAILABLE: ").append(gotFullText).append("\n\n");
            doc.append(content);

            Files.createDirectories(papersDir);
            Path out = papersDir.resolve(label + ".txt");
            Files.writeString(out, doc.toString(), StandardCharsets.UTF_8);

            log.info("EuropePMC: '{}' fullText={} -> {} ({} chars)",
                    title, gotFullText, out, content.length());

            return "**Europe PMC fetch complete**\n"
                 + "- title: " + title + "\n"
                 + "- pmid: " + foundPmid + "\n"
                 + "- full_text_available: " + gotFullText
                 + (gotFullText ? "" : " (abstract only — no OA full text)") + "\n"
                 + "- saved_to: " + out + "\n"
                 + "- chars: " + content.length() + "\n"
                 + "Use rag_ingest with path=" + out + " and a source label like "
                 + "pubmed:" + (foundPmid == null ? "ID" : foundPmid) + ":short-title";

        } catch (Exception e) {
            log.warn("EuropePMC fetch failed for {}: {}", q, e.getMessage());
            return "Error: Europe PMC fetch failed — " + e.getMessage();
        }
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "ResearchAgent/1.0 (thodoris.messinis@gmail.com)")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 ? resp.body() : null;
    }

    /** Strip XML/HTML tags and collapse whitespace. */
    private static String stripXml(String xml) {
        String text = xml.replaceAll("(?s)<ref-list.*?</ref-list>", " ")  // drop reference dumps
                         .replaceAll("(?s)<back.*?</back>", " ")
                         .replaceAll("(?s)<[^>]+>", " ")
                         .replaceAll("&[a-zA-Z]+;", " ")
                         .replaceAll("\\s+", " ")
                         .trim();
        // Cap to keep ingestion bounded (full papers can be huge).
        return text.length() > 60_000 ? text.substring(0, 60_000) : text;
    }

    private static String extract(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]{1,200000})\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pmid",  Map.of("type", "string", "description", "PubMed ID to fetch full text for."));
        props.put("doi",   Map.of("type", "string", "description", "DOI to fetch full text for."));
        props.put("query", Map.of("type", "string", "description", "Free-text query if no PMID/DOI."));
        schema.put("properties", props);
        schema.put("required", new String[]{});
        return schema;
    }
}
