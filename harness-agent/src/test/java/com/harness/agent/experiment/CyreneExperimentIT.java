package com.harness.agent.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.agent.context.ContextBuilder;
import com.harness.agent.context.AgentPromptBuilder;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.knowledge.KnowledgeDiscoveryRouter;
import com.harness.agent.knowledge.KnowledgeReadTool;
import com.harness.agent.knowledge.KnowledgeSearchTool;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.knowledge.KnowledgeHandleCodec;
import com.harness.core.knowledge.KnowledgeIndexTaskStatus;
import com.harness.core.model.*;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.core.modelconfig.ModelConfigFile;
import com.harness.core.modelconfig.ModelConfigInitializer;
import com.harness.core.security.RequestPrincipal;
import com.harness.input.auth.JwtUtil;
import com.harness.tool.HttpApiTool;
import com.harness.tool.Tool;
import com.harness.tool.knowledge.IngestResult;
import com.harness.tool.knowledge.KnowledgeIngestService;
import com.harness.tool.knowledge.authority.ContentAddressedArtifactStorage;
import com.harness.tool.knowledge.authority.MysqlKnowledgeArtifactRepository;
import com.harness.tool.knowledge.authority.MysqlKnowledgeIndexOutboxStore;
import com.harness.tool.knowledge.authority.MysqlKnowledgeIngestJobStore;
import com.harness.tool.rerank.Reranker;
import com.harness.tool.discovery.OpenApiSpecParser;
import com.harness.tool.ToolExecutor;
import com.harness.trace.TraceCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit opt-in runner. Normal tests never connect to infrastructure or models. */
class CyreneExperimentIT {
    private static final String TENANT = "fixtureTenant";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<String> secrets = new ArrayList<>();

    static AgentContext trialContext(RequestPrincipal principal, String fixtureToken, String collection, Set<String> denied, ThinkingLevel thinking) {
        return new AgentContext(Map.of(AgentContext.KEY_CREDENTIALS, Map.of("fixtureToken", fixtureToken),
                AgentContext.KEY_NEEDS_KNOWLEDGE_BASE, true, AgentContext.KEY_NEEDS_WEB_SEARCH, false,
                AgentContext.KEY_THINKING_LEVEL, thinking,
                AgentContext.KEY_KNOWLEDGE_REQUEST_CONTEXT, Map.of("collection", collection, "allowedDocumentIds", Set.of())),
                principal).withToolDenylist(denied);
    }

    static AgentResult runCyreneTrial(AgentOrchestrator agent, String auth, String prompt, String sessionId,
            ThinkingLevel thinking, String owner, AgentContext context) {
        var previousCredentials = HttpApiTool.getCurrentCredentialsSnapshot();
        HttpApiTool.setCurrentCredentials(context.credentials());
        try {
            return agent.run(auth, prompt, List.of(), sessionId, null, new CancellationToken(), thinking, owner, context);
        } finally {
            if (previousCredentials.isEmpty()) HttpApiTool.clearCurrentCredentials();
            else HttpApiTool.setCurrentCredentials(previousCredentials);
        }
    }

    static AgentContext businessTrialContext(RequestPrincipal principal, String fixtureToken, String collection, Set<String> denied, ThinkingLevel thinking) {
        return new AgentContext(Map.of(AgentContext.KEY_CREDENTIALS, Map.of("fixtureToken", fixtureToken),
                AgentContext.KEY_NEEDS_KNOWLEDGE_BASE, false, AgentContext.KEY_NEEDS_WEB_SEARCH, false,
                AgentContext.KEY_THINKING_LEVEL, thinking), principal).withToolDenylist(denied);
    }

    static void requireModelEntry(JsonNode row) {
        if ("error".equals(row.path("status").asText())
                && row.path("trace").path("metadata").path("llmUsageCallCount").asInt(0) == 0) {
            throw new IllegalStateException("Stopped after recorded runtime error without model usage: "
                    + row.path("condition").asText() + "/" + row.path("taskId").asText()
                    + ": " + row.path("error").asText());
        }
    }

