package ai.intelliswarm.researchagent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the deterministic relevance-verdict injection that prevents the appraiser gate-loop:
 * every evidence-appraiser task must carry the approved whitelist + rejected blocklist, regardless
 * of whether the orchestrator LLM remembered to copy them.
 */
class SubagentSpawnInjectionTest {

    @Test
    @DisplayName("approved + rejected labels are injected into the appraiser task")
    void injectsWhitelistAndBlocklist() {
        String out = SubagentSpawnTool.buildRelevanceInjection(
                "Appraise the evidence for hypothesis X.",
                List.of("pubmed:111:a", "openalex:W222:b"),
                List.of("pubmed:999:rejected-animal"));
        assertTrue(out.contains("APPROVED SOURCE LABELS"), out);
        assertTrue(out.contains("pubmed:111:a"));
        assertTrue(out.contains("openalex:W222:b"));
        assertTrue(out.contains("REJECTED — DO NOT CITE"), out);
        assertTrue(out.contains("pubmed:999:rejected-animal"));
    }

    @Test
    @DisplayName("no screening yet -> task is unchanged")
    void noScreeningLeavesTaskAlone() {
        String task = "Appraise the evidence.";
        assertEquals(task, SubagentSpawnTool.buildRelevanceInjection(task, List.of(), List.of()));
    }

    @Test
    @DisplayName("empty approved but some rejected -> emits INSUFFICIENT-EVIDENCE guidance + blocklist")
    void emptyApprovedStillBlocklists() {
        String out = SubagentSpawnTool.buildRelevanceInjection(
                "Appraise.", List.of(), List.of("pubmed:999:rejected"));
        assertTrue(out.contains("none approved"), out);
        assertTrue(out.contains("INSUFFICIENT EVIDENCE"), out);
        assertTrue(out.contains("pubmed:999:rejected"));
    }

    @Test
    @DisplayName("idempotent: a task already carrying an approved block is left untouched")
    void idempotentWhenAlreadyPresent() {
        String task = "Appraise.\n\nAPPROVED SOURCE LABELS:\n- pubmed:111:a\n";
        // Even with different ledger contents, an existing block must not be double-injected.
        assertEquals(task, SubagentSpawnTool.buildRelevanceInjection(
                task, List.of("pubmed:222:other"), List.of("pubmed:333:rej")));
    }
}
