package com.harness.tool.mcp;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses HARNESS_MCP_SERVERS into a list of server configs.
 * Format: name1=url1,name2=url2
 */
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

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
     * Load all MCP server configs from environment.
     */
    public static List<McpServerConfig> loadAll() {
        EnvConfig cfg = EnvConfig.get();
        List<String> servers = cfg.getCommaList(EnvKey.MCP_SERVERS);
        int connectTimeout = cfg.getInt(EnvKey.MCP_CONNECT_TIMEOUT, 5000);
        int callTimeout = cfg.getInt(EnvKey.MCP_CALL_TIMEOUT, 30000);

        List<McpServerConfig> configs = new ArrayList<>();
        for (String server : servers) {
            String[] parts = server.split("=", 2);
            if (parts.length == 2) {
                configs.add(new McpServerConfig(parts[0].trim(), parts[1].trim(), connectTimeout, callTimeout));
            } else {
                log.warn("Invalid MCP server config: {}", server);
            }
        }
        return configs;
    }
}
