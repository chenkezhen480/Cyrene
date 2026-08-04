package com.harness.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.KnowledgeBaseTool;
import com.harness.agent.KnowledgeGraphTool;
import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.core.concurrent.BlockingTaskExecutor;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import com.harness.core.model.ProjectApiConfig;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.retrieval.GraphKnowledgeRetriever;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.provider.ModelProviders;
import com.harness.tool.ToolRegistry;
import com.harness.tool.artifact.ArtifactStorageService;
import com.harness.tool.builtin.FfmpegTool;
import com.harness.tool.builtin.ImageGenerationTool;
import com.harness.tool.builtin.PythonSandboxTool;
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

    public AgentToolRuntime(
            ModelProviders providers,
            GraphSettings graphSettings,
            GraphSchemaRegistry graphSchemaRegistry,
            KnowledgeGraphStore knowledgeGraphStore,
            GraphKnowledgeRetriever graphKnowledgeRetriever,
            GraphSpaceAccessService graphSpaceAccessService,
            ArtifactStore artifactStore,
            ArtifactStorageService artifactStorageService
    ) {
        this.toolRegistry = new ToolRegistry();
        this.skillRegistry = new SkillRegistry();
        registerBuiltins(
                providers,
                graphSettings,
                graphSchemaRegistry,
                knowledgeGraphStore,
                graphKnowledgeRetriever,
                graphSpaceAccessService,
                artifactStore,
                artifactStorageService);
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
            ArtifactStorageService artifactStorageService
    ) {
        EnvConfig config = EnvConfig.get();
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
            toolRegistry.register(new KnowledgeBaseTool(
                    providers.embedding(), providers.rerank(), providers.chat()));
        } else {
            log.info("Knowledge base tool disabled (ragProvider={}, embedding={})",
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

        String imageApiKey = config.getString(EnvKey.TOOL_IMAGE_GEN_API_KEY, "");
        if (!imageApiKey.isBlank()) {
            toolRegistry.register(new ImageGenerationTool(new ImageGenerationTool.ArtifactStorer() {
                @Override
                public Artifact store(byte[] data, String name, String mimeType, String sessionId) {
                    return artifactStorageService.store(data, name, mimeType, sessionId);
                }

                @Override
                public byte[] loadBytes(String artifactId) {
                    return artifactStore.get(artifactId)
                            .map(artifact -> readArtifact(artifactId, artifact))
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Artifact not found: " + artifactId));
                }
            }));
        } else {
            log.info("Image generation tool disabled (no HARNESS_TOOL_IMAGE_GEN_API_KEY)");
        }

        String videoApiKey = config.getString(EnvKey.TOOL_VIDEO_GEN_API_KEY, "");
        String videoBaseUrl = config.getString(EnvKey.TOOL_VIDEO_GEN_BASE_URL, "");
        if (!videoApiKey.isBlank() && !videoBaseUrl.isBlank()) {
            toolRegistry.register(new VideoGenerationTool(
                    artifactStorageService::store,
                    (sessionId, artifact) -> log.info(
                            "[ArtifactCallback] Video artifact ready: {} for session {}",
                            artifact.name(), sessionId)));
        } else {
            log.info("Video generation tool disabled (no HARNESS_TOOL_VIDEO_GEN_API_KEY/BASE_URL)");
        }

        registerDiscoveryTools(resolveConfiguredProjectRoot());
    }

    private static byte[] readArtifact(String artifactId, Artifact artifact) {
        try {
            return Files.readAllBytes(Path.of(artifact.filePath()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read artifact file: " + artifactId, e);
        }
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
