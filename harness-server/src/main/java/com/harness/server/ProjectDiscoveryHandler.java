package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.agent.ProjectDiscoveryService;
import com.harness.core.model.ProjectApiConfig;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Javalin handler for project API discovery endpoints.
 *
 * <pre>
 *   POST /api/project-discovery/scan     — trigger scan, body: { sourceRoot }
 *   POST /api/project-discovery/generate — write config to project-apis.json
 *   GET  /api/project-discovery/config   — read current config
 *   PUT  /api/project-discovery/config   — update config (full replace)
 *   POST /api/project-discovery/reload   — hot-reload into ToolRegistry
 * </pre>
 */
public class ProjectDiscoveryHandler {

    private static final Logger log = LoggerFactory.getLogger(ProjectDiscoveryHandler.class);
    private final ObjectMapper mapper;
    private final AgentOrchestrator agent;
    private final ProjectDiscoveryService discoveryService;

    public ProjectDiscoveryHandler(ObjectMapper mapper, AgentOrchestrator agent) {
        this.mapper = mapper;
        this.agent = agent;
        this.discoveryService = new ProjectDiscoveryService(
                agent.chatModel(), agent.confirmationManager());
    }

    /**
     * POST /api/project-discovery/scan — trigger a discovery scan.
     * Body: { "sourceRoot": "/path/to/project", "baseUrl": "http://localhost:8081" }
     * Returns the discovered endpoints (not yet saved).
     */
    public void scan(Context ctx) {
        try {
            Map<String, String> body = ctx.bodyAsClass(Map.class);
            String sourceRoot = body.get("sourceRoot");
            String baseUrl = body.getOrDefault("baseUrl", "");
            if (sourceRoot == null || sourceRoot.isBlank()) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                        "sourceRoot is required");
                return;
            }

            Path root = Path.of(sourceRoot).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                        "sourceRoot is not a directory: " + root);
                return;
            }

            log.info("[Discovery] Scan requested for: {}, baseUrl={}", root, baseUrl);

            // Run discovery (blocking — may take a while for code scan)
            ProjectApiConfig config = discoveryService.discover(root, baseUrl);

            ctx.json(Map.of(
                    "status", "ok",
                    "projectDescription", config.projectDescription(),
                    "projectRoot", config.projectRoot() != null ? config.projectRoot() : "",
                    "baseUrl", config.baseUrl() != null ? config.baseUrl() : "",
                    "endpoints", config.endpoints(),
                    "discoveredAt", config.discoveredAt()
            ));

        } catch (Exception e) {
            log.error("[Discovery] Scan failed: {}", e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * POST /api/project-discovery/generate — write scan results to project-apis.json.
     * Body: full ProjectApiConfig JSON.
     */
    public void generate(Context ctx) {
        try {
            ProjectApiConfig config = ctx.bodyAsClass(ProjectApiConfig.class);
            if (config.endpoints() == null || config.endpoints().isEmpty()) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                        "endpoints array is required and must not be empty");
                return;
            }

            Path configPath = getConfigPath();
            mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);

            // Auto-reload into ToolRegistry
            agent.loadProjectApiConfig();

            log.info("[Discovery] Generated project-apis.json with {} endpoints at {}",
                    config.endpoints().size(), configPath);
            ctx.json(Map.of(
                    "status", "ok",
                    "path", configPath.toString(),
                    "endpoints", config.endpoints().size()
            ));

        } catch (Exception e) {
            log.error("[Discovery] Generate failed: {}", e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * GET /api/project-discovery/config — read current project-apis.json.
     */
    public void getConfig(Context ctx) {
        try {
            Path configPath = getConfigPath();
            if (!Files.exists(configPath)) {
                ApiResponses.error(ctx, 404, ApiErrorCode.NOT_FOUND, "Config file not found",
                        Map.of("path", configPath.toString()));
                return;
            }

            ProjectApiConfig config = mapper.readValue(configPath.toFile(), ProjectApiConfig.class);
            ctx.json(Map.of(
                    "path", configPath.toString(),
                    "config", config
            ));

        } catch (Exception e) {
            log.error("[Discovery] Failed to read config: {}", e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * PUT /api/project-discovery/config — update (full replace) project-apis.json.
     * Body: full ProjectApiConfig JSON.
     */
    public void updateConfig(Context ctx) {
        try {
            ProjectApiConfig config = ctx.bodyAsClass(ProjectApiConfig.class);
            Path configPath = getConfigPath();

            mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);

            log.info("[Discovery] Updated project-apis.json with {} endpoints", config.endpoints().size());
            ctx.json(Map.of(
                    "status", "ok",
                    "path", configPath.toString(),
                    "endpoints", config.endpoints().size()
            ));

        } catch (Exception e) {
            log.error("[Discovery] Failed to update config: {}", e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * POST /api/project-discovery/reload — hot-reload config into ToolRegistry.
     */
    public void reload(Context ctx) {
        try {
            agent.loadProjectApiConfig();
            log.info("[Discovery] Hot-reload triggered via API");
            ctx.json(Map.of("status", "ok"));
        } catch (Exception e) {
            log.error("[Discovery] Reload failed: {}", e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    private Path getConfigPath() {
        String configPath = EnvConfig.get().getString(EnvKey.PROJECT_APIS_CONFIG_FILE, "./project-apis.json");
        return Path.of(configPath).toAbsolutePath().normalize();
    }
}
