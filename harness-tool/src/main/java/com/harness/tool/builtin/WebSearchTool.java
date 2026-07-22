package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.tool.Tool;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Built-in web search tool backed by a self-hosted SearXNG instance.
 * SearXNG aggregates results from 70+ search engines (Google, Bing, DuckDuckGo, etc.)
 * with no API key required.
 */
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http;
    private final String baseUrl;

    public WebSearchTool() {
        EnvConfig cfg = EnvConfig.get();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        this.baseUrl = cfg.getString(EnvKey.TOOL_WEB_SEARCH_SEARXNG_URL, "http://localhost:8888")
                .replaceAll("/+$", "");
        log.info("WebSearch initialized, SearXNG endpoint: {}", baseUrl);
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "web_search",
                "Search the web for real-time information. Use this tool when: the user asks about current events, news, recent developments, today's weather/stock/price, or any question that requires up-to-date information not in your training data. Also use when the user explicitly asks to search or look up something online.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("query",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Search query")))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                                mapper.createArrayNode().add("query"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String query = arguments.has("query") ? arguments.get("query").asText() : null;
        if (query == null || query.isBlank()) {
            throw new ToolExecutionException("web_search", "Missing required parameter: query");
        }

        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/search?q=" + encoded + "&format=json&categories=general";

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build();

        log.debug("Web search: query={}", query);

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("SearXNG HTTP " + response.code() + ": " + body);
            }
            String result = formatResponse(body, query);
            if (result.startsWith("No results found")) {
                ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
            } else {
                ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            }
            return result;
        } catch (IOException e) {
            throw new ToolExecutionException("web_search",
                    "SearXNG request failed (" + baseUrl + "): " + e.getMessage());
        }
    }

    private String formatResponse(String json, String query) throws IOException {
        JsonNode root = mapper.readTree(json);
        StringBuilder sb = new StringBuilder();

        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return "No results found for: " + query;
        }

        int rank = 0;
        for (JsonNode r : results) {
            if (rank >= 8) break;
            rank++;

            String title = r.path("title").asText("").trim();
            String resultUrl = r.path("url").asText("").trim();
            String content = r.path("content").asText("").trim();

            if (title.isBlank() && resultUrl.isBlank()) continue;

            sb.append(rank).append(". ").append(title).append("\n");
            if (!resultUrl.isBlank()) {
                sb.append("   URL: ").append(resultUrl).append("\n");
            }
            if (!content.isBlank()) {
                sb.append("   ").append(content.length() > 300 ? content.substring(0, 300) + "..." : content).append("\n");
            }
            sb.append("\n");
        }

        // Suggestions (related queries)
        JsonNode suggestions = root.get("suggestions");
        if (suggestions != null && suggestions.isArray() && !suggestions.isEmpty()) {
            sb.append("Related searches: ");
            int count = 0;
            for (JsonNode s : suggestions) {
                if (count >= 3) break;
                if (count > 0) sb.append(", ");
                sb.append(s.asText());
                count++;
            }
            sb.append("\n");
        }

        return sb.toString().isBlank() ? "No results found for: " + query : sb.toString().trim();
    }
}
