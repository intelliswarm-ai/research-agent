# Research Agent — Eval-Driven Improvement Log

Each iteration: run eval → extract DEFECT patterns → improve evaluator → improve agent → re-run.
The evaluator improvements make it detect new failure modes. The agent improvements fix them.

---

## Iteration 0 → 1: Planning Paralysis (Baseline)

**Eval result:** 0.6/10 — 50 todo_write calls, 0 subagents, 0 papers
**Root cause:** `consecutivePlanning` counter reset to 0 after each nudge → infinite loop

### Evaluator improvements
| What | Change |
|---|---|
| DEFECT[planning-paralysis] threshold | Lowered from ≥10 to ≥4 todo_write with 0 subagents |
| DEFECT[todo-write-cap-triggered] | New: fires when todo_write count > 3 (cap was hit) |
| DEFECT[no-verbatim-quotes] | Now **penalises scoreEvidence -3.0** (was issue-only) |
| DEFECT[insufficient-verbatim-quotes] | Now **penalises scoreEvidence -1.5** (was issue-only) |
| GoldenReference | Added 8 new domains: cognitive training, SGLT2, visceral fat, omega-3, etc. |
| PromptSuggester | Upgraded from score-threshold to **DEFECT-code → specific patch** mapping |

### Agent improvements
| What | Where | Change |
|---|---|---|
| Planning paralysis fix | `ConversationEngine.java` | `todoWritesSinceLastAction` counter: blocks todo_write after 3 consecutive calls; resets on any successful research action |
| Anti-paralysis nudge | `ConversationEngine.java` | Counter no longer resets → escalating pressure |
| Adversarial search mandate | `subagent-evidence-appraiser.md` | Must run ≥3 adversarial queries; must document query strings in Contradicting Evidence |
| Verbatim quote mandate | `subagent-evidence-appraiser.md` | Every SUPPORTS/CONTRADICTS entry must open with ≥20-char verbatim quote |
| Orchestrator cap note | `orchestrator.md` | Added consecutive-limit note; strengthened appraiser task instruction |

**Result after iteration 1:** 7.4/10 (+6.8 points) — planning paralysis eliminated

---

## Iteration 1 → 2: Single-Source Balance + Scout Count

**Eval result:** 7.4/10 — 4 papers, 1 scout, 1 relevant paper, same paper on both sides
**Key DEFECTs:**
- `DEFECT[balance-source-reuse-misleading]` (-3.5 on balance) — W2577650921 in both supporting AND contradicting
- `DEFECT[citation-validation-body-mismatch]` (-2.0 on citations) — body uses slug, References strips slug
- `DEFECT[verdict-no-quantitative-data]` (-2.0 on verdict) — INCONCLUSIVE with no numeric values
- `DEFECT[too-few-scouts]` — only 1 scout, too narrow corpus

### Evaluator improvements
| What | Change |
|---|---|
| DEFECT[too-few-scouts] | **New DEFECT**: fires when papersIngested>0 but scoutCount<3; -1.5 efficiency, -1.0 evidence |
| Pre-filter false positive | Fixed `rat` keyword: now requires whole-word `\b` boundary, preventing "ratio/rate/patient" matches |

### Agent improvements
| What | Where | Change |
|---|---|---|
| rat pre-filter false positive | `RelevanceGateTool.java` | Split into `ANIMAL_INVITRO_WHOLE_WORD` (word-boundary) and `ANIMAL_INVITRO_SUBSTRING` (phrase) sets |
| Single-source balance rule | `subagent-evidence-appraiser.md` | Added SINGLE-SOURCE RULE: if only 1 unique source, cannot populate BOTH sides |
| Overlap check in pre-verdict | `subagent-evidence-appraiser.md` | PRE-VERDICT CHECK item 8: list source IDs in SUPPORTS vs CONTRADICTS — overlap must be empty |
| Citation format consistency | `subagent-evidence-appraiser.md` | CITATION FORMAT CONSISTENCY: identical slug in body AND References |
| Quantitative verdict check | `subagent-evidence-appraiser.md` | Strengthened: scan ALL chunks including contradicting for p-values; even null-result p-values count |
| Minimum scout gate | `ConversationEngine.java` | Injects nudge when relevance_filter called before 3 scouts; tracks `scoutsSpawned` counter |
| Scout count tracking | `ConversationEngine.java` | Reads `type` arg from subagent_spawn to count literature-scouts |

