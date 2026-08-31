package com.harness.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.KnowledgeBaseTool;
import com.harness.agent.KnowledgeContextReadTool;
import com.harness.agent.KnowledgeGraphTool;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.core.concurrent.BlockingTaskExecutor;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import com.harness.core.model.ProjectApiConfig;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.retrieval.GraphKnowledgeRetriever;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.provider.ModelProviders;
import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import com.harness.tool.ToolRegistry;
import com.harness.tool.artifact.ArtifactStorageService;
import com.harness.tool.builtin.AudioTranscriptionTool;
import com.harness.tool.builtin.FfmpegTool;
import com.harness.tool.builtin.ImageGenerationTool;
import com.harness.tool.builtin.PythonSandboxTool;
import com.harness.tool.builtin.SpeechSynthesisTool;
import com.harness.tool.builtin.StructuredOutputTool;
import com.harness.tool.builtin.VideoGenerationTool;
import com.harness.tool.builtin.WebSearchTool;
import com.harness.tool.discovery.CodeGlobTool;
import com.harness.tool.discovery.CodeGrepTool;
import com.harness.tool.discovery.ReadClassHierarchyTool;
import com.harness.tool.mcp.McpServerConfig;
import com.harness.tool.mcp.McpToolDiscovery;
import com.harness.tool.skill.LoadSkillTool;
import com.harness.tool.skill.SkillRegistry;
import com.harness.tool.web.BrowserControlTool;
import com.harness.tool.web.ReadUrlContentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Owns the application-level tool registry and all static tool discovery.
 * Request-level authorization remains in immutable {@code RunToolCatalog} snapshots.
 */
