package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.AuthMode;
import com.harness.core.model.ToolSpec;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic HTTP API executor for discovered project endpoints.
 * Each instance wraps one {@link ApiEndpoint} declaration and acts as a tool
 * that the LLM can invoke via the ReAct loop.
 *
 * <p>Credentials are supplied via ThreadLocal (set by AgentOrchestrator before each run),
 * following the same pattern as {@code SavePreferenceTool}.
 */
public class HttpApiTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(HttpApiTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^{}]+)}");

    /** Shared OkHttpClient — connection pooling across all HttpApiTool instances. */
    private static final OkHttpClient SHARED_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /** Per-request credentials map, set by AgentOrchestrator. */
    private static final ThreadLocal<Map<String, String>> CURRENT_CREDENTIALS = new ThreadLocal<>();

    private final ApiEndpoint endpoint;
    private final String toolName;

    public HttpApiTool(ApiEndpoint endpoint) {
        this.endpoint = endpoint;
        // Tool name: endpoint name prefixed to avoid collisions with built-in tools
        this.toolName = "http_" + endpoint.name();
    }

    public static void setCurrentCredentials(Map<String, String> credentials) {
        CURRENT_CREDENTIALS.set(credentials);
    }

    public static void clearCurrentCredentials() {
        CURRENT_CREDENTIALS.remove();
    }

    /**
     * Get a snapshot of the current credentials (for propagation to sub-agent threads).
     * Returns an empty map if no credentials are set.
     */
    public static Map<String, String> getCurrentCredentialsSnapshot() {
        Map<String, String> creds = CURRENT_CREDENTIALS.get();
        return creds != null ? Map.copyOf(creds) : Map.of();
    }

    @Override
    public ToolSpec spec() {
        JsonNode params = endpoint.parameters() != null ? endpoint.parameters()
                : mapper.createObjectNode().put("type", "object");
        String desc = endpoint.description() != null ? endpoint.description()
                : "Call " + endpoint.method() + " " + endpoint.path();
        String method = endpoint.method() == null ? "" : endpoint.method().toUpperCase(Locale.ROOT);
        com.harness.core.model.ToolCapability capability = switch (method) {
            case "GET", "HEAD" -> com.harness.core.model.ToolCapability.READ;
            default -> com.harness.core.model.ToolCapability.MUTATION;
        };
        return new ToolSpec(toolName, desc, params, capability);
    }

    @Override
    public String execute(JsonNode arguments) throws ToolExecutionException {
        log.debug("[HttpApi] Executing {}: {} {}", toolName, endpoint.method(), endpoint.path());

        String method = endpoint.method().toUpperCase(Locale.ROOT);
        HttpUrl url = resolveUrl(arguments, method);

        // 2. Build request with auth injection
        Request.Builder reqBuilder = new Request.Builder().url(url).addHeader("Accept", "application/json");
        injectAuth(reqBuilder);

        // 3. Attach body for methods that accept one
        switch (method) {
            case "GET", "HEAD", "DELETE" -> reqBuilder.method(method, null);
            case "POST" -> reqBuilder.post(buildJsonBody(arguments));
            case "PUT" -> reqBuilder.put(buildJsonBody(arguments));
            case "PATCH" -> reqBuilder.patch(buildJsonBody(arguments));
            default -> reqBuilder.method(method, buildJsonBody(arguments));
        }

        // 4. Execute
        try (Response response = SHARED_CLIENT.newCall(reqBuilder.build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String errMsg = "HTTP " + response.code() + ": " + truncate(body, 500);
                log.warn("[HttpApi] {} returned error: {}", toolName, errMsg);
                throw new ToolExecutionException(toolName, errMsg);
            }
            log.debug("[HttpApi] {} succeeded: {} chars", toolName, body.length());
            return truncate(body, 4000);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (IOException e) {
            log.error("[HttpApi] {} network error: {}", toolName, e.getMessage());
            throw new ToolExecutionException(toolName, "Network error: " + e.getMessage());
        }
    }

    /**
     * Resolve path values as single segments and attach query arguments for bodyless methods.
     */
    private HttpUrl resolveUrl(JsonNode arguments, String method) {
        String base = endpoint.baseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpUrl url = HttpUrl.get(base + endpoint.path());
        HttpUrl.Builder builder = url.newBuilder();
        for (int i = 0; i < url.pathSegments().size(); i++) {
            Matcher matcher = PATH_PARAMETER.matcher(url.pathSegments().get(i));
            if (!matcher.find()) continue;
            String segment = matcher.replaceAll(match -> {
                String name = match.group(1);
                JsonNode value = arguments != null ? arguments.get(name) : null;
                if (value == null || value.isNull() || !value.isValueNode()) {
                    throw new ToolExecutionException(toolName, "Path parameter '" + name + "' requires a scalar value");
                }
                return Matcher.quoteReplacement(value.asText());
            });
            builder.setPathSegment(i, segment);
        }

        if (arguments != null && ("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method))) {
            var fields = arguments.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String name = field.getKey();
                JsonNode property = endpoint.parameters() != null
                        ? endpoint.parameters().path("properties").path(name) : null;
                if (endpoint.path().contains("{" + name + "}") || "__body__".equals(name)
                        || (property != null && property.has("in") && !"query".equals(property.path("in").asText()))) {
                    continue;
                }
                addQueryParameter(builder, name, field.getValue());
            }
        }
        return builder.build();
    }

    private void addQueryParameter(HttpUrl.Builder builder, String name, JsonNode value) {
        Iterable<JsonNode> values = value.isArray() ? value : List.of(value);
        for (JsonNode item : values) {
            if (item.isNull()) continue;
            if (!item.isValueNode()) {
                throw new ToolExecutionException(toolName, "Query parameter '" + name + "' requires scalar values");
            }
            builder.addQueryParameter(name, item.asText());
        }
    }

    /**
     * Inject authentication token into the request based on authMode and tokenInjection.
     */
    private void injectAuth(Request.Builder builder) {
        // Both BOT and USER_PASSTHROUGH read from credentials map
        Map<String, String> creds = CURRENT_CREDENTIALS.get();
        if (creds == null || creds.isEmpty()) {
            if (endpoint.authMode() == AuthMode.USER_PASSTHROUGH) {
                log.warn("[HttpApi] No credentials available for user_passthrough endpoint {}", toolName);
                throw new ToolExecutionException(toolName,
                        "Missing credentials (credentialKey: " + endpoint.credentialKey() + "). " +
                        "The calling client must pass credentials in context.credentials.");
            }
            return;
        }

        String credentialKey = endpoint.credentialKey();
        if (credentialKey == null || credentialKey.isBlank()) return;

        String token = creds.get(credentialKey);
        if (token == null || token.isBlank()) {
            if (endpoint.authMode() == AuthMode.USER_PASSTHROUGH) {
                log.warn("[HttpApi] Credential '{}' not found in credentials map for {}", credentialKey, toolName);
                throw new ToolExecutionException(toolName,
                        "Missing credential '" + credentialKey + "'. " +
                        "Ensure context.credentials contains a '" + credentialKey + "' entry.");
            }
            return;
        }

        // Inject token per tokenInjection config
        var injection = endpoint.tokenInjection();
        if (injection == null) {
            // Default: Authorization header with Bearer prefix
            builder.addHeader("Authorization", "Bearer " + token);
            return;
        }

        String prefix = injection.prefix() != null ? injection.prefix() : "";
        String value = prefix + token;

        switch (injection.location() != null ? injection.location() : "header") {
            case "header" -> builder.addHeader(injection.name(), value);
            case "query" -> builder.url(builder.build().url().newBuilder()
                    .setQueryParameter(injection.name(), value).build());
            case "cookie" -> builder.addHeader("Cookie", injection.name() + "=" + token);
            default -> builder.addHeader(injection.name(), value);
        }
    }

    /**
     * Build JSON request body from arguments.
     * Unwraps OpenAPI JSON bodies; flat arguments exclude substituted path parameters.
     */
    private RequestBody buildJsonBody(JsonNode arguments) {
        if (arguments == null || arguments.isNull() || arguments.isEmpty()) {
            return RequestBody.create("", MediaType.parse("application/json"));
        }
        JsonNode bodyArgs = arguments.has("__body__") ? arguments.get("__body__") : filterPathParams(arguments);
        String json = bodyArgs.toString();
        return RequestBody.create(json, MediaType.parse("application/json"));
    }

    /**
     * Remove path parameters from the arguments object (they're already in the URL).
     */
    private JsonNode filterPathParams(JsonNode arguments) {
        String path = endpoint.path();
        var filtered = mapper.createObjectNode();
        var fieldNames = arguments.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            if (!path.contains("{" + key + "}")) {
                filtered.set(key, arguments.get(key));
            }
        }
        return filtered;
    }

    /**
     * Truncate string to max length.
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * Get the endpoint config (for registration/reload purposes).
     */
    public ApiEndpoint endpoint() {
        return endpoint;
    }
}
