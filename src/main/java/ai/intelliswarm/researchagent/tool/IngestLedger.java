package ai.intelliswarm.researchagent.tool;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped record of which papers were actually ingested into the RAG store.
 *
 * <p>Populated whenever a {@code rag_ingest} call succeeds (observed in the agent loop), keyed on
 * the stable {@code database:id} core of the source label. Used by the gates in {@link ReportWriteTool}
 * to enforce "ingestion really happened" and "every cited paper was actually ingested" — closing the
 * gap where a small model claims to have ingested papers it didn't.
 */
@Component
public class IngestLedger {

    private final Set<String> ingested = ConcurrentHashMap.newKeySet();

    public void record(String source) {
        String key = RelevanceLedger.normalize(source);
        if (!key.isBlank()) ingested.add(key);
    }

    public boolean contains(String source) { return ingested.contains(RelevanceLedger.normalize(source)); }

    public boolean isEmpty() { return ingested.isEmpty(); }

    public int size() { return ingested.size(); }

    public Set<String> all() { return Set.copyOf(ingested); }

    public void clear() { ingested.clear(); }
}
