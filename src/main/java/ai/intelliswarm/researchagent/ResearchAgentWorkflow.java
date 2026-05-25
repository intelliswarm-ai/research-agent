package ai.intelliswarm.researchagent;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.rag.tool.OpenAlexTool;
import ai.intelliswarm.swarmai.rag.tool.PdfDownloadTool;
import ai.intelliswarm.swarmai.rag.tool.PubMedTool;
import ai.intelliswarm.swarmai.rag.tool.RagIngestTool;
import ai.intelliswarm.swarmai.rag.tool.RagSearchTool;
import ai.intelliswarm.swarmai.rag.tool.SemanticScholarTool;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.common.CSVAnalysisTool;
import ai.intelliswarm.swarmai.tool.common.FileReadTool;
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import ai.intelliswarm.swarmai.tool.research.ArxivTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Sequential 5-stage research workflow:
 *
 * <ol>
 *     <li><b>Data Profiler</b> — reads the CSV, produces a domain-aware data dictionary.</li>
 *     <li><b>Hypothesis Generator</b> — emits 3-5 testable research hypotheses tied to the data.</li>
 *     <li><b>Paper Discoverer</b> — for each hypothesis, searches arXiv / Semantic Scholar / OpenAlex /
 *         PubMed / web; downloads relevant PDFs; ingests them into the RAG store with citation labels.</li>
 *     <li><b>Evidence Evaluator</b> — queries the RAG store per hypothesis; classifies each retrieved
 *         passage as SUPPORTS / CONTRADICTS / NEUTRAL and cites the source paper.</li>
 *     <li><b>Report Writer</b> — synthesizes a markdown research report with verdicts and citations.</li>
 * </ol>
 */