### Expected improvements for iteration 2
- balance: 5.0 → 8+ (single-source rule prevents same-paper on both sides)
- citations: 7.5 → 9+ (citation format consistency)
- verdict: 4.0 → 7+ (quantitative data now required and better detected)
- more scouts → more papers → broader evidence

---

---

## Iteration 3 (gate loop / mandatory ingest): run_error — see Iteration 4

**What happened:** Scout ran only 2 turns (8 searches, 0 ingests). Mandatory ingest rule wasn't followed. Gate loop blocked report_write 8+ times.

### Evaluator improvements
| What | Change |
|---|---|
| DEFECT[scout-zero-ingest] | **New DEFECT**: scout ran searches but called rag_ingest 0 times — 2-turn early exit pattern |

### Agent improvements
| What | Where | Change |
|---|---|---|
| Mandatory ingest per scout | `subagent-literature-scout.md` | Added MANDATORY INGEST RULE + INGEST CHECK before report |
| Removed pre-ingest title filter | `subagent-literature-scout.md` | Was too strict — let relevance_filter screen papers |
| Appraiser spawn gate | `orchestrator.md` | Must check scout report for ingested count, not just rag_status |
| Gate-loop hard block | `ConversationEngine.java` | `reportWriteGateBlocks >= 5` → injects HARD-STOP with exact approved/rejected lists |
| Improved gate nudge | `ConversationEngine.java` | Uses `relevanceLedger.relevant()` and `.rejected()` instead of all-ingested list |

---

## Iteration 4 (mandatory ingest + gate block): 7.18/10

**Eval result:** 7.18/10 — 17 papers, 4 subagents, Structure=10, Evidence=10, run_error=False
**Key improvements:** mandatory ingest fix → 17 papers (vs 0 in iter 2-3)
**Key DEFECTs:**
- `DEFECT[verdict-direction-weak]`: INCONCLUSIVE with 4 supporting, 0 contradicting → wrong label
- `DEFECT[verdict-no-quantitative-data]`: no numeric values in verdict
- `DEFECT[citation-validation-body-mismatch]`: body IDs without slug, References with slug
- `DEFECT[context-overflow-risk]`: 263 tool calls (143 rag_search!) — near context limit
- `Evidence=10.0` is WRONG: paper titles in `"Title"` format counted as verbatim quotes (scorer bug)

### Evaluator improvements
| What | Change |
|---|---|
| DEFECT[no-verbatim-quotes] scorer bug | Fixed: paper titles in `"Title"` format no longer counted; requires evidence-sentence markers or ≥80 chars |
| citation-validation-body-mismatch | Fixed: normalize IDs by stripping slug before comparison (body vs References) |

### Agent improvements
| What | Where | Change |
|---|---|---|
| Verdict direction rule | `subagent-evidence-appraiser.md` | Decision tree: ≥2 supports + 0 contradicts → SUPPORTED; INCONCLUSIVE only when evidence is genuinely mixed |
| rag_search hard cap | `subagent-evidence-appraiser.md` | Added 12-query maximum (was 143 in iter 4!) |
| rag_search cap note | `subagent-evidence-appraiser.md` | Stop at 12 to avoid context overflow |

### Expected improvements for iteration 5
- verdict: 2.0 → 7+ (direction + quantitative data fixes)
- citations: 8.0 → 9+ (body-ref normalization fix)
- evidence: correct scoring (no false title-quote credits)
- efficiency: better (143 rag_search → capped at 12)

---

## Template for Future Iterations

```
## Iteration N → N+1: [Primary failure mode]

**Eval result:** X.Y/10 — [key metrics]
**Key DEFECTs:** [top 3-5 DEFECT codes]

### Evaluator improvements
| What | Change |

### Agent improvements
| What | Where | Change |

### Expected improvements
- [dimension]: X → Y
```

---

## Score History

| Iteration | Score | Papers | Scouts | Top DEFECT | Key Fix |
|---|---|---|---|---|---|
| 0 (baseline) | 0.6 | 0 | 0 | planning-paralysis (50 todo_write) | — |
| 1 | 7.4 | 4 | 1 | balance-source-reuse-misleading | planning paralysis fix |
| 2 | (running) | — | — | — | scout count + balance + citations |
