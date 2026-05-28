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
 */
public class McpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private final ObjectMapper mapper = new ObjectMapper();

    private final ToolSpec spec;
    private final McpServerConfig serverConfig;
    private final OkHttpClient http;

    public McpToolAdapter(ToolSpec spec, McpServerConfig serverConfig) {
        this.spec = spec;
        this.serverConfig = serverConfig;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(serverConfig.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(serverConfig.callTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public String execute(JsonNode arguments) {
        String url = serverConfig.url() + "/tools/" + spec.name() + "/call";
        log.debug("MCP call: {} -> {}", spec.name(), url);

        try {
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
        } catch (IOException e) {
            throw new ToolExecutionException(spec.name(), "MCP call failed: " + e.getMessage(), e);
        }
    }
}
