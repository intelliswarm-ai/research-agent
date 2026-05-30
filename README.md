# Research Agent

A dynamic medical-research assistant built on the [SwarmAI](https://github.com/intelliswarm-ai/swarm-ai) framework. A clinician types a hypothesis; the agent plans, spawns specialist sub-agents, retrieves and appraises evidence, and produces a cited research report — all without a fixed pipeline.

## Current status

Branch `dynamic-planning` (active development). The static 5-stage CSV workflow has been replaced by a generative-planning engine. Quality on the Alzheimer amyloid-clearance benchmark has reached **9.2 / 10** over three eval iterations.

## Architecture

```
ResearchAgentApplication
  └── ResearchRepl  ←  JLine REPL (interactive) | BatchResearchRunner (headless eval)
        └── IntakeDialog  — PICO-structured intake wizard
              └── ConversationEngine  — orchestrator turn loop
                    ├── LlmClient  (gpt-4o-mini via Spring AI)
                    ├── ToolRouter  — permission gate
                    └── ResearchToolset
                          ├── todo_write          — living plan (anti-paralysis guard)
                          ├── subagent_spawn      — dynamic sub-agent core
                          ├── pubmed_search       — PubMed eSummary API
                          ├── arxiv_search        — arXiv API
                          ├── semantic_scholar_search
                          ├── openalex_search
                          ├── europepmc_fulltext  — OA full-text XML
                          ├── unpaywall_lookup    — DOI → legal OA PDF
                          ├── pdf_download        — browser UA + redirect follow
                          ├── rag_ingest          — Lucene-backed vector store
                          ├── rag_search          — hybrid keyword + vector retrieval
                          ├── rag_status          — deterministic ingest verification
                          ├── relevance_filter    — species / study-type gate
                          ├── citation_validate   — cross-checks PMIDs / arXiv / OpenAlex
                          └── report_write        — final markdown report
```

### Sub-agent personas

The orchestrator dynamically spawns any of three specialist sub-agents:

| Persona | Role |
|---|---|
| `literature-scout` | Searches PubMed / arXiv / etc., fetches full text, ingests into RAG |
| `evidence-appraiser` | Queries RAG, classifies passages `SUPPORTS / CONTRADICTS / NEUTRAL` |
| `synthesizer` | Writes structured report sections from evidence summaries |

Each sub-agent gets its own ephemeral message history and a restricted tool subset. The orchestrator can spawn any number in any order, adapting as evidence accumulates.

### Key quality safeguards

- **RelevanceGateTool** — rejects animal / in-vitro papers when human-clinical evidence is required (`RELEVANT / TANGENTIAL / REJECT` per paper).
- **RagStatusTool** — deterministically verifies ingestion succeeded before appraisal (prevents small models from hallucinating success).
- **CitationValidatorTool** — cross-checks PMIDs via PubMed eSummary, arXiv, and OpenAlex — confirms citations aren't fabricated.
- **Anti-paralysis guard** — ≥ 3 consecutive `todo_write`-only turns inject a "stop planning, spawn scout" nudge.
- **EuropePmcFullTextTool** — fetches real OA full text (`fullTextXML`) rather than PubMed HTML abstract pages.
- **UnpaywallTool** — DOI → legal OA PDF URL; combined with browser User-Agent + `Redirect.ALWAYS` in `ContentAwarePdfDownloadTool` to bypass 403/302 publisher blocks.

## Evaluation framework (`research-agent-eval`)

A companion Maven module drives automated quality measurement:

```bash
cd ../research-agent-eval

./run-eval.sh                                      # full benchmark suite
./run-eval.sh --hypothesis "my hypothesis"         # single run
./run-eval.sh --trend alzheimer-amyloid-clearance  # quality trend over time
./run-eval.sh --history                            # list all past runs
```

**QualityScorer** grades reports on 6 weighted dimensions:

| Dimension | Weight |
|---|---|
| Structure (required sections present) | 20 % |
| Evidence depth (labeled citations + verbatim quotes) | 25 % |
| Citation density | 20 % |
| Balance (supporting vs contradicting evidence) | 15 % |
| Verdict clarity | 10 % |
| Efficiency (tokens per insight) | 10 % |

The scorer also emits `DEFECT[...]` diagnostics: `planning-paralysis`, `relevance-gate-skipped`, `abstract-only`, `wrong-species-citation`, `verdict-inflation`, `zero-yield-search`, `no-report`.

**Quality trajectory on the Alzheimer amyloid-clearance hypothesis:**

| Iteration | Score |
|---|---|
| Baseline (static pipeline) | 0.6 / 10 |
| After dynamic planning + subagents | 6.8 / 10 |
| After relevance gate + full-text + anti-paralysis | 9.2 / 10 |

## Prerequisites

- **Java 21+** and **Maven**
- **OpenAI API key** (`OPENAI_API_KEY`) — chat model + embeddings
- Locally-built **SwarmAI** (`mvn install` from `../swarm-ai/` once)

### Optional env vars

| Variable | Purpose |
|---|---|
| `RESEARCH_AGENT_CHAT_MODEL` | Override chat model (default `gpt-4o-mini`) |
| `RESEARCH_AGENT_EMBED_MODEL` | Override embedding model (default `text-embedding-3-small`) |
| `OPENALEX_MAILTO` | Your email — opts into OpenAlex's higher-priority polite pool |
| `NCBI_API_KEY` | Lifts PubMed rate limit from 3 req/s to 10 req/s |

## Quick start

```bash
# Build SwarmAI once
cd ../swarm-ai && mvn -DskipTests install

# Run the interactive REPL
cd ../research-agent
cp .env.example .env          # add your OPENAI_API_KEY
./run.sh
```

The intake wizard asks PICO-structured questions, drafts a hypothesis, and hands off to the orchestrator. The final report is written to `output/research_report_<ts>.md`. Downloaded PDFs land in `papers/`. The vector index lives in `.research-agent-index/`.

## Module layout

```
research-agent/
├── pom.xml
├── run.sh
├── output/                   ← eval results + research reports
├── papers/                   ← downloaded PDFs
└── src/main/java/ai/intelliswarm/researchagent/
    ├── agent/                ← ConversationEngine, Session, ToolRouter, Prompts
    ├── cli/                  ← ResearchRepl (JLine), IntakeDialog
    ├── config/               ← ResearchProperties, ResearchConfiguration, DotenvLoader
    ├── eval/                 ← BatchResearchRunner, MetricsCollector, QualityScorer,
    │                            CitationValidatorTool, EvalResultWriter
    └── tool/                 ← SubagentSpawnTool, TodoWriteTool, ReportWriteTool,
                                 ContentAwarePdfDownloadTool, EuropePmcFullTextTool,
                                 UnpaywallTool, RelevanceGateTool, RagStatusTool,
                                 ResearchToolset, ResearchAgentToolsConfiguration

research-agent-eval/
├── pom.xml
├── run-eval.sh
└── src/main/java/ai/intelliswarm/researcheval/
    ├── EvaluationRunner      ← launches agent as subprocess, captures metrics
    ├── EvalRunStore          ← JSON history per hypothesis (trend tracking)
    ├── GoldenReference       ← compares vs gold-standard facts / PMIDs
    └── PromptSuggester       ← maps quality gaps → concrete prompt edits
```

## Known open issues

- **EuropePMC EOF** — some PMIDs return EOF on the fullTextXML endpoint; agent falls back to abstract-only for those papers.
- **Quote fidelity** — `gpt-4o-mini` sometimes paraphrases rather than quoting verbatim from RAG chunks. Next improvement: ground each report quote against actual ingested chunk text.
- **`subagent_spawn` tool routing** — sub-agents currently inherit the orchestrator's tool list; a scoped tool registry per persona would reduce noise in the sub-agent prompt.