    static List<String> initializeCopiedModel(Path path) throws Exception {
        var file = new ModelConfigFile(path);
        var before = file.read().values();
        Set<String> existingKeys = Files.readAllLines(path).stream().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(line -> line.substring(0, line.indexOf('=')).strip()).collect(Collectors.toSet());
        List<String> addedKeys = ModelConfigKey.DEFINITIONS.stream()
                .filter(definition -> !definition.hidden() && !existingKeys.contains(definition.key()))
                .map(definition -> definition.key()).toList();
        ModelConfigInitializer.initializeIfMissing(file, Map.of());
        if (!before.equals(file.read().values())) throw new IllegalStateException("Copied model startup normalization changed an effective setting");
        return addedKeys;
    }

    JsonNode preflight(String fixture, String adminToken, String benchmarkDate) throws Exception {
        long started = System.currentTimeMillis();
        JsonNode health = request(fixture + "/health", adminToken, "GET");
        if (!"ok".equals(health.path("status").asText())
                || !"cyrene-synthetic-crm-v1".equals(health.path("fixture").asText())
                || !benchmarkDate.equals(health.path("benchmarkDate").asText())) {
            throw new IllegalStateException("Fixture GET " + fixture + "/health failed fixture marker/date validation");
        }
        JsonNode state = request(fixture + "/_experiment/state", adminToken, "GET");
        if (!benchmarkDate.equals(state.path("benchmarkDate").asText())
                || !state.path("customers").isArray() || !state.path("tasks").isArray()) {
            throw new IllegalStateException("Fixture GET " + fixture + "/_experiment/state returned an invalid state contract");
        }
        return mapper.createObjectNode().put("fixtureUrl", fixture).put("durationMs", System.currentTimeMillis() - started)
                .put("benchmarkDate", benchmarkDate).put("customerCount", state.path("customers").size())
                .put("taskCount", state.path("tasks").size()).set("health", health);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.HOURS)
    void runIsolatedExperiments() throws Exception {
        String configPath = System.getProperty("cyrene.experiment.config");
        assumeTrue(configPath != null && !configPath.isBlank(), "Set cyrene.experiment.config for isolated experiments");
        JsonNode config = mapper.readTree(Path.of(configPath).toFile());
        Path output = Path.of(required(config, "outputDirectory")).toAbsolutePath().normalize();
        Files.createDirectories(output);
        Path rows = output.resolve("raw.jsonl");
        if (Files.exists(rows)) throw new IllegalStateException("raw.jsonl already exists; use a new outputDirectory");
        JsonNode tokens = mapper.readTree(Path.of(required(config, "fixtureTokensPath")).toFile());
        for (String name : List.of("ownerAlpha", "ownerBeta", "admin")) secrets.add(required(tokens, name));
        String fixture = required(config, "fixtureUrl").replaceAll("/$", "");
        requireLoopback(fixture, "fixtureUrl");
        Map<String, String> overrides = runtimeOverrides(config, Path.of(configPath).toRealPath().getParent());
        EnvConfig.init(overrides);
        String previousHttpFactory = System.getProperty("langchain4j.http.clientBuilderFactory");
        System.setProperty("langchain4j.http.clientBuilderFactory",
                com.harness.provider.impl.CancellableHttpClient.Factory.class.getName());
        Path modelPath = Path.of(required(config, "modelConfigPath")).toAbsolutePath().normalize();
        byte[] inputModelDigest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(modelPath));
        List<String> addedModelKeys = initializeCopiedModel(modelPath);
        byte[] modelDigest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(modelPath));
        AgentOrchestrator agent = null;
        int trials = 0;
        int failures = 0;
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        var rootLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var httpLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("dev.langchain4j.http.client.log.LoggingHttpClient");
        var previousRootLevel = rootLogger.getLevel();
        var previousHttpLevel = httpLogger.getLevel();
        rootLogger.setLevel(ch.qos.logback.classic.Level.WARN);
        httpLogger.setLevel(ch.qos.logback.classic.Level.OFF);
        long setupStarted = System.currentTimeMillis();
        try {
            JsonNode taskFile = mapper.readTree(Path.of(required(config, "taskFilePath")).toFile());
            if (taskFile.path("schemaVersion").asInt() != 1 || !taskFile.path("tasks").isArray()) {
                throw new IllegalArgumentException("Task file requires schemaVersion=1 and tasks array");
            }
            int repetitions = positive(config, "repetitions", 1);
            int requestedTasks = nonnegative(config, "maxTasks", 0);
            int maxTasks = requestedTasks == 0 ? taskFile.path("tasks").size() : requestedTasks;
            List<String> configuredConditions = conditions(config);
            boolean businessOnly = configuredConditions.equals(List.of("cyrene_business"));
            ThinkingLevel thinking = ThinkingLevel.OFF;
            int maxRounds = EnvConfig.get().getInt(EnvKey.REACT_MAX_ITERATIONS, 10);
            write(output.resolve("preflight.json"), preflight(fixture, required(tokens, "admin"), required(taskFile, "benchmarkDate")));
            long discoveryStarted = System.currentTimeMillis();
            Path openApiFile = output.resolve("fixture-openapi.json");
            write(openApiFile, request(fixture + "/openapi.json", required(tokens, "admin"), "GET"));
            ProjectApiConfig importedApis = new OpenApiSpecParser().parse(openApiFile, openApiFile.getParent());
            long discoveryDurationMs = System.currentTimeMillis() - discoveryStarted;
            agent = new AgentOrchestrator();
            agent.currentConfiguration().values().forEach((key, value) -> {
                if (key.toLowerCase(Locale.ROOT).contains("apikey")) secrets.add(value);
            });
            if (!businessOnly) agent.knowledgeVectorRuntime().requireReady();
            List<IngestResult> corpus = businessOnly ? List.of() : ingestCorpus(agent, config);
            long catalogWaitMs = businessOnly ? 0 : awaitCatalog(corpus);
            Map<String, Tool> searchTools = businessOnly ? Map.of() : knowledgeTools(agent);
            Set<String> allowed = registerBusinessTools(agent, fixture, importedApis);
            if (!businessOnly) {
                allowed.add(KnowledgeSearchTool.TOOL_NAME);
                allowed.add(KnowledgeReadTool.TOOL_NAME);
            }
            Set<String> denied = agent.toolRegistry().getAll().stream().map(ToolSpec::name)
                    .filter(name -> !allowed.contains(name)).collect(Collectors.toUnmodifiableSet());
            agent.setToolDenylistResolver((tenant, identity) -> denied);
            JwtUtil jwt = new JwtUtil();
            var setup = mapper.createObjectNode();
            setup.put("schemaVersion", 1).put("startedAt", Instant.now().toString());
            setup.put("setupDurationMs", System.currentTimeMillis() - setupStarted).put("catalogWaitMs", catalogWaitMs);
            setup.put("automatedApiImportDurationMs", discoveryDurationMs);
            setup.put("apiIntegration", "Fetched fixture OpenAPI -> production OpenApiSpecParser -> reviewed allowlisted HttpApiTools; automatic import timing excludes human onboarding");
            setup.put("modelConfigSha256", HexFormat.of().formatHex(modelDigest));
            setup.put("copiedModelInputSha256", HexFormat.of().formatHex(inputModelDigest));
            setup.set("modelStartupAddedEmptyKeys", mapper.valueToTree(addedModelKeys));
            setup.put("modelNormalizationPolicy", "Production initializer completes blank visible keys before stability digest; existing effective values must remain identical; no source model.conf writes");
            setup.put("chatProvider", agent.chatModel().providerName()).put("chatModel", agent.chatModel().modelName());
            setup.put("embeddingProvider", agent.embeddingModel().providerName()).put("embeddingModel", agent.embeddingModel().modelName());
            setup.put("embeddingDimension", agent.embeddingModel().dimension()).put("rerankProvider", agent.rerankModel().providerName());
            setup.put("routingProvider", agent.currentConfiguration().getString(ModelConfigKey.ROUTING_PROVIDER, "none"));
            setup.put("routingPolicy", "Trusted context explicitly sets needsKnowledgeBase=true, needsWebSearch=false, thinkingLevel=off; existing GapAnalyzer Tier0 returns the complete explicit choice without calling the configured JEV provider; native loop uses the same choices; no routing benchmark");
            setup.put("comparisonPolicy", "Same corpus chunks, embedding, reranker, signed handles, authority checks, business tools and credentials; catalog selection enabled only in wiki_on; native loop shares Cyrene tools/security and raw source retrieval");
            setup.put("credentialBoundary", "Direct Cyrene invocation mirrors ChatHandler trusted credential injection into HttpApiTool; both runtimes restore the preceding credential scope in finally; credentials never enter tool arguments");
            setup.put("contaminationControls", "All groups force SOURCE_DOCUMENT; memory-save/update tools denied; long-term injected knowledge budget is zero; each run has a fresh session");
            setup.put("executionPolicy", "Current AgentOrchestrator/ReAct for wiki groups with fresh CancellationToken; LangChain4j AiServices for native loop; fresh authenticated session and transactional fixture reset per trial; condition order rotates per task/repetition; runtime errors with no recorded model usage stop after writing row/summary, correctness failures continue");
            setup.put("baselineLimit", "LangChain4j native tool loop with shared Cyrene tools/security; no independent Spring AI Alibaba or AgentScope deployment");
            if (businessOnly) {
                setup.put("comparisonPolicy", "Independent business comparison: current Cyrene OpenAPI importer and ReAct, five business tools only; external comparator recorded separately");
                setup.put("routingPolicy", "Trusted needsKnowledgeBase=false, needsWebSearch=false, thinkingLevel=off; complete Tier0 choice; no JEV calls");
                setup.put("baselineLimit", "Business-only task subset; no Wiki/RAG efficacy conclusion; framework prompt extensions retained");
                setup.put("setupTokenUsageAvailability", "No corpus ingestion in business-only mode");
            }
            setup.put("thinkingLevel", thinking.configValue()).put("maxToolCallingRounds", maxRounds);
            setup.put("modelSamplingPolicy", "All groups use current ChatModel with identical configured temperature/max output tokens and provider planningRequestParameters for explicit thinkingLevel=off");
            setup.set("conditions", mapper.valueToTree(configuredConditions));
            setup.put("setupTokenUsageAvailability", "Ingestion API does not expose model token usage; no inferred setup token or monetary cost");
            setup.put("evaluatorPolicy", "Answer text checks use NFKC, ignore Unicode whitespace, and enforce numeric/ASCII identifier boundaries; raw answers preserved; fixture JSON/state comparisons exact; no model/semantic judge");
            setup.put("repetitions", repetitions).put("taskCount", Math.min(maxTasks, taskFile.path("tasks").size()));
            setup.set("allowedTools", mapper.valueToTree(allowed.stream().sorted().toList()));
            setup.set("corpus", mapper.valueToTree(corpus));
            write(output.resolve("setup.json"), setup);
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                int taskIndex = 0;
                for (JsonNode task : taskFile.get("tasks")) {
                    if (taskIndex >= maxTasks) break;
                    List<String> trialConditions = new ArrayList<>(configuredConditions);
                    Collections.rotate(trialConditions, (taskIndex + repetition - 1) % trialConditions.size());
                    for (String condition : trialConditions) {
                        if (!businessOnly) agent.toolRegistry().replace(searchTools.get("bare_langchain4j".equals(condition) ? "wiki_off" : condition));
                        JsonNode row = runTrial(agent, jwt, tokens, fixture, taskFile,
                                task, condition, repetition, denied, thinking, maxRounds);
                        Files.writeString(rows, redact(mapper.writeValueAsString(row)) + System.lineSeparator(),
                                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        trials++;
                        boolean passed = row.path("passed").asBoolean();
                        if (!passed) failures++;
                        var count = counts.computeIfAbsent(condition, ignored -> new LinkedHashMap<>(Map.of("trials", 0, "passed", 0, "failed", 0)));
                        count.compute("trials", (key, value) -> value + 1);
                        count.compute(passed ? "passed" : "failed", (key, value) -> value + 1);
                        var summary = mapper.createObjectNode().put("schemaVersion", 1).put("trials", trials)
                                .put("failureCount", failures).put("allPassed", failures == 0);
                        summary.set("conditions", mapper.valueToTree(counts));
                        write(output.resolve("summary.json"), summary);
                        System.out.println("Experiment " + condition + " " + required(task, "id") + " passed=" + passed
                                + " durationMs=" + row.path("durationMs").asLong());
                        requireModelEntry(row);
                    }
                    taskIndex++;
                }
            }
            if (!MessageDigest.isEqual(modelDigest, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(modelPath)))) {
                throw new IllegalStateException("Copied model configuration changed after startup normalization");
            }
        } catch (Exception exception) {
            Path errorFile = output.resolve("runner-error.json");
            if (Files.exists(errorFile)) errorFile = output.resolve("runner-error-" + System.currentTimeMillis() + ".json");
            write(errorFile, mapper.createObjectNode()
                    .put("errorType", exception.getClass().getName()).put("error", redact(String.valueOf(exception.getMessage())))
                    .put("completedTrials", trials).put("failureCount", failures));
            throw exception;
        } finally {
            if (agent != null) agent.shutdown();
            com.harness.tool.rag.MilvusConnectionPool.shutdown();
            if (previousHttpFactory == null) System.clearProperty("langchain4j.http.clientBuilderFactory");
            else System.setProperty("langchain4j.http.clientBuilderFactory", previousHttpFactory);
            rootLogger.setLevel(previousRootLevel);
            httpLogger.setLevel(previousHttpLevel);
        }
        // Benchmark failures are data and stay in summary.json; setup/runtime failures fail this test.
    }

    private JsonNode runTrial(AgentOrchestrator agent, JwtUtil jwt, JsonNode tokens,
            String fixture, JsonNode taskFile, JsonNode task, String condition, int repetition, Set<String> denied,
            ThinkingLevel thinking, int maxRounds) throws Exception {
        var row = mapper.createObjectNode().put("schemaVersion", 1).put("condition", condition)
                .put("repetition", repetition).put("taskId", required(task, "id"))
                .put("category", required(task, "category")).put("prompt", required(task, "prompt"));
        String owner = task.has("principal") ? required(task, "principal") : required(taskFile, "defaultPrincipal");
        if (!Set.of("ownerAlpha", "ownerBeta").contains(owner)) throw new IllegalArgumentException("Task principal must be a fixture owner");
        row.put("principal", owner).put("startedAt", Instant.now().toString());
        request(fixture + "/_experiment/reset", required(tokens, "admin"), "POST");
        JsonNode before = request(fixture + "/_experiment/state", required(tokens, "admin"), "GET");
        String sessionId = agent.sessionStore().create(owner, TENANT).id();
        row.put("sessionId", sessionId);
        String auth = jwt.generateToken(owner, TENANT, "EXPERIMENT_OWNER");
        secrets.add(auth);
        var principal = jwt.principal(jwt.verifyTokenClaims(auth));
        var context = "cyrene_business".equals(condition)
                ? businessTrialContext(principal, required(tokens, owner), "", denied, thinking)
                : trialContext(principal, required(tokens, owner), EnvConfig.get().requireString(EnvKey.RAG_COLLECTION), denied, thinking);
        AgentTrace trace = null;
        String answer = "";
        List<String> diagnostics = new ArrayList<>();
        long started = System.currentTimeMillis();
        try {
            AgentResult result;
            if ("bare_langchain4j".equals(condition)) {
                var nativeTrace = new TraceCollector(agent.traceStore());
                nativeTrace.setSessionId(sessionId);
                var systemPrompt = new AgentPromptBuilder(agent.skillRegistry(), agent.artifactStorageService())
                        .buildSystemPrompt(null, sessionId, true, false, null, false);
                result = NativeLangChain4jExperiment.run(agent.chatModel(), agent.toolRegistry().snapshot().excluding(denied),
                        new ToolExecutor(agent.confirmationManager()), context, nativeTrace, systemPrompt,
                        required(task, "prompt"), thinking, maxRounds);
            } else {
                result = runCyreneTrial(agent, auth, required(task, "prompt"), sessionId, thinking, owner, context);
            }
            trace = result.trace();
            answer = result.output();
            if (result.requiresConfirmation()) diagnostics.add("run requires confirmation");
            row.put("status", result.requiresConfirmation() ? "confirmationRequired" : "completed");
        } catch (Exception exception) {
            row.put("status", "error").put("errorType", exception.getClass().getName())
                    .put("error", redact(String.valueOf(exception.getMessage())));
            diagnostics.add("runtime error: " + exception.getClass().getSimpleName());
            var tracePage = agent.traceStore().findBySession(sessionId, null, 1);
            if (!tracePage.items().isEmpty()) trace = tracePage.items().getFirst();
        }
        row.put("durationMs", System.currentTimeMillis() - started).put("answer", answer);
        JsonNode after = request(fixture + "/_experiment/state", required(tokens, "admin"), "GET");
        diagnostics.addAll(ExperimentEvaluation.validate(task.path("expectations"), answer, before, after));
        row.put("passed", diagnostics.isEmpty());
        row.set("validationDiagnostics", mapper.valueToTree(diagnostics));
        row.set("expectations", task.path("expectations"));
        row.set("initialFixtureState", before);
        row.set("finalFixtureState", after);
        if (trace != null) {
            row.put("traceId", trace.traceId()).put("traceTotalTokens", trace.totalTokens());
            row.set("totalTokens", mapper.valueToTree(ExperimentUsage.observedTotalTokens(trace)));
            row.set("trace", mapper.valueToTree(trace));
            row.set("toolCalls", mapper.valueToTree(trace.steps().stream().flatMap(step -> step.toolCalls().stream()).toList()));
            row.set("toolResults", mapper.valueToTree(trace.steps().stream().flatMap(step -> step.toolResults().stream()).toList()));
        } else {
            row.putNull("traceId").putNull("totalTokens");
        }
        return row;
    }

    private Map<String, Tool> knowledgeTools(AgentOrchestrator agent) {
        Clock clock = Clock.systemUTC();
        var access = new KnowledgeAccessService(new ContextBuilder(agent.vectorStore(), new Reranker(agent.rerankModel())),
                EnvConfig.get().getInt(EnvKey.RAG_CONTEXT_WINDOW_MAX, 10));
        var codec = new KnowledgeHandleCodec(mapper);
        var projections = agent.knowledgeVectorRuntime().projections();
        var search = new KnowledgeSearchTool(new KnowledgeDiscoveryRouter(agent.knowledgeRepository(), projections,
                agent.embeddingModel(), access, null, clock), codec, mapper);
        agent.toolRegistry().replace(new KnowledgeReadTool(agent.knowledgeRepository(), projections, codec, access, null, mapper, clock));
        var raw = new RawChunkKnowledgeSearch(search.spec(), agent.knowledgeRepository(), access, codec,
                mapper, clock, EnvConfig.get().requireString(EnvKey.RAG_COLLECTION));
        return Map.of("wiki_on", new SourceDocumentSearch(search), "wiki_off", new SourceDocumentSearch(raw));
    }

    private List<IngestResult> ingestCorpus(AgentOrchestrator agent, JsonNode config) throws Exception {
        var repository = agent.knowledgeRepository();
        var ingest = new KnowledgeIngestService(agent.embeddingModel(), agent.vectorStore(), agent.documentConversionService(),
                agent.documentSummarizer(), new ContentAddressedArtifactStorage(Path.of(EnvConfig.get().requireString(EnvKey.KNOWLEDGE_UPLOAD_DIR))),
                new MysqlKnowledgeArtifactRepository(), new MysqlKnowledgeIngestJobStore(repository), repository, agent.wikiIdentityResolver());
        List<IngestResult> results = new ArrayList<>();
        try (var files = Files.list(Path.of(required(config, "corpusDirectory")))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList()) {
                results.add(ingest.ingest(Files.readAllBytes(file), file.getFileName().toString(), "text/markdown",
                        EnvConfig.get().requireString(EnvKey.RAG_COLLECTION), null, TENANT));
            }
        }
        if (results.isEmpty()) throw new IllegalArgumentException("Corpus has no Markdown documents");
        return List.copyOf(results);
    }

    private long awaitCatalog(List<IngestResult> corpus) throws Exception {
        var store = new MysqlKnowledgeIndexOutboxStore();
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 120_000) {
            boolean complete = true;
            long cursor = 0;
            do {
                var page = store.findPageByConceptIds(corpus.stream().map(IngestResult::documentId).toList(), cursor, 100);
                if (page.items().isEmpty() && cursor == 0) complete = false;
                for (var task : page.items()) {
                    if (task.status() == KnowledgeIndexTaskStatus.FAILED) throw new IllegalStateException("Catalog projection failed: " + task.errorMessage());
                    complete &= task.status() == KnowledgeIndexTaskStatus.COMPLETED;
                }
                if (!page.pageInfo().hasMore()) break;
                cursor = page.items().getLast().id();
            } while (true);
            if (complete) return System.currentTimeMillis() - start;
            Thread.sleep(500);
        }
        throw new IllegalStateException("Catalog projection did not complete within 120 seconds");
    }

    private Set<String> registerBusinessTools(AgentOrchestrator agent, String baseUrl, ProjectApiConfig importedApis) {
        Set<String> expectedRoutes = Set.of("GET /customers", "GET /customers/{id}", "GET /tasks", "POST /tasks", "PUT /tasks/{id}");
        Set<String> registeredRoutes = new HashSet<>();
        Set<String> names = new LinkedHashSet<>();
        for (ApiEndpoint endpoint : importedApis.endpoints()) {
            String route = endpoint.method() + " " + endpoint.path();
            if (!expectedRoutes.contains(route)) continue;
            if (!registeredRoutes.add(route)) throw new IllegalArgumentException("Duplicate fixture OpenAPI route: " + route);
            var reviewed = new ApiEndpoint(endpoint.id(), endpoint.name(), endpoint.description(), endpoint.method(), endpoint.path(),
                    baseUrl, endpoint.source(), AuthMode.USER_PASSTHROUGH, "fixtureToken", new TokenInjection("header", "Authorization", "Bearer "),
                    endpoint.parameters(), true, false);
            var tool = new HttpApiTool(reviewed);
            agent.toolRegistry().register(tool);
            names.add(tool.spec().name());
        }
        if (!registeredRoutes.equals(expectedRoutes)) throw new IllegalArgumentException("Fixture OpenAPI is missing required business routes");
        return names;
    }

    private Map<String, String> runtimeOverrides(JsonNode config, Path privateRuntimeDirectory) throws Exception {
        if (!config.path("envOverrides").isObject()) throw new IllegalArgumentException("envOverrides must be an object");
        Map<String, String> overrides = new LinkedHashMap<>();
        config.get("envOverrides").fields().forEachRemaining(entry -> {
            if (!entry.getKey().startsWith("HARNESS_") || !entry.getValue().isTextual()) throw new IllegalArgumentException("envOverrides requires HARNESS_* string values");
            overrides.put(entry.getKey(), entry.getValue().textValue());
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.contains("pass") || key.contains("token") || key.contains("secret") || key.contains("api_key")) {
                if (!entry.getValue().textValue().isBlank()) secrets.add(entry.getValue().textValue());
            }
        });
        for (String key : List.of(EnvKey.AUDIT_DB_URL, EnvKey.AUDIT_DB_USER, EnvKey.AUDIT_DB_PASS, EnvKey.MEMORY_REDIS_URL,
                EnvKey.RAG_URL, EnvKey.RAG_API_KEY, EnvKey.RAG_DATABASE, EnvKey.RAG_COLLECTION, EnvKey.KNOWLEDGE_CATALOG_COLLECTION,
                EnvKey.MEMORY_USER_KNOWLEDGE_COLLECTION, EnvKey.MEMORY_OPERATION_KNOWLEDGE_COLLECTION,
                EnvKey.DOCUMENT_PARSER_URL, EnvKey.DOCUMENT_PARSER_TOKEN, EnvKey.ARTIFACT_DIR, EnvKey.KNOWLEDGE_UPLOAD_DIR,
                EnvKey.GRAPH_SCHEMA_DIR, EnvKey.MCP_CONFIG_FILE, EnvKey.SKILL_DIR)) {
            if (!overrides.containsKey(key)) throw new IllegalArgumentException("Missing explicit isolated override: " + key);
        }
        requireLoopback(overrides.get(EnvKey.AUDIT_DB_URL).replaceFirst("^jdbc:", ""), EnvKey.AUDIT_DB_URL);
        for (String key : List.of(EnvKey.MEMORY_REDIS_URL, EnvKey.RAG_URL, EnvKey.DOCUMENT_PARSER_URL)) requireLoopback(overrides.get(key), key);
        Path model = Path.of(required(config, "modelConfigPath")).toRealPath();
        if (!model.startsWith(privateRuntimeDirectory)) throw new IllegalArgumentException("Use a private copy of model.conf beside the runtime settings");
        overrides.put(EnvKey.CONFIG_MODEL_FILE, model.toString());
        overrides.put(EnvKey.CONFIG_MODEL_READ_GROUP, "");
        overrides.put(EnvKey.MEMORY_STORE, "mysql");
        overrides.put(EnvKey.AUDIT_STORE, "mysql");
        overrides.put(EnvKey.RAG_PROVIDER, "milvus");
        overrides.put(EnvKey.MEMORY_LONGTERM_BUDGET_RATIO, "0");
        overrides.put(EnvKey.GRAPH_PROVIDER, "none");
        overrides.put(EnvKey.PROJECT_DISCOVERY_ENABLED, "false");
        overrides.put(EnvKey.TOOL_WEB_SEARCH_ENABLED, "false");
        overrides.put(EnvKey.TOOL_URL_READER_ENABLED, "false");
        overrides.put(EnvKey.TOOL_BROWSER_ENABLED, "false");
        overrides.put(EnvKey.TOOL_FFMPEG_ENABLED, "false");
        overrides.put(EnvKey.SHELL_ENABLED, "false");
        overrides.put(EnvKey.RISK_CONFIRM_TOOLS, "");
        overrides.put(EnvKey.SYSTEM_PROMPT, "You are Cyrene, operating an isolated synthetic CRM experiment. Use only authorized tools and current evidence. Never invent business fields, IDs, dates, credentials or policy. The benchmark date is 2026-10-12. Ask for missing required information before writes. Explain access failures accurately. Reply in the user's language.");
        overrides.put(EnvKey.AUTH_MODE, "jwt");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String secret = Base64.getEncoder().encodeToString(key);
        secrets.add(secret);
        overrides.put(EnvKey.AUTH_JWT_SECRET, secret);
        overrides.put(EnvKey.AUTH_JWT_ISSUER, "cyrene-isolated-experiment");
        overrides.put(EnvKey.AUTH_JWT_AUDIENCE, "cyrene-isolated-experiment");
        overrides.put(EnvKey.MEMORY_REDIS_PASSWORD, overrides.getOrDefault(EnvKey.MEMORY_REDIS_PASSWORD, ""));
        overrides.put(EnvKey.MEMORY_REDIS_DB, overrides.getOrDefault(EnvKey.MEMORY_REDIS_DB, "0"));
        return overrides;
    }

    JsonNode request(String url, String token, String method) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, "POST".equals(method) ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response;
        try { response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (java.io.IOException exception) {
            throw new IllegalStateException("Fixture " + method + " " + url + " failed: " + exception.getClass().getSimpleName() + ": " + redact(String.valueOf(exception.getMessage())), exception);
        }
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Fixture " + method + " " + url + " failed with HTTP " + response.statusCode() + ": " + redact(response.body()));
        return mapper.readTree(response.body());
    }

    private void write(Path path, JsonNode value) throws Exception { Files.writeString(path, redact(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)), StandardCharsets.UTF_8); }
    private String redact(String value) {
        String redacted = value;
        for (String secret : secrets) if (secret != null && !secret.isBlank()) redacted = redacted.replace(secret, "[REDACTED]");
        return redacted;
    }
    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException(field + " must be a nonblank string");
        return value.textValue();
    }
    private static int positive(JsonNode node, String field, int defaultValue) {
        if (!node.has(field)) return defaultValue;
        if (!node.get(field).isIntegralNumber() || !node.get(field).canConvertToInt() || node.get(field).intValue() < 1) throw new IllegalArgumentException(field + " must be a positive integer");
        return node.get(field).intValue();
    }
    private static int nonnegative(JsonNode node, String field, int defaultValue) {
        if (!node.has(field)) return defaultValue;
        if (!node.get(field).isIntegralNumber() || !node.get(field).canConvertToInt() || node.get(field).intValue() < 0) throw new IllegalArgumentException(field + " must be a nonnegative integer");
        return node.get(field).intValue();
    }
    private static List<String> conditions(JsonNode config) {
        if (!config.has("conditions")) return List.of("wiki_on", "wiki_off", "bare_langchain4j");
        if (!config.get("conditions").isArray() || config.get("conditions").isEmpty()) throw new IllegalArgumentException("conditions must be a nonempty array");
        List<String> conditions = new ArrayList<>();
        for (JsonNode value : config.get("conditions")) {
            if (!value.isTextual() || !Set.of("wiki_on", "wiki_off", "bare_langchain4j", "cyrene_business").contains(value.textValue())
                    || conditions.contains(value.textValue())) throw new IllegalArgumentException("Unknown or duplicate experiment condition");
            conditions.add(value.textValue());
        }
        if (conditions.contains("cyrene_business") && conditions.size() != 1) throw new IllegalArgumentException("Business-only cannot be combined with knowledge conditions");
        return List.copyOf(conditions);
    }
    private static void requireLoopback(String value, String field) {
        URI uri = URI.create(value);
        if (!Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost()) || uri.getPort() <= 0) throw new IllegalArgumentException(field + " must use an explicit loopback host port");
    }
}
