package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.react.ReActEngine;
import com.harness.core.model.*;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import com.harness.tool.discovery.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Orchestrates the project API discovery flow.
 *
 * <ol>
 *   <li>Probe for OpenAPI/Swagger spec → deterministic parse if found</li>
 *   <li>If no spec, run a dedicated sub-agent with discovery tools to scan the codebase</li>
 *   <li>Return discovered endpoints as a draft {@link ProjectApiConfig}</li>
 * </ol>
 *
 * <p>The discovery sub-agent uses a <b>separate, temporary {@link ToolRegistry}</b> containing only
 * the three discovery primitives. It does NOT share the main ToolRegistry — discovery tools must
 * not leak into the agent's regular tool set.
 */
public class ProjectDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ProjectDiscoveryService.class);

    private final ChatModelProvider chatModelProvider;

    public ProjectDiscoveryService(ChatModelProvider chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    /**
     * Run the discovery flow on a project root directory.
     *
     * <p>If an OpenAPI spec is found, it's parsed deterministically (no LLM).
     * Otherwise, a dedicated sub-agent with discovery tools scans the codebase.
     *
     * @param sourceRoot absolute path to the project root
     * @return discovered endpoints (not yet confirmed by human)
     */
    public ProjectApiConfig discover(Path sourceRoot, String baseUrl) {
        sourceRoot = sourceRoot.toAbsolutePath().normalize();
        log.info("[Discovery] Starting discovery for: {}, baseUrl={}", sourceRoot, baseUrl);

        // Phase 1: Try OpenAPI spec first
        OpenApiSpecParser specParser = new OpenApiSpecParser();
        Optional<Path> specPath = specParser.findSpec(sourceRoot);

        if (specPath.isPresent()) {
            try {
                ProjectApiConfig config = specParser.parse(specPath.get(), sourceRoot);
                log.info("[Discovery] OpenAPI spec found, parsed {} endpoints (source=openapi)",
                        config.endpoints().size());
                return applyBaseUrl(config, baseUrl);
            } catch (Exception e) {
                log.warn("[Discovery] Failed to parse OpenAPI spec {}: {}, falling back to code scan",
                        specPath.get(), e.getMessage());
            }
        }

        // Phase 2: No spec — run code scan via dedicated sub-agent
        log.info("[Discovery] No OpenAPI spec found, starting code scan");
        return scanBySubAgent(sourceRoot, baseUrl);
    }

    /**
     * Apply baseUrl to all endpoints in a config.
     */
    private ProjectApiConfig applyBaseUrl(ProjectApiConfig config, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return config;
        List<ApiEndpoint> updated = config.endpoints().stream()
                .map(ep -> new ApiEndpoint(ep.id(), ep.name(), ep.description(), ep.method(),
                        ep.path(), baseUrl, ep.source(), ep.authMode(), ep.credentialKey(),
                        ep.tokenInjection(), ep.parameters(), ep.confirmed(), ep.riskAcknowledged()))
                .toList();
        return new ProjectApiConfig(config.discoveredAt(), config.sourceRoot(), updated);
    }

    /**
     * Run a dedicated sub-agent with discovery tools to scan the codebase.
     * Creates a temporary, isolated ToolRegistry with only the three discovery primitives.
     */
    private ProjectApiConfig scanBySubAgent(Path sourceRoot, String baseUrl) {
        EnvConfig cfg = EnvConfig.get();
        int maxToolCalls = cfg.getInt(EnvKey.PROJECT_DISCOVERY_MAX_TOOL_CALLS, 60);
        int timeoutMinutes = cfg.getInt(EnvKey.PROJECT_DISCOVERY_TIMEOUT_MINUTES, 10);

        // Parse additional exclude patterns from env
        Set<String> additionalExcludes = Set.of();
        String excludePatterns = cfg.getString(EnvKey.PROJECT_DISCOVERY_EXCLUDE_PATTERNS);
        if (excludePatterns != null && !excludePatterns.isBlank()) {
            additionalExcludes = Set.of(excludePatterns.split(","));
        }

        // Build isolated ToolRegistry with discovery tools only
        ToolRegistry discoveryRegistry = new ToolRegistry();
        discoveryRegistry.register(new CodeGlobTool(sourceRoot, additionalExcludes));
        discoveryRegistry.register(new CodeGrepTool(sourceRoot, additionalExcludes));
        discoveryRegistry.register(new CodeReadTool(sourceRoot, additionalExcludes));
        ToolExecutor discoveryExecutor = new ToolExecutor(discoveryRegistry);

        // Create independent ReActEngine for discovery
        ReActEngine discoveryEngine = new ReActEngine(
                chatModelProvider, discoveryRegistry, discoveryExecutor, null, null);

        // Build discovery prompt
        String systemPrompt = buildDiscoveryPrompt(sourceRoot, maxToolCalls);
        String userMessage = "Scan this project and discover all API endpoints. " +
                             "Focus on REST API route registrations (Spring annotations, Express routes, Flask decorators, etc.).";

        // Run discovery with independent lifecycle (not tied to any chat request)
        AgentTrace.Builder traceBuilder = AgentTrace.builder();
        CancellationToken cancellationToken = new CancellationToken();

        try {
            ReActEngine.ReActResult result = discoveryEngine.execute(
                    systemPrompt, userMessage, List.of(),
                    traceBuilder, null, cancellationToken, null);

            // Parse LLM output into endpoints
            List<ApiEndpoint> endpoints = parseEndpointsFromOutput(result.output(), sourceRoot, baseUrl);

            // Extract project description from output, fallback to directory name
            String projectDescription = extractProjectDescription(result.output());
            if (projectDescription == null || projectDescription.isBlank()) {
                projectDescription = sourceRoot.getFileName().toString();
            }

            log.info("[Discovery] Code scan complete: {} endpoints discovered, {} tool calls, description: {}",
                    endpoints.size(), result.steps().stream().mapToInt(s -> s.toolCalls().size()).sum(),
                    projectDescription);

            return new ProjectApiConfig(
                    java.time.Instant.now().toString(),
                    projectDescription,
                    endpoints
            );

        } catch (Exception e) {
            log.error("[Discovery] Code scan failed: {}", e.getMessage(), e);
            // Return empty config with directory name as description
            return new ProjectApiConfig(
                    java.time.Instant.now().toString(),
                    sourceRoot.getFileName().toString(),
                    List.of()
            );
        }
    }

    /**
     * Build the system prompt for the discovery sub-agent.
     */
    private String buildDiscoveryPrompt(Path sourceRoot, int maxToolCalls) {
        return """
            You are a code-scanning agent that discovers REST API endpoints in a project.

            Your task: find ALL API endpoint registrations in the project at %s.

            EFFICIENT WORKFLOW (follow this strictly):
            Phase 1 - LOCATE controllers (1-2 tool calls):
              1. code_glob("**/*Controller.java") to find all controller files
              2. If none found, try code_glob for *.py, *.js, *.ts or code_grep for @RestController

            Phase 2 - READ controllers using code_read:
              Call code_read on multiple controller files in parallel (multiple tool calls per request).
              Use lines "1-120" to read only the class-level @RequestMapping and method-level mappings.
              Extract endpoints directly from the source code — do NOT use code_grep again.

            Phase 3 - OUTPUT your findings immediately. Do NOT search further.

            For each endpoint found, extract:
            - name: Java method name (e.g. getOrderDetail)
            - description: from comments or @Operation annotation
            - method: HTTP method (GET/POST/PUT/DELETE)
            - path: combine class-level @RequestMapping + method-level mapping (e.g. /system/user/list)
            - parameters: brief description from method signature

            CRITICAL RULES:
            - Maximum %d tool calls. Budget your calls wisely.
            - Call code_read on multiple files in parallel (one tool call per file, multiple per request).
            - Read only lines 1-120 of each controller to get the mappings section.
            - After reading all controller files, STOP and output results.
            - Do NOT do additional grep searches after reading files.
            - Do NOT read sensitive files (.env, *.pem, *.key).

            Output format:
            First, output a project summary line:
            PROJECT: <one-line description of what this project does, e.g. "若依管理系统-用户权限模块">

            Then repeat for each endpoint:
            ENDPOINT:
            - name: <name>
            - description: <description>
            - method: <GET|POST|PUT|DELETE>
            - path: <url path>
            - parameters: JSON Schema object describing the parameters. Example:
              {"type":"object","properties":{"userId":{"type":"integer","description":"用户ID"},"userName":{"type":"string","description":"用户名"}},"required":["userName"]}
              For endpoints with no parameters: {"type":"object","properties":{}}
              For request body (POST/PUT), describe the body fields as top-level properties.
            - returnType: brief description of the response type, e.g. "List<UserVO>" or "AjaxResult<PageInfo>"
            """.formatted(sourceRoot, maxToolCalls);
    }

    /**
     * Extract project description from the LLM's output.
     * Looks for a line starting with "PROJECT:" and returns the description text.
     */
    private String extractProjectDescription(String output) {
        if (output == null || output.isBlank()) return null;
        for (String line : output.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("PROJECT:")) {
                return trimmed.substring("PROJECT:".length()).trim();
            }
        }
        return null;
    }

    /**
     * Parse endpoint definitions from the LLM's text output.
     * This is a best-effort extraction — the LLM may not always produce perfectly structured output.
     */
    private List<ApiEndpoint> parseEndpointsFromOutput(String output, Path sourceRoot, String baseUrl) {
        if (output == null || output.isBlank()) return List.of();

        List<ApiEndpoint> endpoints = new ArrayList<>();
        var mapper = new ObjectMapper();
        // Split by ENDPOINT: marker
        String[] blocks = output.split("(?m)^ENDPOINT:");
        int id = 1;

        for (String block : blocks) {
            if (block.isBlank()) continue;
            try {
                String name = extractField(block, "name");
                String desc = extractField(block, "description");
                String method = extractField(block, "method");
                String path = extractField(block, "path");
                String params = extractField(block, "parameters");
                String returnType = extractField(block, "returnType");

                if (name.isEmpty() || path.isEmpty()) continue;

                if (method.isEmpty()) method = "GET";

                // Try to parse parameters as JSON Schema; fallback to wrapping as description
                JsonNode schema;
                if (!params.isEmpty()) {
                    String trimmed = params.trim();
                    if (trimmed.startsWith("{")) {
                        try {
                            schema = mapper.readTree(trimmed);
                        } catch (Exception e) {
                            // Not valid JSON, wrap as description
                            schema = buildFallbackSchema(mapper, params);
                        }
                    } else {
                        schema = buildFallbackSchema(mapper, params);
                    }
                } else {
                    schema = buildFallbackSchema(mapper, "无参数");
                }

                // Embed returnType as x-returnType extension if present
                if (returnType != null && !returnType.isBlank() && schema.isObject()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) schema).put("x-returnType", returnType);
                }

                // 从 path 中提取模块前缀，生成唯一 name（如 course_category_list）
                String uniqueName = buildUniqueName(path, name);

                endpoints.add(new ApiEndpoint(
                        "ep_" + String.format("%04d", id++),
                        uniqueName,
                        desc.isEmpty() ? method + " " + path : desc,
                        method.toUpperCase(),
                        path,
                        baseUrl != null ? baseUrl : "",
                        "code_scan",
                        AuthMode.USER_PASSTHROUGH,   // 默认用户身份，可人工修改
                        null,   // credentialKey — 待人工填写
                        null,   // tokenInjection — 待人工配置
                        schema,
                        false,  // confirmed — 草稿状态，必须人工审核后才可启用
                        false   // riskAcknowledged
                ));
            } catch (Exception e) {
                log.debug("[Discovery] Failed to parse endpoint block: {}", e.getMessage());
            }
        }

        return endpoints;
    }

    /**
     * Build a fallback parameters schema when the LLM output is not valid JSON Schema.
     */
    private JsonNode buildFallbackSchema(ObjectMapper mapper, String description) {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = mapper.createObjectNode();
        props.set("params", mapper.createObjectNode()
                .put("type", "string")
                .put("description", description));
        schema.set("properties", props);
        return schema;
    }

    /**
     * Build a unique tool name from path + method name.
     * e.g. /zhiduyuan/course/category/list + list → course_category_list
     * e.g. /zhiduyuan/quiz/bank/{bankId} + getInfo → quiz_bank_getInfo
     */
    private String buildUniqueName(String path, String methodName) {
        // Extract meaningful segments from path (skip prefix like /zhiduyuan)
        String[] segments = path.split("/");
        List<String> parts = new ArrayList<>();
        for (String seg : segments) {
            if (seg.isEmpty() || seg.startsWith("{")) continue;
            // Skip common prefixes
            if (seg.equals("zhiduyuan") || seg.equals("api") || seg.equals("v1")) continue;
            parts.add(seg);
        }
        // Append method name if not already the last segment
        String lastPart = parts.isEmpty() ? "" : parts.get(parts.size() - 1);
        if (!lastPart.equalsIgnoreCase(methodName)) {
            parts.add(methodName);
        }
        return String.join("_", parts);
    }

    /**
     * Extract a field value from a text block (e.g., "- name: getOrderDetail").
     * Supports multi-line values for JSON objects (parameters field).
     */
    private String extractField(String block, String fieldName) {
        String[] lines = block.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            String value = null;
            if (trimmed.startsWith("- " + fieldName + ":")) {
                value = trimmed.substring(fieldName.length() + 3).trim();
            } else if (trimmed.startsWith(fieldName + ":")) {
                value = trimmed.substring(fieldName.length() + 1).trim();
            }
            if (value != null) {
                // For JSON objects, collect multi-line content until closing brace
                if (value.startsWith("{") && !value.endsWith("}")) {
                    StringBuilder sb = new StringBuilder(value);
                    for (int j = i + 1; j < lines.length; j++) {
                        sb.append("\n").append(lines[j]);
                        if (lines[j].trim().endsWith("}")) break;
                    }
                    return sb.toString().trim();
                }
                return value;
            }
        }
        return "";
    }
}
