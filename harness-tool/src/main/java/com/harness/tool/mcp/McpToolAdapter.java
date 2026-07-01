package com.harness.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Adapter that wraps an MCP server tool as a local Tool instance.
 * Calls the MCP server via HTTP to execute tool operations.
 * Supports transparent reconnect: if a tool call fails due to a closed/broken
 * connection (IOException), the adapter creates a fresh OkHttpClient and retries
 * the call once before propagating the failure.
 */
public class McpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private final ObjectMapper mapper = new ObjectMapper();

    private final ToolSpec spec;
    private final McpServerConfig serverConfig;
    private volatile OkHttpClient http;
    private final String mcpToolName;  // Original tool name on the MCP server (without prefix)

    public McpToolAdapter(ToolSpec spec, McpServerConfig serverConfig) {
        this.spec = spec;
        this.serverConfig = serverConfig;
        this.http = buildHttpClient();
        // Extract original MCP tool name: strip "mcp_{serverName}_" prefix
        String prefix = "mcp_" + serverConfig.name() + "_";
        this.mcpToolName = spec.name().startsWith(prefix)
                ? spec.name().substring(prefix.length())
                : spec.name();
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public String execute(JsonNode arguments) {
        String url = serverConfig.url() + "/tools/" + mcpToolName + "/call";
        log.debug("MCP call: {} -> {}", spec.name(), url);

        try {
            return doCall(url, arguments);
        } catch (IOException e) {
            // First failure — attempt transparent reconnect and retry once
            log.warn("MCP call failed for {}, attempting reconnect: {}", spec.name(), e.getMessage());
            try {
                reconnect();
                String result = doCall(url, arguments);
                log.info("MCP reconnect succeeded for {}", spec.name());
                return result;
            } catch (Exception retryEx) {
                log.error("MCP reconnect failed for {}: {}", spec.name(),
                        retryEx.getMessage());
                throw new ToolExecutionException(spec.name(),
                        "MCP call failed after reconnect: " + retryEx.getMessage(), retryEx);
            }
        }
    }

    /**
     * Execute a single MCP HTTP call. Throws IOException on connection-level failures.
     */
    private String doCall(String url, JsonNode arguments) throws IOException {
        String body = mapper.writeValueAsString(arguments);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON_TYPE))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new ToolExecutionException(spec.name(),
                        "MCP server error " + response.code() + ": " + respBody);
            }
            return respBody;
        }
    }

    /**
     * Reconnect by creating a fresh OkHttpClient, discarding any stale pooled connections.
     */
    private void reconnect() {
        log.debug("MCP reconnecting to server '{}' (new HTTP client)", serverConfig.name());
        this.http = buildHttpClient();
    }

    private OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(serverConfig.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(serverConfig.callTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }
}
