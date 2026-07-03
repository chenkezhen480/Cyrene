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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
        return new ToolSpec(toolName, desc, params);
    }

    @Override
    public String execute(JsonNode arguments) throws ToolExecutionException {
        log.debug("[HttpApi] Executing {}: {} {}", toolName, endpoint.method(), endpoint.path());

        // 1. Resolve base URL + path
        String url = resolveUrl(arguments);

        // 2. Build request with auth injection
        Request.Builder reqBuilder = new Request.Builder().url(url);
        injectAuth(reqBuilder);
        injectHeaders(reqBuilder, arguments);

        // 3. Attach body for non-GET methods
        String method = endpoint.method().toUpperCase();
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
     * Resolve the full URL by combining baseUrl + path, substituting path params from arguments.
     */
    private String resolveUrl(JsonNode arguments) {
        String base = endpoint.baseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String path = endpoint.path();

        // Substitute path parameters: /orders/{id} → /orders/123
        if (arguments != null) {
            var fieldNames = arguments.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                path = path.replace("{" + key + "}", arguments.get(key).asText());
            }
        }

        // Also try extracting from path itself for patterns like /orders/{orderId}
        // The LLM might pass "orderId" or "id" — both are handled by the loop above

        return base + path;
    }

    /**
     * Inject authentication token into the request based on authMode and tokenInjection.
     */
    private void injectAuth(Request.Builder builder) {
        if (endpoint.authMode() == AuthMode.BOT) {
            // Bot mode: use configured service credential (from env or config)
            // For MVP, bot credentials are passed the same way via credentials map
            // with the endpoint's credentialKey
        }

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
            case "query" -> {
                // Append as query parameter — handled in URL resolution
                String url = builder.build().url().toString();
                String separator = url.contains("?") ? "&" : "?";
                builder.url(url + separator + injection.name() + "=" + token);
            }
            case "cookie" -> builder.addHeader("Cookie", injection.name() + "=" + token);
            default -> builder.addHeader(injection.name(), value);
        }
    }

    /**
     * Inject additional headers from arguments (if any).
     */
    private void injectHeaders(Request.Builder builder, JsonNode arguments) {
        builder.addHeader("Accept", "application/json");
        // If the LLM passes a body for GET, treat query params
        if ("GET".equalsIgnoreCase(endpoint.method()) && arguments != null) {
            // No additional headers needed for GET
        }
    }

    /**
     * Build JSON request body from arguments.
     * Excludes path parameters (already substituted in URL).
     */
    private RequestBody buildJsonBody(JsonNode arguments) {
        if (arguments == null || arguments.isNull() || arguments.isEmpty()) {
            return RequestBody.create("", MediaType.parse("application/json"));
        }
        // Filter out path parameters
        JsonNode bodyArgs = filterPathParams(arguments);
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
