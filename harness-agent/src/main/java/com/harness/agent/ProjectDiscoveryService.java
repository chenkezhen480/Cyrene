package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.react.ReActLoop;
import com.harness.react.ReActLoopFactory;
import com.harness.react.ReActRequest;
import com.harness.react.ReActResult;
import com.harness.core.model.*;
import com.harness.core.runtime.RunTrace;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import com.harness.tool.confirmation.ConfirmationManager;
import com.harness.tool.discovery.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrates the project API discovery flow.
 *
 * <ol>
 *   <li>Probe for OpenAPI/Swagger spec → deterministic parse if found</li>
 *   <li>If no spec, LLM-guided scan using 3 tools:
 *       <ul>
 *         <li>{@code code_glob} — locate controller/route files</li>
 *         <li>{@code code_grep} — find endpoint annotations</li>
 *         <li>{@code read_class_hierarchy} — read DTO/VO classes with inheritance</li>
 *       </ul>
 *   </li>
 *   <li>LLM extracts endpoints and builds JSON Schema from class hierarchy</li>
 * </ol>
 */
public class ProjectDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ProjectDiscoveryService.class);

    private final ReActLoopFactory reActLoopFactory;
    private final ConfirmationManager confirmationManager;

    public ProjectDiscoveryService(ReActLoopFactory reActLoopFactory,
                                   ConfirmationManager confirmationManager) {
        this.reActLoopFactory = java.util.Objects.requireNonNull(
                reActLoopFactory, "reActLoopFactory");
        this.confirmationManager = confirmationManager;
    }

    /**
     * Run the discovery flow on a project root directory.
     *
     * @param sourceRoot absolute path to the project root
     * @param baseUrl    global base URL for all endpoints (nullable)
     * @return discovered endpoints (not yet confirmed by human)
     */
    public ProjectApiConfig discover(Path sourceRoot, String baseUrl) {
        sourceRoot = sourceRoot.toAbsolutePath().normalize();
        log.info("[Discovery] Starting discovery for: {}, baseUrl={}", sourceRoot, baseUrl);

        // Phase 1: Try OpenAPI spec first (deterministic, no LLM)
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

        // Phase 2: No spec — LLM-guided scan with tools
        log.info("[Discovery] No OpenAPI spec found, starting LLM-guided code scan");
        return scanByLLMWithTools(sourceRoot, baseUrl);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Phase 2: LLM-guided scan with 3 tools
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * LLM-guided scan: LLM decides glob/grep patterns, tools execute,
     * then read_class_hierarchy for DTO/VO classes.
     */
    private ProjectApiConfig scanByLLMWithTools(Path sourceRoot, String baseUrl) {
        // Build tool registry with 3 discovery tools
        Set<String> excludes = Set.of();  // sensitive file exclusions are built into each tool
        ToolRegistry discoveryRegistry = new ToolRegistry();
        discoveryRegistry.register(new CodeGlobTool(sourceRoot, excludes));
        discoveryRegistry.register(new CodeGrepTool(sourceRoot, excludes));
        discoveryRegistry.register(new ReadClassHierarchyTool(sourceRoot));
        ToolExecutor discoveryExecutor = new ToolExecutor(confirmationManager);

        ReActLoop reActLoop = reActLoopFactory.create(
                discoveryRegistry.snapshot(), discoveryExecutor);

        String systemPrompt = buildDiscoveryPrompt(sourceRoot);
        String userMessage = "请扫描此项目，发现所有 REST API 接口并构建完整的参数 JSON Schema。";

        CancellationToken cancellationToken = new CancellationToken();

        try {
            ReActResult result = reActLoop.execute(new ReActRequest(
                    systemPrompt,
                    userMessage,
                    List.of(),
                    RunTrace.noop(),
                    null,
                    cancellationToken,
                    null,
                    null));

            List<ApiEndpoint> endpoints = parseEndpointsFromOutput(result.output(), sourceRoot, baseUrl);
            String projectDescription = extractProjectDescription(result.output());
            if (projectDescription == null || projectDescription.isBlank()) {
                projectDescription = sourceRoot.getFileName().toString();
            }

            int toolCalls = result.steps().stream().mapToInt(s -> s.toolCalls().size()).sum();
            log.info("[Discovery] Scan complete: {} endpoints, {} tool calls, description: {}",
                    endpoints.size(), toolCalls, projectDescription);

            return new ProjectApiConfig(
                    Instant.now().toString(),
                    projectDescription,
                    baseUrl,
                    sourceRoot.toAbsolutePath().toString(),
                    endpoints
            );

        } catch (Exception e) {
            log.error("[Discovery] Scan failed: {}", e.getMessage(), e);
            return new ProjectApiConfig(
                    Instant.now().toString(),
                    sourceRoot.getFileName().toString(),
                    baseUrl,
                    sourceRoot.toAbsolutePath().toString(),
                    List.of()
            );
        }
    }

    /**
     * Build the system prompt for LLM-guided discovery.
     */
    private String buildDiscoveryPrompt(Path sourceRoot) {
        return """
            你是一个代码扫描 agent，任务是发现项目中的所有 REST API 接口。

            项目路径: %s

            你可以使用以下工具：
            - code_glob(pattern) — 按 glob 模式查找文件，返回匹配的文件路径列表
            - code_grep(regex, glob?) — 按正则搜索文件内容，返回匹配行及上下文（±7行）
            - read_class_hierarchy(className) — 读取一个类及其父类（最多2层），返回合并后的字段列表和 JSON Schema

            ═══ 工作流程 ═══

            第一步：定位控制器文件
              用 code_glob 找到所有控制器/路由文件。
              示例：code_glob("**/*Controller.java")

            第二步：搜索接口注解
              用 code_grep 在控制器文件中搜索路由注解。
              示例：code_grep(regex="@GetMapping|@PostMapping|@PutMapping|@DeleteMapping", glob="**/*Controller.java")

            第三步：读取 DTO/VO 类结构
              从第二步的结果中，识别出参数类型名称（如 UserDTO、CourseVO、QueryForm 等）。
              对每个类型调用 read_class_hierarchy 获取完整字段结构（含继承的父类字段）。
              示例：read_class_hierarchy(className="UserDTO")

            第四步：输出结果
              整合所有信息，输出结构化的接口定义。

            ═══ 输出格式（严格遵守，否则解析失败）═══

            第一行输出项目描述：
            PROJECT: <一行项目描述>

            然后每个接口严格按以下格式输出，不要加 markdown 标题、编号、粗体等任何修饰：

            ENDPOINT:
            - name: 接口名称
            - description: 接口描述
            - method: GET
            - path: /api/xxx
            - parameters: {"type":"object","properties":{}}
            - returnType: R<Void>

            ⚠️ 绝对禁止：
            - 不要用 "### ENDPOINT:" 或 "### 1." 等 markdown 标题
            - 不要用 "- **name**: xxx" 等粗体格式
            - 不要用 ```json``` 代码块包裹 parameters
            - 不要加 "---" 分隔线
            - 不要输出项目概述、模块说明等额外内容
            - parameters 必须是行内 JSON，直接跟在 "- parameters: " 后面

            ✅ 正确示例：
            PROJECT: 若依管理系统-用户权限模块

            ENDPOINT:
            - name: 查询用户列表
            - description: 分页查询用户列表
            - method: GET
            - path: /system/user/list
            - parameters: {"type":"object","properties":{"userName":{"type":"string"},"status":{"type":"string"}}}
            - returnType: TableDataInfo<UserVO>

            ENDPOINT:
            - name: 新增用户
            - description: 新增用户信息
            - method: POST
            - path: /system/user
            - parameters: {"type":"object","properties":{"userName":{"type":"string"},"nickName":{"type":"string"}}}
            - returnType: R<Void>

            ⚠️ 工具使用规则：
            - 必须为每个 DTO/VO 类调用 read_class_hierarchy，确保参数结构完整
            - 必须提取所有接口，不要遗漏
            - read_class_hierarchy 返回的 JSON Schema 直接用作 parameters 值
            - 无参数时用：{"type":"object","properties":{}}
            - read_class_hierarchy 能正常返回结果时，继续用它读取下一个类
            - 如果 read_class_hierarchy 返回 "Class not found"，先用 code_glob 查找文件路径，再重试
            - 尽量在一次响应中调用多个工具（如同时读取多个 DTO 类），减少轮次
            """.formatted(sourceRoot);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Output parsing
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Apply baseUrl at config level. Endpoint-level baseUrl is cleared (use global).
     */
    private ProjectApiConfig applyBaseUrl(ProjectApiConfig config, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return config;
        List<ApiEndpoint> updated = config.endpoints().stream()
                .map(ep -> new ApiEndpoint(ep.id(), ep.name(), ep.description(), ep.method(),
                        ep.path(), null, ep.source(), ep.authMode(), ep.credentialKey(),
                        ep.tokenInjection(), ep.parameters(), ep.confirmed(), ep.riskAcknowledged()))
                .toList();
        return new ProjectApiConfig(config.discoveredAt(), config.projectDescription(), baseUrl, config.projectRoot(), updated);
    }

    /**
     * Extract project description from the LLM's output.
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
     */
    private List<ApiEndpoint> parseEndpointsFromOutput(String output, Path sourceRoot, String baseUrl) {
        if (output == null || output.isBlank()) return List.of();

        List<ApiEndpoint> endpoints = new ArrayList<>();
        var mapper = new ObjectMapper();
        // Split by ENDPOINT: — supports "ENDPOINT:", "### ENDPOINT:", "### ENDPOINT: name"
        String[] blocks = output.split("(?m)^#{0,3}\\s*ENDPOINT[:\\s]");
        // Fallback: if no ENDPOINT markers found, split by markdown H3 headings (### N. or ### Title)
        if (blocks.length < 2) {
            blocks = output.split("(?m)^#{1,3}\\s+(?!.*PROJECT)(?!.*项目描述)");
        }
        int id = 1;

        for (String block : blocks) {
            if (block.isBlank()) continue;
            try {
                // If name is on the same line as ENDPOINT (e.g. "### ENDPOINT: 查询列表\n...")
                String firstLine = block.lines().findFirst().orElse("").trim();
                String inlineName = firstLine.replaceAll("^[-\\s]+", "").trim();

                String name = extractField(block, "name");
                if (name.isEmpty() && !inlineName.isEmpty()) {
                    name = inlineName.split("\n")[0].trim(); // take only the first line
                }
                String desc = extractField(block, "description");
                String method = extractField(block, "method");
                String path = extractField(block, "path");
                String params = extractField(block, "parameters");
                String returnType = extractField(block, "returnType");

                if (name.isEmpty() || path.isEmpty()) continue;
                if (method.isEmpty()) method = "GET";

                JsonNode schema;
                if (!params.isEmpty()) {
                    String trimmed = params.trim();
                    if (trimmed.startsWith("{")) {
                        try {
                            schema = mapper.readTree(trimmed);
                        } catch (Exception e) {
                            schema = buildFallbackSchema(mapper, params);
                        }
                    } else {
                        schema = buildFallbackSchema(mapper, params);
                    }
                } else {
                    schema = buildFallbackSchema(mapper, "无参数");
                }

                if (returnType != null && !returnType.isBlank() && schema.isObject()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) schema).put("x-returnType", returnType);
                }

                String uniqueName = buildUniqueName(path, name);

                endpoints.add(new ApiEndpoint(
                        "ep_" + String.format("%04d", id++),
                        uniqueName,
                        desc.isEmpty() ? method + " " + path : desc,
                        method.toUpperCase(),
                        path,
                        null,   // baseUrl — 使用全局 config.baseUrl
                        "code_scan",
                        AuthMode.USER_PASSTHROUGH,
                        "Authorization",
                        new TokenInjection("header", "Authorization", "Bearer "),
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
     */
    private String buildUniqueName(String path, String methodName) {
        String[] segments = path.split("/");
        List<String> parts = new ArrayList<>();
        for (String seg : segments) {
            if (seg.isEmpty() || seg.startsWith("{")) continue;
            if (seg.equals("zhiduyuan") || seg.equals("api") || seg.equals("v1")) continue;
            parts.add(seg);
        }
        String lastPart = parts.isEmpty() ? "" : parts.get(parts.size() - 1);
        if (!lastPart.equalsIgnoreCase(methodName)) {
            parts.add(methodName);
        }
        return String.join("_", parts);
    }

    /**
     * Extract a field value from a text block.
     */
    private String extractField(String block, String fieldName) {
        String[] lines = block.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            String value = null;
            // Support: "- name:", "- **name**:", "name:", "**name**:"
            String boldField = "**" + fieldName + "**";
            if (trimmed.startsWith("- " + boldField + ":")) {
                value = trimmed.substring(boldField.length() + 3).trim();
            } else if (trimmed.startsWith("- " + fieldName + ":")) {
                value = trimmed.substring(fieldName.length() + 3).trim();
            } else if (trimmed.startsWith(boldField + ":")) {
                value = trimmed.substring(boldField.length() + 1).trim();
            } else if (trimmed.startsWith(fieldName + ":")) {
                value = trimmed.substring(fieldName.length() + 1).trim();
            }
            if (value != null) {
                // Handle inline JSON
                if (value.startsWith("{") && !value.endsWith("}")) {
                    StringBuilder sb = new StringBuilder(value);
                    for (int j = i + 1; j < lines.length; j++) {
                        sb.append("\n").append(lines[j]);
                        if (lines[j].trim().endsWith("}")) break;
                    }
                    return sb.toString().trim();
                }
                // Handle ```json code block — extract JSON from the block
                if (value.startsWith("```") || value.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    boolean inBlock = false;
                    for (int j = i + 1; j < lines.length; j++) {
                        String l = lines[j].trim();
                        if (l.startsWith("```") && inBlock) break;
                        if (l.startsWith("```")) { inBlock = true; continue; }
                        if (inBlock) sb.append(l).append("\n");
                        // Also catch bare JSON after the field line
                        if (!inBlock && l.startsWith("{")) {
                            sb.append(l).append("\n");
                            for (int k = j + 1; k < lines.length; k++) {
                                sb.append(lines[k].trim()).append("\n");
                                if (lines[k].trim().endsWith("}")) break;
                            }
                            break;
                        }
                    }
                    String extracted = sb.toString().trim();
                    if (!extracted.isEmpty()) return extracted;
                }
                return value;
            }
        }
        return "";
    }
}
