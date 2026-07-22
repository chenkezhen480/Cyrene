package com.harness.tool.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.AuthMode;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.TokenInjection;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import com.harness.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool for managing project API endpoints in memory with auto-sync to disk.
 * Supports add, remove, and update operations on individual endpoints.
 *
 * <p>Operations take effect immediately in memory and are persisted to project-apis.json.</p>
 */
public class UpdateProjectApiTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(UpdateProjectApiTool.class);

    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;

    public UpdateProjectApiTool(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.mapper = new ObjectMapper();
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "update_project_api",
                "Add, remove, or update a single API endpoint in the project configuration. "
                        + "Changes take effect immediately (no restart needed) and are auto-saved to disk. "
                        + "Actions: 'add' (new endpoint), 'remove' (delete by id), 'update' (modify by id). "
                        + "For 'add': provide name, method, path at minimum. "
                        + "For 'remove': provide endpoint 'id'. "
                        + "For 'update': provide 'id' plus fields to change.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .put("required", "action")
                        .set("properties", mapper.createObjectNode()
                                .put("action", "Action: 'add', 'remove', or 'update'")
                                .put("id", "Endpoint ID (required for remove/update)")
                                .put("name", "Endpoint name (required for add)")
                                .put("description", "Endpoint description")
                                .put("method", "HTTP method: GET, POST, PUT, DELETE, PATCH (default GET)")
                                .put("path", "URL path like /api/users (required for add)")
                                .put("baseUrl", "Override base URL for this endpoint (optional)")
                                .put("parameters", "JSON Schema object for parameters")
                                .put("authMode", "Auth mode: NONE, USER_PASSTHROUGH, BOT (default USER_PASSTHROUGH)")
                                .put("confirmed", "Whether endpoint is confirmed (boolean)")
                                .put("riskAcknowledged", "Whether risk is acknowledged (boolean)")
                        )
        );
    }

    @Override
    public String execute(JsonNode args) {
        String action = extractString(args, "action");
        if (action == null || action.isBlank()) {
            return error("Missing required field 'action'. Use: add, remove, or update.");
        }

        return switch (action.toLowerCase()) {
            case "add" -> handleAdd(args);
            case "remove" -> handleRemove(args);
            case "update" -> handleUpdate(args);
            default -> error("Unknown action '%s'. Use: add, remove, or update.".formatted(action));
        };
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Action handlers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String handleAdd(JsonNode args) {
        String name = extractString(args, "name");
        String path = extractString(args, "path");
        if (name == null || name.isBlank()) return error("Missing required field 'name' for add.");
        if (path == null || path.isBlank()) return error("Missing required field 'path' for add.");

        String method = extractString(args, "method");
        if (method == null || method.isBlank()) method = "GET";
        method = method.toUpperCase();

        String description = extractString(args, "description");
        if (description == null || description.isBlank()) description = method + " " + path;

        String baseUrl = extractString(args, "baseUrl");
        String authModeStr = extractString(args, "authMode");
        AuthMode authMode = parseAuthMode(authModeStr, AuthMode.USER_PASSTHROUGH);

        JsonNode parameters = args.get("parameters");
        if (parameters == null || parameters.isNull()) {
            parameters = mapper.createObjectNode().put("type", "object").set("properties", mapper.createObjectNode());
        }

        boolean confirmed = extractBool(args, "confirmed", false);
        boolean riskAcknowledged = extractBool(args, "riskAcknowledged", false);

        ProjectApiConfig config = toolRegistry.getProjectApiConfig();
        if (config == null) return error("No project API config loaded. Run discovery scan first.");

        // Generate next ID
        String nextId = generateNextId(config.endpoints());

        ApiEndpoint endpoint = new ApiEndpoint(
                nextId, name, description, method, path, baseUrl,
                "manual", authMode, "Authorization",
                new TokenInjection("header", "Authorization", "Bearer "),
                parameters, confirmed, riskAcknowledged
        );

        List<ApiEndpoint> updated = new ArrayList<>(config.endpoints());
        updated.add(endpoint);

        ProjectApiConfig newConfig = config.withEndpoints(updated);
        if (!toolRegistry.updateProjectApiConfig(newConfig)) {
            return error("Failed to save config to disk. Endpoint added in memory only.");
        }

        log.info("[UpdateProjectApi] Added endpoint: {} {} ({})", method, path, nextId);
        ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
        return "Endpoint added successfully:\n"
                + "  id: " + nextId + "\n"
                + "  name: " + name + "\n"
                + "  method: " + method + "\n"
                + "  path: " + path;
    }

    private String handleRemove(JsonNode args) {
        String id = extractString(args, "id");
        if (id == null || id.isBlank()) return error("Missing required field 'id' for remove.");

        ProjectApiConfig config = toolRegistry.getProjectApiConfig();
        if (config == null) return error("No project API config loaded.");

        List<ApiEndpoint> endpoints = config.endpoints();
        ApiEndpoint found = endpoints.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
        if (found == null) return error("Endpoint not found: " + id);

        List<ApiEndpoint> updated = new ArrayList<>(endpoints);
        updated.remove(found);

        ProjectApiConfig newConfig = config.withEndpoints(updated);
        if (!toolRegistry.updateProjectApiConfig(newConfig)) {
            return error("Failed to save config to disk. Endpoint removed in memory only.");
        }

        log.info("[UpdateProjectApi] Removed endpoint: {} {} ({})", found.method(), found.path(), id);
        ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
        return "Endpoint removed: " + found.method() + " " + found.path() + " (" + id + ")";
    }

    private String handleUpdate(JsonNode args) {
        String id = extractString(args, "id");
        if (id == null || id.isBlank()) return error("Missing required field 'id' for update.");

        ProjectApiConfig config = toolRegistry.getProjectApiConfig();
        if (config == null) return error("No project API config loaded.");

        List<ApiEndpoint> endpoints = config.endpoints();
        int idx = -1;
        for (int i = 0; i < endpoints.size(); i++) {
            if (endpoints.get(i).id().equals(id)) { idx = i; break; }
        }
        if (idx < 0) return error("Endpoint not found: " + id);

        ApiEndpoint old = endpoints.get(idx);

        // Merge: only override fields that are explicitly provided
        String name = extractString(args, "name");
        String description = extractString(args, "description");
        String method = extractString(args, "method");
        String path = extractString(args, "path");
        String baseUrl = extractString(args, "baseUrl");
        String authModeStr = extractString(args, "authMode");
        JsonNode parameters = args.get("parameters");
        Boolean confirmed = extractOptionalBool(args, "confirmed");
        Boolean riskAcknowledged = extractOptionalBool(args, "riskAcknowledged");

        AuthMode authMode = authModeStr != null ? parseAuthMode(authModeStr, old.authMode()) : old.authMode();

        ApiEndpoint updated = new ApiEndpoint(
                old.id(),
                name != null ? name : old.name(),
                description != null ? description : old.description(),
                method != null ? method.toUpperCase() : old.method(),
                path != null ? path : old.path(),
                baseUrl != null ? baseUrl : old.baseUrl(),
                old.source(),
                authMode,
                old.credentialKey(),
                old.tokenInjection(),
                parameters != null ? parameters : old.parameters(),
                confirmed != null ? confirmed : old.confirmed(),
                riskAcknowledged != null ? riskAcknowledged : old.riskAcknowledged()
        );

        List<ApiEndpoint> newList = new ArrayList<>(endpoints);
        newList.set(idx, updated);

        ProjectApiConfig newConfig = config.withEndpoints(newList);
        if (!toolRegistry.updateProjectApiConfig(newConfig)) {
            return error("Failed to save config to disk. Endpoint updated in memory only.");
        }

        log.info("[UpdateProjectApi] Updated endpoint: {} {} ({})", updated.method(), updated.path(), id);
        ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
        return "Endpoint updated: " + updated.method() + " " + updated.path() + " (" + id + ")";
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String generateNextId(List<ApiEndpoint> endpoints) {
        int max = 0;
        for (ApiEndpoint ep : endpoints) {
            String id = ep.id();
            if (id != null && id.startsWith("ep_")) {
                try {
                    int num = Integer.parseInt(id.substring(3));
                    if (num > max) max = num;
                } catch (NumberFormatException ignored) {}
            }
        }
        return String.format("ep_%04d", max + 1);
    }

    private AuthMode parseAuthMode(String value, AuthMode fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return AuthMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private String extractString(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) return null;
        String val = node.asText("").trim();
        return val.isEmpty() ? null : val;
    }

    private boolean extractBool(JsonNode args, String field, boolean defaultValue) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) return defaultValue;
        return node.asBoolean(defaultValue);
    }

    private Boolean extractOptionalBool(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) return null;
        return node.asBoolean();
    }

    private String error(String msg) {
        return "Error: " + msg;
    }
}
