package com.harness.tool.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.AuthMode;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.TokenInjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Deterministic parser for OpenAPI/Swagger spec files.
 * No LLM calls — pure structured parsing.
 *
 * <p>Supports:
 * <ul>
 *   <li>OpenAPI 3.x (openapi.json / openapi.yaml)</li>
 *   <li>Swagger 2.x (swagger.json / swagger.yaml)</li>
 * </ul>
 */
public class OpenApiSpecParser {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSpecParser.class);
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** Common spec file names to probe. */
    private static final List<String> SPEC_FILE_NAMES = List.of(
            "openapi.json", "openapi.yaml", "openapi.yml",
            "swagger.json", "swagger.yaml", "swagger.yml",
            "api-docs.json", "api-docs.yaml",
            "docs/openapi.json", "docs/openapi.yaml",
            "api/openapi.json", "api/openapi.yaml",
            "src/main/resources/openapi.json", "src/main/resources/openapi.yaml"
    );

    /**
     * Probe the project root for an OpenAPI/Swagger spec file.
     * Returns the path to the first found spec, or empty if none found.
     */
    public Optional<Path> findSpec(Path projectRoot) {
        for (String name : SPEC_FILE_NAMES) {
            Path specPath = projectRoot.resolve(name).normalize();
            if (Files.exists(specPath) && Files.isRegularFile(specPath)) {
                log.info("[OpenAPI] Found spec file: {}", specPath);
                return Optional.of(specPath);
            }
        }
        return Optional.empty();
    }

    /**
     * Parse an OpenAPI/Swagger spec file into a ProjectApiConfig.
     *
     * @param specPath path to the spec file
     * @param projectRoot the project root (for sourceRoot in config)
     * @return parsed config with all endpoints marked source="openapi"
     */
    public ProjectApiConfig parse(Path specPath, Path projectRoot) throws IOException {
        String content = Files.readString(specPath);
        JsonNode root;

        // Detect YAML vs JSON
        String fileName = specPath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            root = yamlMapper.readTree(content);
        } else {
            root = jsonMapper.readTree(content);
        }

        // Detect version
        if (root.has("openapi")) {
            return parseOpenApi3(root, projectRoot);
        } else if (root.has("swagger")) {
            return parseSwagger2(root, projectRoot);
        } else {
            log.warn("[OpenAPI] Unknown spec format (no 'openapi' or 'swagger' key), attempting OpenAPI 3 parse");
            return parseOpenApi3(root, projectRoot);
        }
    }

    /**
     * Parse OpenAPI 3.x spec.
     */
    private ProjectApiConfig parseOpenApi3(JsonNode root, Path projectRoot) {
        List<ApiEndpoint> endpoints = new ArrayList<>();
        String basePath = "";

        // Extract base URL from servers
        JsonNode servers = root.get("servers");
        String defaultBaseUrl = "";
        if (servers != null && servers.isArray() && !servers.isEmpty()) {
            defaultBaseUrl = servers.get(0).has("url") ? servers.get(0).get("url").asText() : "";
        }

        // Parse paths
        JsonNode paths = root.get("paths");
        if (paths != null) {
            var fieldNames = paths.fieldNames();
            while (fieldNames.hasNext()) {
                String path = fieldNames.next();
                JsonNode pathItem = paths.get(path);

                for (String method : List.of("get", "post", "put", "patch", "delete", "head", "options")) {
                    JsonNode operation = pathItem.get(method);
                    if (operation == null) continue;

                    String operationId = operation.has("operationId")
                            ? operation.get("operationId").asText()
                            : method + path.replaceAll("[^a-zA-Z0-9]", "_");
                    String summary = operation.has("summary") ? operation.get("summary").asText()
                            : operation.has("description") ? operation.get("description").asText()
                            : method.toUpperCase() + " " + path;

                    // Extract parameters schema
                    JsonNode params = extractParametersSchema(operation, root);

                    // Determine auth: check security requirements
                    boolean requiresAuth = operation.has("security") || root.has("security");

                    ApiEndpoint ep = new ApiEndpoint(
                            "ep_" + Integer.toHexString(endpoints.size() + 1),
                            operationId,
                            summary,
                            method.toUpperCase(),
                            path,
                            defaultBaseUrl,
                            "openapi",
                            requiresAuth ? AuthMode.USER_PASSTHROUGH : AuthMode.BOT,
                            requiresAuth ? guessCredentialKey(defaultBaseUrl) : null,
                            requiresAuth ? new TokenInjection("header", "Authorization", "Bearer ") : null,
                            params,
                            false,  // confirmed — not yet reviewed
                            false   // riskAcknowledged
                    );
                    endpoints.add(ep);
                }
            }
        }

        log.info("[OpenAPI] Parsed {} endpoints from OpenAPI 3.x spec", endpoints.size());
        return new ProjectApiConfig(Instant.now().toString(), projectRoot.toAbsolutePath().toString(), null, endpoints);
    }

    /**
     * Parse Swagger 2.x spec.
     */
    private ProjectApiConfig parseSwagger2(JsonNode root, Path projectRoot) {
        List<ApiEndpoint> endpoints = new ArrayList<>();

        // Extract base URL from host + basePath + schemes
        String host = root.has("host") ? root.get("host").asText() : "localhost";
        String basePath = root.has("basePath") ? root.get("basePath").asText() : "";
        String scheme = "http";
        if (root.has("schemes") && root.get("schemes").isArray() && !root.get("schemes").isEmpty()) {
            scheme = root.get("schemes").get(0).asText();
        }
        String defaultBaseUrl = scheme + "://" + host + basePath;

        JsonNode paths = root.get("paths");
        if (paths != null) {
            var fieldNames = paths.fieldNames();
            while (fieldNames.hasNext()) {
                String path = fieldNames.next();
                JsonNode pathItem = paths.get(path);

                for (String method : List.of("get", "post", "put", "patch", "delete", "head", "options")) {
                    JsonNode operation = pathItem.get(method);
                    if (operation == null) continue;

                    String operationId = operation.has("operationId")
                            ? operation.get("operationId").asText()
                            : method + path.replaceAll("[^a-zA-Z0-9]", "_");
                    String summary = operation.has("summary") ? operation.get("summary").asText()
                            : operation.has("description") ? operation.get("description").asText()
                            : method.toUpperCase() + " " + path;

                    JsonNode params = extractSwagger2Params(operation, root);

                    boolean requiresAuth = operation.has("security") || root.has("security");

                    ApiEndpoint ep = new ApiEndpoint(
                            "ep_" + Integer.toHexString(endpoints.size() + 1),
                            operationId,
                            summary,
                            method.toUpperCase(),
                            path,
                            defaultBaseUrl,
                            "openapi",
                            requiresAuth ? AuthMode.USER_PASSTHROUGH : AuthMode.BOT,
                            requiresAuth ? guessCredentialKey(defaultBaseUrl) : null,
                            requiresAuth ? new TokenInjection("header", "Authorization", "Bearer ") : null,
                            params,
                            false,
                            false
                    );
                    endpoints.add(ep);
                }
            }
        }

        log.info("[OpenAPI] Parsed {} endpoints from Swagger 2.x spec", endpoints.size());
        return new ProjectApiConfig(Instant.now().toString(), projectRoot.toAbsolutePath().toString(), null, endpoints);
    }

    /**
     * Extract parameters as JSON Schema from OpenAPI 3.x operation.
     */
    private JsonNode extractParametersSchema(JsonNode operation, JsonNode root) {
        var schema = jsonMapper.createObjectNode();
        schema.put("type", "object");
        var properties = jsonMapper.createObjectNode();
        var required = jsonMapper.createArrayNode();

        JsonNode params = operation.get("parameters");
        if (params != null && params.isArray()) {
            for (JsonNode param : params) {
                String name = param.has("name") ? param.get("name").asText() : "unknown";
                String in = param.has("in") ? param.get("in").asText() : "query";
                boolean isRequired = param.has("required") && param.get("required").asBoolean();

                var propSchema = jsonMapper.createObjectNode();
                JsonNode schemaNode = param.get("schema");
                if (schemaNode != null) {
                    if (schemaNode.has("type")) propSchema.put("type", schemaNode.get("type").asText());
                    if (schemaNode.has("description")) propSchema.put("description", schemaNode.get("description").asText());
                }
                propSchema.put("in", in);
                if (param.has("description")) propSchema.put("description", param.get("description").asText());
                properties.set(name, propSchema);
                if (isRequired) required.add(name);
            }
        }

        // Check requestBody
        JsonNode requestBody = operation.get("requestBody");
        if (requestBody != null) {
            JsonNode content = requestBody.get("content");
            if (content != null) {
                JsonNode jsonContent = content.get("application/json");
                if (jsonContent != null && jsonContent.has("schema")) {
                    properties.set("__body__", jsonContent.get("schema"));
                }
            }
        }

        schema.set("properties", properties);
        if (!required.isEmpty()) schema.set("required", required);
        return schema;
    }

    /**
     * Extract parameters from Swagger 2.x operation.
     */
    private JsonNode extractSwagger2Params(JsonNode operation, JsonNode root) {
        var schema = jsonMapper.createObjectNode();
        schema.put("type", "object");
        var properties = jsonMapper.createObjectNode();
        var required = jsonMapper.createArrayNode();

        JsonNode params = operation.get("parameters");
        if (params != null && params.isArray()) {
            for (JsonNode param : params) {
                String name = param.has("name") ? param.get("name").asText() : "unknown";
                String in = param.has("in") ? param.get("in").asText() : "query";
                boolean isRequired = param.has("required") && param.get("required").asBoolean();

                var propSchema = jsonMapper.createObjectNode();
                if (param.has("type")) propSchema.put("type", param.get("type").asText());
                if (param.has("description")) propSchema.put("description", param.get("description").asText());
                propSchema.put("in", in);
                properties.set(name, propSchema);
                if (isRequired) required.add(name);
            }
        }

        schema.set("properties", properties);
        if (!required.isEmpty()) schema.set("required", required);
        return schema;
    }

    /**
     * Guess a credentialKey from the base URL (e.g., "http://order-service:8080" → "orderService").
     */
    private String guessCredentialKey(String baseUrl) {
        try {
            String host = new java.net.URL(baseUrl).getHost();
            // Extract service name from host: "order-service" → "orderService"
            String[] parts = host.split("-");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i == 0) {
                    sb.append(parts[i].toLowerCase());
                } else {
                    sb.append(parts[i].substring(0, 1).toUpperCase());
                    sb.append(parts[i].substring(1).toLowerCase());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "defaultService";
        }
    }
}