public final class AgentToolRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentToolRuntime.class);

    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final ArtifactStore artifactStore;
    private final ArtifactStorageService artifactStorageService;
    private final VoiceModelProvider voiceProvider;

    public AgentToolRuntime(
            ModelProviders providers,
            GraphSettings graphSettings,
            GraphSchemaRegistry graphSchemaRegistry,
            KnowledgeGraphStore knowledgeGraphStore,
            GraphKnowledgeRetriever graphKnowledgeRetriever,
            GraphSpaceAccessService graphSpaceAccessService,
            ArtifactStore artifactStore,
            ArtifactStorageService artifactStorageService,
            ModelConfig modelConfig
    ) {
        this.toolRegistry = new ToolRegistry();
        this.skillRegistry = new SkillRegistry();
        this.artifactStore = java.util.Objects.requireNonNull(artifactStore, "artifactStore");
        this.artifactStorageService = java.util.Objects.requireNonNull(
                artifactStorageService, "artifactStorageService");
        this.voiceProvider = java.util.Objects.requireNonNull(
                providers.voice(), "voiceProvider");
        registerBuiltins(
                providers,
                graphSettings,
                graphSchemaRegistry,
                knowledgeGraphStore,
                graphKnowledgeRetriever,
                graphSpaceAccessService,
                artifactStore,
                artifactStorageService,
                modelConfig);
        registerMcpTools();
        initializeSkills();
        reloadProjectApiConfig();
    }

    public ToolRegistry tools() {
        return toolRegistry;
    }

    public SkillRegistry skills() {
        return skillRegistry;
    }

    public void reloadProjectApiConfig() {
        EnvConfig config = EnvConfig.get();
        if (!config.getBool(EnvKey.PROJECT_DISCOVERY_ENABLED, true)) {
            log.info("[Discovery] Project API discovery disabled");
            return;
        }
        String configPath = config.getString(EnvKey.PROJECT_APIS_CONFIG_FILE, "./project-apis.json");
        Path path = Path.of(configPath);
        if (!Files.exists(path)) {
            log.debug("[Discovery] No project-apis.json found at {}, skipping", configPath);
            return;
        }
        try {
            ProjectApiConfig projectConfig = new ObjectMapper().readValue(path.toFile(), ProjectApiConfig.class);
            toolRegistry.loadFromConfig(projectConfig);
            replaceDiscoveryTools(projectConfig.projectRoot());
            log.info("[Discovery] Loaded project APIs from {}: {} endpoints",
                    configPath, projectConfig.endpoints().size());
        } catch (Exception e) {
            log.error("[Discovery] Failed to load project-apis.json: {}", e.getMessage(), e);
        }
    }

    private void registerBuiltins(
            ModelProviders providers,
            GraphSettings graphSettings,
            GraphSchemaRegistry graphSchemaRegistry,
            KnowledgeGraphStore knowledgeGraphStore,
            GraphKnowledgeRetriever graphKnowledgeRetriever,
            GraphSpaceAccessService graphSpaceAccessService,
            ArtifactStore artifactStore,
            ArtifactStorageService artifactStorageService,
            ModelConfig modelConfig
    ) {
        EnvConfig config = EnvConfig.get();
        toolRegistry.register(StructuredOutputTool.chatBlock());
        if (config.getBool(EnvKey.TOOL_WEB_SEARCH_ENABLED, true)) {
            toolRegistry.register(new WebSearchTool());
        }
        if (config.getBool(EnvKey.TOOL_URL_READER_ENABLED, true)) {
            toolRegistry.register(new ReadUrlContentTool());
        }
        if (config.getBool(EnvKey.TOOL_BROWSER_ENABLED, false)) {
            toolRegistry.register(new BrowserControlTool());
        }

        String ragProvider = config.getString(EnvKey.RAG_PROVIDER, "pgvector");
        if (!"none".equalsIgnoreCase(ragProvider) && providers.embedding().isAvailable()) {
            KnowledgeAccessService knowledgeAccess = new KnowledgeAccessService(
                    providers.rerank(), providers.embedding());
            toolRegistry.register(new KnowledgeBaseTool(knowledgeAccess, providers.chat()));
            toolRegistry.register(new KnowledgeContextReadTool(knowledgeAccess));
        } else {
            log.info("Knowledge tools disabled (ragProvider={}, embedding={})",
                    ragProvider, providers.embedding().isAvailable());
        }

        if (!"none".equals(knowledgeGraphStore.providerName())) {
            toolRegistry.register(new KnowledgeGraphTool(
                    graphKnowledgeRetriever,
                    knowledgeGraphStore,
                    graphSpaceAccessService,
                    graphSchemaRegistry,
                    graphSettings,
                    new ObjectMapper()));
        } else {
            log.info("Knowledge graph tool disabled (provider=none)");
        }

        if (config.getBool(EnvKey.TOOL_FFMPEG_ENABLED, false)) {
            toolRegistry.register(new FfmpegTool());
        }
        toolRegistry.register(new PythonSandboxTool(
                (source, name, mimeType, sessionId) ->
                        artifactStorageService.storeFromPath(source, name, mimeType, sessionId),
                artifactStore::get));

        PreparedModelTools modelTools = prepareModelTools(modelConfig);
        modelTools.replacements().values().forEach(toolRegistry::register);

        registerDiscoveryTools(resolveConfiguredProjectRoot());
    }

    /** Build model-backed tools without publishing them to request snapshots. */
    public PreparedModelTools prepareModelTools(ModelConfig config) {
        Map<String, com.harness.tool.Tool> replacements = new LinkedHashMap<>();
        Set<String> removals = new java.util.HashSet<>();

        prepareImageTool(config, replacements, removals);
        prepareVideoTool(config, replacements, removals);
        prepareVoiceTools(config, voiceProvider, replacements, removals);
        return new PreparedModelTools(replacements, removals);
    }

    /** Build only tools whose effective configuration changes. */
    public PreparedModelTools prepareModelToolChanges(
            ModelConfig current,
            ModelConfig candidate,
            VoiceModelProvider candidateVoiceProvider
    ) {
        Map<String, com.harness.tool.Tool> replacements = new LinkedHashMap<>();
        Set<String> removals = new java.util.HashSet<>();
        if (!sameValues(current, candidate, List.of(
                ModelConfigKey.IMAGE_PROVIDER,
                ModelConfigKey.IMAGE_API_KEY,
                ModelConfigKey.IMAGE_BASE_URL,
                ModelConfigKey.IMAGE_MODEL,
                ModelConfigKey.CHAT_TIMEOUT_SECONDS))) {
            prepareImageTool(candidate, replacements, removals);
        }
        if (!sameValues(current, candidate, List.of(
                ModelConfigKey.VIDEO_PROVIDER,
                ModelConfigKey.VIDEO_API_KEY,
                ModelConfigKey.VIDEO_BASE_URL,
                ModelConfigKey.VIDEO_MODEL,
                ModelConfigKey.VIDEO_SUBMIT_PATH,
                ModelConfigKey.VIDEO_STATUS_PATH,
                ModelConfigKey.CHAT_TIMEOUT_SECONDS))) {
            prepareVideoTool(candidate, replacements, removals);
        }
        if (!sameValues(current, candidate, List.of(
                ModelConfigKey.VOICE_PROVIDER,
                ModelConfigKey.VOICE_API_KEY,
                ModelConfigKey.VOICE_BASE_URL,
                ModelConfigKey.VOICE_ASR_MODEL,
                ModelConfigKey.VOICE_TTS_MODEL,
                ModelConfigKey.VOICE_TIMEOUT_SECONDS,
                ModelConfigKey.VOICE_ASR_MAX_SIZE_MB,
                ModelConfigKey.VOICE_DEFAULT_VOICE))) {
            prepareVoiceTools(
                    candidate,
                    java.util.Objects.requireNonNull(
                            candidateVoiceProvider, "candidateVoiceProvider"),
                    replacements,
                    removals);
        }
        return new PreparedModelTools(replacements, removals);
    }

    private void prepareImageTool(
            ModelConfig config,
            Map<String, com.harness.tool.Tool> replacements,
            Set<String> removals
    ) {
        String imageApiKey = config.getString(ModelConfigKey.IMAGE_API_KEY, "");
        if (!imageApiKey.isBlank()) {
            ImageGenerationTool imageTool = new ImageGenerationTool(
                    new ImageGenerationTool.ArtifactStorer() {
                        @Override
                        public Artifact store(
                                byte[] data,
                                String name,
                                String mimeType,
                                String sessionId
                        ) {
                            return artifactStorageService.store(
                                    data, name, mimeType, sessionId);
                        }

                        @Override
                        public byte[] loadBytes(String artifactId) {
                            return artifactStore.get(artifactId)
                                    .map(artifact -> readArtifact(artifactId, artifact))
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "Artifact not found: " + artifactId));
                        }
                    },
                    config);
            replacements.put(imageTool.spec().name(), imageTool);
        } else {
            removals.add("image_generation");
        }
    }

    private void prepareVideoTool(
            ModelConfig config,
            Map<String, com.harness.tool.Tool> replacements,
            Set<String> removals
    ) {
        String videoApiKey = config.getString(ModelConfigKey.VIDEO_API_KEY, "");
        String videoBaseUrl = config.getString(ModelConfigKey.VIDEO_BASE_URL, "");
        if (!videoApiKey.isBlank() && !videoBaseUrl.isBlank()) {
            VideoGenerationTool videoTool = new VideoGenerationTool(
                    artifactStorageService::store,
                    (sessionId, artifact) -> log.info(
                            "[ArtifactCallback] Video artifact ready: {} for session {}",
                            artifact.name(), sessionId),
                    config);
            replacements.put(videoTool.spec().name(), videoTool);
        } else {
            removals.add("video_generation");
        }
    }

    private void prepareVoiceTools(
            ModelConfig config,
            VoiceModelProvider capabilitySource,
            Map<String, com.harness.tool.Tool> replacements,
            Set<String> removals
    ) {
        String voiceProviderName = config.getString(ModelConfigKey.VOICE_PROVIDER, "none");
        if ("none".equalsIgnoreCase(voiceProviderName)) {
            removals.add(AudioTranscriptionTool.TOOL_NAME);
            removals.add(SpeechSynthesisTool.TOOL_NAME);
            return;
        }

        VoiceCapabilities capabilities = capabilitySource.capabilities();
        if (capabilities.asrAvailable()) {
            AudioTranscriptionTool transcriptionTool = new AudioTranscriptionTool(
                    voiceProvider, this::loadAudioSource);
            replacements.put(transcriptionTool.spec().name(), transcriptionTool);
        } else {
            removals.add(AudioTranscriptionTool.TOOL_NAME);
        }
        if (capabilities.ttsAvailable()) {
            SpeechSynthesisTool synthesisTool = new SpeechSynthesisTool(
                    voiceProvider, artifactStorageService::store);
            replacements.put(synthesisTool.spec().name(), synthesisTool);
        } else {
            removals.add(SpeechSynthesisTool.TOOL_NAME);
        }
    }

    private static boolean sameValues(
            ModelConfig first,
            ModelConfig second,
            List<String> keys
    ) {
        return keys.stream().allMatch(key -> java.util.Objects.equals(
                first.getString(key), second.getString(key)));
    }

    public void applyModelTools(PreparedModelTools modelTools) {
        if (modelTools.replacements().isEmpty() && modelTools.removals().isEmpty()) {
            return;
        }
        toolRegistry.applyChanges(modelTools.replacements(), modelTools.removals());
    }

    public record PreparedModelTools(
            Map<String, com.harness.tool.Tool> replacements,
            Set<String> removals
    ) {
        public PreparedModelTools {
            replacements = Map.copyOf(replacements);
            removals = Set.copyOf(removals);
        }
    }

    private static byte[] readArtifact(String artifactId, Artifact artifact) {
        try {
            return Files.readAllBytes(Path.of(artifact.filePath()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read artifact file: " + artifactId, e);
        }
    }

    private AudioTranscriptionTool.AudioSource loadAudioSource(String reference) {
        String normalizedReference = reference != null ? reference.trim() : "";
        if (normalizedReference.startsWith("/api/artifacts/")) {
            String artifactId = normalizedReference.substring("/api/artifacts/".length());
            int separator = artifactId.indexOf('/');
            if (separator >= 0) {
                artifactId = artifactId.substring(0, separator);
            }
            int query = artifactId.indexOf('?');
            if (query >= 0) {
                artifactId = artifactId.substring(0, query);
            }
            String resolvedArtifactId = artifactId;
            Artifact artifact = artifactStore.get(resolvedArtifactId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Artifact not found: " + resolvedArtifactId));
            return new AudioTranscriptionTool.AudioSource(
                    readArtifact(resolvedArtifactId, artifact),
                    artifact.name(),
                    artifact.mimeType());
        }
        if (!normalizedReference.startsWith("/files/")) {
            throw new IllegalArgumentException(
                    "Audio reference must use /files/ or /api/artifacts/: " + reference);
        }

        String relativePath = normalizedReference.substring("/files/".length());
        Path uploadRoot = Path.of(EnvConfig.get().getString(
                EnvKey.KNOWLEDGE_UPLOAD_DIR, "./knowledge-uploads"))
                .toAbsolutePath()
                .normalize();
        Path audioPath = uploadRoot.resolve(relativePath).normalize();
        if (!audioPath.startsWith(uploadRoot)) {
            throw new IllegalArgumentException(
                    "Audio reference resolves outside the upload directory: " + reference);
        }
        if (!Files.isRegularFile(audioPath)) {
            throw new IllegalArgumentException("Audio file not found: " + reference);
        }
        try {
            return new AudioTranscriptionTool.AudioSource(
                    Files.readAllBytes(audioPath),
                    audioPath.getFileName().toString(),
                    detectAudioMimeType(audioPath));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read audio file: " + reference, e);
        }
    }

    private static String detectAudioMimeType(Path path) throws java.io.IOException {
        String detected = Files.probeContentType(path);
        if (detected != null && detected.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            return detected;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int extensionIndex = name.lastIndexOf('.');
        String extension = extensionIndex >= 0 ? name.substring(extensionIndex + 1) : "";
        return switch (extension) {
            case "mp3" -> "audio/mpeg";
            case "m4a", "mp4" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "webm" -> "audio/webm";
            case "ogg" -> "audio/ogg";
            default -> "application/octet-stream";
        };
    }

    private void registerMcpTools() {
        List<McpServerConfig> servers = McpServerConfig.loadAll();
        if (servers.isEmpty()) {
            return;
        }
        CompletableFuture.runAsync(
                () -> new McpToolDiscovery().discoverAndRegister(servers, toolRegistry),
                BlockingTaskExecutor.shared());
    }

    private void initializeSkills() {
        String skillDir = EnvConfig.get().getString(EnvKey.SKILL_DIR, "./skills");
        skillRegistry.loadIndex(Path.of(skillDir));
        toolRegistry.register(new LoadSkillTool(skillRegistry, toolRegistry));
        log.info("Skill tools registered: persistentSkills={}, directory={}",
                skillRegistry.size(), skillDir);
    }

    private Path resolveConfiguredProjectRoot() {
        EnvConfig config = EnvConfig.get();
        if (!config.getBool(EnvKey.PROJECT_DISCOVERY_ENABLED, true)) {
            return null;
        }
        String configPath = config.getString(EnvKey.PROJECT_APIS_CONFIG_FILE, "./project-apis.json");
        Path path = Path.of(configPath);
        if (!Files.exists(path)) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        try {
            ProjectApiConfig projectConfig = new ObjectMapper().readValue(path.toFile(), ProjectApiConfig.class);
            return normalizeProjectRoot(projectConfig.projectRoot());
        } catch (Exception e) {
            log.warn("[Discovery] Failed to read projectRoot from config, using '.': {}", e.getMessage());
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private void registerDiscoveryTools(Path projectRoot) {
        if (projectRoot == null) {
            return;
        }
        Set<String> excludes = Set.of();
        toolRegistry.register(new CodeGlobTool(projectRoot, excludes));
        toolRegistry.register(new CodeGrepTool(projectRoot, excludes));
        toolRegistry.register(new ReadClassHierarchyTool(projectRoot));
    }

    private void replaceDiscoveryTools(String configuredRoot) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return;
        }
        Path projectRoot = normalizeProjectRoot(configuredRoot);
        Set<String> excludes = Set.of();
        toolRegistry.replace(new CodeGlobTool(projectRoot, excludes));
        toolRegistry.replace(new CodeGrepTool(projectRoot, excludes));
        toolRegistry.replace(new ReadClassHierarchyTool(projectRoot));
        log.info("[Discovery] Re-registered discovery tools with projectRoot={}", projectRoot);
    }

    private static Path normalizeProjectRoot(String configuredRoot) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        return Path.of(configuredRoot).toAbsolutePath().normalize();
    }
}