@Component
public class ResearchAgentWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ResearchAgentWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    // Existing swarm-ai tools
    private final FileReadTool fileReadTool;
    private final CSVAnalysisTool csvAnalysisTool;
    private final ArxivTool arxivTool;
    private final WebSearchTool webSearchTool;

    // New swarmai-rag tools
    private final SemanticScholarTool semanticScholarTool;
    private final OpenAlexTool openAlexTool;
    private final PubMedTool pubMedTool;
    private final PdfDownloadTool pdfDownloadTool;
    private final RagIngestTool ragIngestTool;
    private final RagSearchTool ragSearchTool;

    public ResearchAgentWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            FileReadTool fileReadTool,
            CSVAnalysisTool csvAnalysisTool,
            ArxivTool arxivTool,
            WebSearchTool webSearchTool,
            SemanticScholarTool semanticScholarTool,
            OpenAlexTool openAlexTool,
            PubMedTool pubMedTool,
            PdfDownloadTool pdfDownloadTool,
            RagIngestTool ragIngestTool,
            RagSearchTool ragSearchTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.fileReadTool = fileReadTool;
        this.csvAnalysisTool = csvAnalysisTool;
        this.arxivTool = arxivTool;
        this.webSearchTool = webSearchTool;
        this.semanticScholarTool = semanticScholarTool;
        this.openAlexTool = openAlexTool;
        this.pubMedTool = pubMedTool;
        this.pdfDownloadTool = pdfDownloadTool;
        this.ragIngestTool = ragIngestTool;
        this.ragSearchTool = ragSearchTool;
    }

    public void run(String csvPath) {
        ChatClient chatClient = chatClientBuilder.build();

        // ============================================================
        // AGENTS
        // ============================================================

        Agent profiler = Agent.builder()
            .role("Senior Data Analyst")
            .goal("Profile the dataset at '" + csvPath + "' and describe what domain it belongs to. "
                + "Use file_read with limit=5 for a raw sample, then csv_analysis with operation='describe' "
                + "for an overview and operation='stats' for column statistics. Identify columns suitable for "
                + "comparison (numeric outcomes), grouping (categorical), and time (dates).")
            .backstory("You are a data analyst who reads a dataset before forming any opinion. You report the "
                + "actual schema, row count, null counts, and a handful of representative rows — never invented numbers.")
            .chatClient(chatClient)
            .tool(fileReadTool)
            .tool(csvAnalysisTool)
            .maxTurns(3)
            .permissionMode(PermissionLevel.READ_ONLY)
            .verbose(true)
            .temperature(0.1)
            .maxExecutionTime(180_000)
            .build();

        Agent hypothesizer = Agent.builder()
            .role("Research Hypothesizer")
            .goal("Propose 3 to 5 testable research hypotheses grounded in the data profile from the previous "
                + "task. Each hypothesis must reference the specific columns it depends on, name the kind of "
                + "study or analysis that would test it, and identify whether the relevant literature is more "
                + "likely in arXiv (CS/ML/physics), PubMed (biomedical), OpenAlex (broad), or Semantic Scholar.")
            .backstory("You are an interdisciplinary researcher. You frame hypotheses as falsifiable claims, "
                + "not opinions. You prefer narrow, specific hypotheses over sweeping ones.")
            .chatClient(chatClient)
            .maxTurns(1)
            .permissionMode(PermissionLevel.READ_ONLY)
            .verbose(true)
            .temperature(0.3)
            .maxExecutionTime(120_000)
            .build();

        Agent discoverer = Agent.builder()
            .role("Literature Discovery & Ingestion Specialist")
            .goal("For each hypothesis from the previous task, find 2-3 relevant papers and ingest them into "
                + "the RAG store. Workflow per hypothesis:\n"
                + "  1) Pick the right source(s) based on the hypothesis's discipline (arxiv_search for CS/ML, "
                + "     pubmed_search for biomedical, openalex_search for cross-field, semantic_scholar_search "
                + "     as a fallback). Use web_search only if the others return nothing.\n"
                + "  2) From each search result list, pick papers with a usable PDF link. For arXiv, the PDF "
                + "     link is on the result itself; for Semantic Scholar use the openAccessPdf URL; for "
                + "     OpenAlex use the open_access.oa_url; for PubMed look up DOI on a free-PDF site only if "
                + "     a direct PDF link is available.\n"
                + "  3) Call pdf_download with the PDF url to save it to ./papers/.\n"
                + "  4) Call rag_ingest with path=<saved_path> and source=<id>:<short-title> (e.g. "
                + "     source='arxiv:2401.12345:transformer-interpretability'). The source label MUST be "
                + "     unique per paper so it can be cited later.\n"
                + "Stop after ingesting at most 8 papers total to keep the run bounded. If a PDF link 404s or "
                + "ingestion fails, move on — do not retry the same URL.")
            .backstory("You are a meticulous literature scout. You never fabricate paper IDs or URLs — every "
                + "PDF you ingest came from a tool's response. You always copy the URL verbatim.")
            .chatClient(chatClient)
            .tool(arxivTool)
            .tool(semanticScholarTool)
            .tool(openAlexTool)
            .tool(pubMedTool)
            .tool(webSearchTool)
            .tool(pdfDownloadTool)
            .tool(ragIngestTool)
            .maxTurns(20)
            .permissionMode(PermissionLevel.WORKSPACE_WRITE)
            .verbose(true)
            .temperature(0.2)
            .maxExecutionTime(900_000) // 15 min — discovery + downloads can be slow
            .build();

        Agent evaluator = Agent.builder()
            .role("Evidence Evaluator")
            .goal("For each hypothesis, run 2-3 rag_search queries (paraphrase the hypothesis into different "
                + "query formulations) and classify each retrieved passage as one of: "
                + "SUPPORTS / CONTRADICTS / NEUTRAL. For each verdict, cite the chunk's 'source' field. "
                + "End with an overall verdict per hypothesis (SUPPORTED / CONTRADICTED / INCONCLUSIVE) and a "
                + "one-sentence justification.")
            .backstory("You are a careful reviewer who never overstates evidence. If the retrieved passages do "
                + "not address the hypothesis directly, say INCONCLUSIVE — do not strain interpretation. "
                + "Every claim you make must point to a specific source label.")
            .chatClient(chatClient)
            .tool(ragSearchTool)
            .maxTurns(15)
            .permissionMode(PermissionLevel.READ_ONLY)
            .verbose(true)
            .temperature(0.1)
            .maxExecutionTime(600_000)
            .build();

        Agent reporter = Agent.builder()
            .role("Research Report Writer")
            .goal("Write the final research report as your entire response. The report must contain, in this order:\n"
                + "  1. **Dataset summary** — one short paragraph based on the profile.\n"
                + "  2. **Hypotheses** — numbered list of the proposed hypotheses verbatim.\n"
                + "  3. **Literature ingested** — bulleted list of every source label that was added to the RAG.\n"
                + "  4. **Findings** — per-hypothesis section with: the hypothesis, the overall verdict, "
                + "     2-4 cited evidence quotes (each quote followed by its source label in parentheses).\n"
                + "  5. **Limitations** — what the evidence cannot decide, and what further work would help.\n"
                + "Write in markdown. Do not call any tools. Do not summarize — produce the full report.")
            .backstory("You are a senior reviewer who writes for skeptical readers. Every claim is linked to a "
                + "source. You never invent citations.")
            .chatClient(chatClient)
            .maxTurns(1)
            .permissionMode(PermissionLevel.READ_ONLY)
            .verbose(true)
            .temperature(0.2)
            .maxExecutionTime(300_000)
            .build();

        // ============================================================
        // TASKS
        // ============================================================

        Task profileTask = Task.builder()
            .description("Profile the dataset at '" + csvPath + "'. Required deliverables:\n"
                + "  - Data dictionary table (column, type, semantic role)\n"
                + "  - Row count, column count, null-counts table\n"
                + "  - 5 sample rows\n"
                + "  - 3-5 sentences naming the apparent domain of the data and what kinds of questions it could answer.")
            .expectedOutput("Markdown with data dictionary, shape, sample, and domain summary.")
            .agent(profiler)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180_000)
            .build();

        Task hypothesisTask = Task.builder()
            .description("Read the data profile from the previous task. Emit 3-5 testable hypotheses. "
                + "For each: label it H1/H2/..., state the claim in one sentence, name the columns it depends on, "
                + "describe the study design that would test it, and recommend a primary literature source "
                + "(arxiv / pubmed / openalex / semantic_scholar / web). Output as a numbered markdown list.")
            .expectedOutput("Numbered list of 3-5 hypotheses with discipline tags.")
            .agent(hypothesizer)
            .dependsOn(profileTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(120_000)
            .build();

        Task discoveryTask = Task.builder()
            .description("For each hypothesis from the previous task, locate and ingest 2-3 relevant papers. "
                + "Cap total ingestions at 8 to keep run time bounded. Report a markdown table with columns: "
                + "Hypothesis | Source Label | Title | URL ingested. The Source Label is the value you passed "
                + "to rag_ingest's `source` parameter — it MUST be unique per paper. If a download or ingest "
                + "fails for a given URL, do not retry; pick a different paper.")
            .expectedOutput("Markdown table of all successfully-ingested papers.")
            .agent(discoverer)
            .dependsOn(hypothesisTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(900_000)
            .build();

        Task evaluationTask = Task.builder()
            .description("For each hypothesis, run rag_search 2-3 times with paraphrased queries. For every "
                + "returned chunk, decide if it SUPPORTS, CONTRADICTS, or is NEUTRAL relative to the hypothesis "
                + "and cite the chunk's `source` value. End with one verdict per hypothesis "
                + "(SUPPORTED / CONTRADICTED / INCONCLUSIVE). Output structure:\n"
                + "  ### Hypothesis H1: <claim>\n"
                + "  - Query: <query string>\n"
                + "  - SUPPORTS [source]: <quoted snippet>\n"
                + "  - ...\n"
                + "  - Verdict: <SUPPORTED/CONTRADICTED/INCONCLUSIVE> — <one-sentence reason>\n")
            .expectedOutput("Per-hypothesis evidence list with verdicts and citations.")
            .agent(evaluator)
            .dependsOn(discoveryTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(600_000)
            .build();

        Task reportTask = Task.builder()
            .description("Produce the final research report as your entire response. Follow the 5-section "
                + "structure from your role goal exactly. Do not call any tools.")
            .expectedOutput("Complete markdown research report.")
            .agent(reporter)
            .dependsOn(evaluationTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .outputFile("output/research_report.md")
            .maxExecutionTime(300_000)
            .build();

        // ============================================================
        // SWARM
        // ============================================================

        Swarm swarm = Swarm.builder()
            .id("research-agent")
            .agent(profiler)
            .agent(hypothesizer)
            .agent(discoverer)
            .agent(evaluator)
            .agent(reporter)
            .task(profileTask)
            .task(hypothesisTask)
            .task(discoveryTask)
            .task(evaluationTask)
            .task(reportTask)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .eventPublisher(eventPublisher)
            .config("csvPath", csvPath)
            .build();

        logger.info("=".repeat(80));
        logger.info("RESEARCH AGENT WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("CSV: {}", csvPath);
        logger.info("Process: SEQUENTIAL (5 stages)");
        logger.info("=".repeat(80));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("csvPath", csvPath);

        long t0 = System.currentTimeMillis();
        SwarmOutput result = swarm.kickoff(inputs);
        long durationSec = (System.currentTimeMillis() - t0) / 1000;

        logger.info("");
        logger.info("=".repeat(80));
        logger.info("RESEARCH AGENT COMPLETE — {}s, {} tasks", durationSec, result.getTaskOutputs().size());
        logger.info("=".repeat(80));
        logger.info("\nFinal Report:\n{}", result.getFinalOutput());
    }
}
