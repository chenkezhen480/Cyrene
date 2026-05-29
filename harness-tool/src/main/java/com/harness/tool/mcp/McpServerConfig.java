package com.harness.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Parses MCP server configurations from either a JSON file or environment variables.
 *
 * <p>Loading priority:
 * <ol>
 *   <li>{@code HARNESS_MCP_CONFIG_FILE} — path to a JSON file</li>
 *   <li>{@code HARNESS_MCP_SERVERS} — comma-separated {@code name=url} pairs</li>
 * </ol>
 *
 * <p>JSON format:
 * <pre>{@code
 * {
 *   "filesystem": { "url": "http://localhost:3001" },
 *   "github":     { "url": "https://mcp.github.com", "connectTimeoutMs": 10000, "callTimeoutMs": 60000 }
 * }
 * }</pre>
 */
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String name;
    private final String url;
    private final int connectTimeoutMs;
    private final int callTimeoutMs;

    public McpServerConfig(String name, String url, int connectTimeoutMs, int callTimeoutMs) {
        this.name = name;
        this.url = url;
        this.connectTimeoutMs = connectTimeoutMs;
        this.callTimeoutMs = callTimeoutMs;
    }

    public String name() { return name; }
    public String url() { return url; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int callTimeoutMs() { return callTimeoutMs; }

    /**
     * Load all MCP server configs: JSON file first, then env var fallback.
     */
    public static List<McpServerConfig> loadAll() {
        EnvConfig cfg = EnvConfig.get();
        int defaultConnect = cfg.getInt(EnvKey.MCP_CONNECT_TIMEOUT, 5000);
        int defaultCall = cfg.getInt(EnvKey.MCP_CALL_TIMEOUT, 30000);

        // Priority 1: JSON config file
        String configFile = cfg.getString(EnvKey.MCP_CONFIG_FILE);
        if (configFile != null && !configFile.isBlank()) {
            List<McpServerConfig> fromFile = loadFromJson(configFile, defaultConnect, defaultCall);
            if (fromFile != null) {
                return fromFile;
            }
            log.warn("JSON config file '{}' failed, falling back to env var", configFile);
        }

        // Priority 2: env var
        return loadFromEnv(cfg, defaultConnect, defaultCall);
    }

    private static List<McpServerConfig> loadFromJson(String path, int defaultConnect, int defaultCall) {
        File file = new File(path);
        if (!file.exists()) {
            log.error("MCP config file not found: {}", path);
            return null;
        }

        try {
            JsonNode root = mapper.readTree(file);
            List<McpServerConfig> configs = new ArrayList<>();

            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                JsonNode node = entry.getValue();

                String url = node.has("url") ? node.get("url").asText() : null;
                if (url == null || url.isBlank()) {
                    log.warn("MCP server '{}': missing 'url', skipping", name);
                    continue;
                }

                int connect = node.has("connectTimeoutMs") ? node.get("connectTimeoutMs").asInt(defaultConnect) : defaultConnect;
                int call = node.has("callTimeoutMs") ? node.get("callTimeoutMs").asInt(defaultCall) : defaultCall;

                configs.add(new McpServerConfig(name, url, connect, call));
            }

            log.info("Loaded {} MCP server(s) from {}", configs.size(), path);
            return configs;
        } catch (Exception e) {
            log.error("Failed to parse MCP config file '{}': {}", path, e.getMessage(), e);
            return null;
        }
    }

    private static List<McpServerConfig> loadFromEnv(EnvConfig cfg, int defaultConnect, int defaultCall) {
        List<String> servers = cfg.getCommaList(EnvKey.MCP_SERVERS);
        List<McpServerConfig> configs = new ArrayList<>();
        for (String server : servers) {
            String[] parts = server.split("=", 2);
            if (parts.length == 2) {
                configs.add(new McpServerConfig(parts[0].trim(), parts[1].trim(), defaultConnect, defaultCall));
            } else {
                log.warn("Invalid MCP server config: {}", server);
            }
        }
        return configs;
    }
}
