package com.harness.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ToolSpec;
import com.harness.tool.ToolRegistry;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Discovers and registers tools from MCP servers via the JSON-RPC tools/list method.
 * Results are cached per server so discovery happens only once.
 */
public class McpToolDiscovery {

    private static final Logger log = LoggerFactory.getLogger(McpToolDiscovery.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger nextId = new AtomicInteger(1);

    /** Cache: server name -> list of discovered McpToolAdapter instances */
    private final Map<String, List<McpToolAdapter>> discoveredCache = new ConcurrentHashMap<>();

    /**
     * Discover tools from all configured MCP servers and register them.
     */
    public void discoverAndRegister(List<McpServerConfig> servers, ToolRegistry registry) {
        for (McpServerConfig server : servers) {
            try {
                discoverAndRegisterServer(server, registry);
            } catch (Exception e) {
                log.warn("Failed to discover tools from MCP server '{}': {}", server.name(), e.getMessage());
            }
        }
    }

    /**
     * Discover tools from a single MCP server and register them.
     * Uses cache to avoid rediscovering on repeated calls.
     */
    private void discoverAndRegisterServer(McpServerConfig server, ToolRegistry registry) {
        List<McpToolAdapter> cached = discoveredCache.get(server.name());
        if (cached != null) {
            log.debug("MCP server '{}': using {} cached tools", server.name(), cached.size());
            for (McpToolAdapter adapter : cached) {
                registry.register(adapter);
            }
            return;
        }

        List<McpToolAdapter> adapters = discoverTools(server);
        discoveredCache.put(server.name(), adapters);

        for (McpToolAdapter adapter : adapters) {
            registry.register(adapter);
            log.info("Registered MCP tool: {} (from {})", adapter.spec().name(), server.name());
        }
        log.info("MCP server '{}': discovered and registered {} tools", server.name(), adapters.size());
    }

    /**
     * Send tools/list JSON-RPC request and parse response into McpToolAdapter instances.
     */
    private List<McpToolAdapter> discoverTools(McpServerConfig server) {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(server.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(server.callTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();

        String requestBody;
        try {
            requestBody = mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", nextId.getAndIncrement(),
                    "method", "tools/list",
                    "params", Map.of()
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build JSON-RPC request", e);
        }

        Request request = new Request.Builder()
                .url(server.url())
                .post(RequestBody.create(requestBody, JSON_TYPE))
                .build();

        String responseBody;
        try (Response response = http.newCall(request).execute()) {
            responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + responseBody);
            }
        } catch (IOException e) {
            throw new RuntimeException("MCP tools/list request failed: " + e.getMessage(), e);
        }

        return parseToolsList(responseBody, server);
    }

    /**
     * Parse the JSON-RPC tools/list response and create McpToolAdapter instances.
     * Expected response format:
     * {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"...","description":"...","inputSchema":{...}}]}}
     */
    private List<McpToolAdapter> parseToolsList(String json, McpServerConfig server) {
        try {
            JsonNode root = mapper.readTree(json);

            // Check for JSON-RPC error
            JsonNode error = root.get("error");
            if (error != null) {
                String message = error.path("message").asText("Unknown error");
                int code = error.path("code").asInt(-1);
                throw new RuntimeException("MCP error " + code + ": " + message);
            }

            JsonNode result = root.get("result");
            if (result == null) {
                log.warn("MCP server '{}': tools/list response missing 'result' field", server.name());
                return List.of();
            }

            JsonNode tools = result.get("tools");
            if (tools == null || !tools.isArray()) {
                log.warn("MCP server '{}': tools/list result missing 'tools' array", server.name());
                return List.of();
            }

            List<McpToolAdapter> adapters = new ArrayList<>();
            for (JsonNode toolNode : tools) {
                try {
                    String name = toolNode.path("name").asText("");
                    String description = toolNode.path("description").asText("");
                    JsonNode inputSchema = toolNode.get("inputSchema");

                    if (name.isBlank()) {
                        log.warn("MCP server '{}': skipping tool with empty name", server.name());
                        continue;
                    }

                    // Ensure inputSchema has proper structure for LLM consumption
                    if (inputSchema == null || inputSchema.isNull()) {
                        inputSchema = mapper.createObjectNode()
                                .put("type", "object")
                                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                        mapper.createObjectNode());
                    }

                    ToolSpec spec = new ToolSpec(
                            "mcp_" + server.name() + "_" + name,
                            "[MCP:" + server.name() + "] " + description,
                            inputSchema
                    );
                    adapters.add(new McpToolAdapter(spec, server));
                } catch (Exception e) {
                    log.warn("MCP server '{}': failed to parse tool definition: {}",
                            server.name(), e.getMessage());
                }
            }
            return adapters;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse tools/list response: " + e.getMessage(), e);
        }
    }
}
