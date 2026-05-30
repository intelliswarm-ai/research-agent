package ai.intelliswarm.researchagent.tool;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped record of relevance-gate verdicts, written by {@link RelevanceGateTool} and
 * read by {@link ReportWriteTool} to ENFORCE the gate deterministically.
 *
 * <p>The orchestrator (an LLM) might forget to call {@code relevance_filter}, or might try to cite
 * a rejected paper anyway. By keying verdicts on the stable {@code database:id} core of each source
 * label, {@code report_write} can refuse to write a report that cites unscreened or rejected papers
 * — so the relevance gate cannot be bypassed.
 */
@Component
public class RelevanceLedger {

    public enum Verdict { RELEVANT, TANGENTIAL, REJECT }

    private final Map<String, Verdict> verdicts = new ConcurrentHashMap<>();
    private volatile boolean ran = false;

    public void record(String source, Verdict v) {
        String key = normalize(source);
        if (!key.isBlank() && v != null) verdicts.put(key, v);
        ran = true;
    }

    public boolean hasRun() { return ran; }

    public Verdict verdictFor(String source) { return verdicts.get(normalize(source)); }

    public void clear() { verdicts.clear(); ran = false; }

    public int size() { return verdicts.size(); }

    /**
     * Reduce a source label to its stable identity: {@code database:id}.
     * e.g. {@code pubmed:41923852:comparative-energy-value-of-discarded-potato-chips} → {@code pubmed:41923852}.
     * Robust to differing short-title slugs between the screening call and the citation.
     */
    public static String normalize(String source) {
        if (source == null) return "";
        String s = source.trim().toLowerCase().replaceFirst("^source:\\s*", "");
        String[] parts = s.split(":");
        if (parts.length >= 2) return parts[0] + ":" + parts[1];
        return s;
    }
}
