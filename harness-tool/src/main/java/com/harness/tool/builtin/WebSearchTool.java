package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Built-in web search tool with multi-engine fallback chain.
 * Engines are tried in priority order; engines without API keys are skipped.
 * DuckDuckGo is always available as the last resort (no API key required).
 */
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http;
    private final List<SearchEngine> engines;

    public WebSearchTool() {
        EnvConfig cfg = EnvConfig.get();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        // Build engines from priority config
        String tavilyKey = cfg.getString(EnvKey.TOOL_WEB_SEARCH_TAVILY_API_KEY,
                cfg.getString(EnvKey.TOOL_WEB_SEARCH_API_KEY, ""));
        String serpApiKey = cfg.getString(EnvKey.TOOL_WEB_SEARCH_SERPAPI_API_KEY, "");

        List<SearchEngine> allEngines = new ArrayList<>();
        allEngines.add(new TavilySearchEngine(tavilyKey));
        allEngines.add(new SerpApiSearchEngine(serpApiKey));
        allEngines.add(new DuckDuckGoSearchEngine());

        // Parse priority list, default "tavily,serpapi,duckduckgo"
        List<String> priority = cfg.getCommaList(EnvKey.TOOL_WEB_SEARCH_PRIORITY);
        if (priority.isEmpty()) {
            priority = List.of("tavily", "serpapi", "duckduckgo");
        }

        // Order engines by priority, append any unlisted engines at the end
        this.engines = new ArrayList<>();
        List<SearchEngine> remaining = new ArrayList<>(allEngines);
        for (String name : priority) {
            SearchEngine found = findAndRemove(remaining, name);
            if (found != null) {
                engines.add(found);
            }
        }
        engines.addAll(remaining);

        log.info("WebSearch engines in order: {}", engines.stream().map(SearchEngine::name).toList());
    }

    private static SearchEngine findAndRemove(List<SearchEngine> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().equalsIgnoreCase(name)) {
                return list.remove(i);
            }
        }
        return null;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "web_search",
                "Search the web for current information",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("query",
                                                mapper.createObjectNode().put("type", "string").put("description", "Search query")))
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

        List<String> skipped = new ArrayList<>();
        for (SearchEngine engine : engines) {
            if (!engine.isAvailable()) {
                skipped.add(engine.name());
                log.debug("Skipping {}: not available (missing API key)", engine.name());
                continue;
            }
            try {
                log.debug("Web search: engine={}, query={}", engine.name(), query);
                String result = engine.search(query);
                if (result != null && !result.isBlank()) {
                    return result;
                }
                log.warn("{} returned empty result for: {}", engine.name(), query);
            } catch (Exception e) {
                log.warn("{} failed for query '{}': {}", engine.name(), query, e.getMessage());
            }
        }

        throw new ToolExecutionException("web_search",
                "All search engines failed. Skipped (no API key): " + skipped + ", tried: "
                        + engines.stream().filter(SearchEngine::isAvailable).map(SearchEngine::name).toList());
    }

    // ==================== SearchEngine interface ====================

    interface SearchEngine {
        String name();
        boolean isAvailable();
        String search(String query) throws Exception;
    }

    // ==================== Tavily ====================

    private class TavilySearchEngine implements SearchEngine {
        private final String apiKey;

        TavilySearchEngine(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override public String name() { return "tavily"; }
        @Override public boolean isAvailable() { return apiKey != null && !apiKey.isBlank(); }

        @Override
        public String search(String query) throws Exception {
            String body = mapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("api_key", apiKey);
                put("query", query);
                put("max_results", 5);
                put("include_answer", true);
            }});

            Request request = new Request.Builder()
                    .url("https://api.tavily.com/search")
                    .post(RequestBody.create(body, JSON_TYPE))
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Tavily HTTP " + response.code() + ": " + respBody);
                }
                return formatTavilyResponse(respBody);
            }
        }

        private String formatTavilyResponse(String json) throws Exception {
            JsonNode root = mapper.readTree(json);
            StringBuilder sb = new StringBuilder();

            JsonNode answer = root.get("answer");
            if (answer != null && !answer.isNull()) {
                sb.append("Answer: ").append(answer.asText()).append("\n\n");
            }

            JsonNode results = root.get("results");
            if (results != null && results.isArray()) {
                int rank = 1;
                for (JsonNode r : results) {
                    sb.append(rank++).append(". ").append(r.path("title").asText("")).append("\n");
                    sb.append("   URL: ").append(r.path("url").asText("")).append("\n");
                    String snippet = r.path("content").asText("");
                    if (!snippet.isBlank()) {
                        sb.append("   ").append(snippet.length() > 300 ? snippet.substring(0, 300) + "..." : snippet).append("\n");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString().isBlank() ? null : sb.toString().trim();
        }
    }

    // ==================== SerpAPI ====================

    private class SerpApiSearchEngine implements SearchEngine {
        private final String apiKey;

        SerpApiSearchEngine(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override public String name() { return "serpapi"; }
        @Override public boolean isAvailable() { return apiKey != null && !apiKey.isBlank(); }

        @Override
        public String search(String query) throws Exception {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://serpapi.com/search?q=" + encoded + "&api_key=" + apiKey + "&num=5";

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("SerpAPI HTTP " + response.code() + ": " + respBody);
                }
                return formatSerpApiResponse(respBody);
            }
        }

        private String formatSerpApiResponse(String json) throws Exception {
            JsonNode root = mapper.readTree(json);
            StringBuilder sb = new StringBuilder();

            JsonNode answerBox = root.get("answer_box");
            if (answerBox != null) {
                String answer = answerBox.path("answer").asText(
                        answerBox.path("snippet").asText(""));
                if (!answer.isBlank()) {
                    sb.append("Answer: ").append(answer).append("\n\n");
                }
            }

            JsonNode results = root.get("organic_results");
            if (results != null && results.isArray()) {
                int rank = 1;
                for (JsonNode r : results) {
                    sb.append(rank++).append(". ").append(r.path("title").asText("")).append("\n");
                    sb.append("   URL: ").append(r.path("link").asText("")).append("\n");
                    String snippet = r.path("snippet").asText("");
                    if (!snippet.isBlank()) {
                        sb.append("   ").append(snippet).append("\n");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString().isBlank() ? null : sb.toString().trim();
        }
    }

    // ==================== DuckDuckGo ====================

    private class DuckDuckGoSearchEngine implements SearchEngine {

        @Override public String name() { return "duckduckgo"; }
        @Override public boolean isAvailable() { return true; }

        @Override
        public String search(String query) throws Exception {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://api.duckduckgo.com/?q=" + encoded + "&format=json&no_html=1&skip_disambig=1";

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "HarnessAgent/1.0")
                    .get()
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("DuckDuckGo HTTP " + response.code() + ": " + respBody);
                }
                return formatDdgResponse(respBody, query);
            }
        }

        private String formatDdgResponse(String json, String query) throws Exception {
            JsonNode root = mapper.readTree(json);
            StringBuilder sb = new StringBuilder();

            // Abstract (direct answer)
            String abstractText = root.path("AbstractText").asText("");
            if (!abstractText.isBlank()) {
                sb.append("Summary: ").append(abstractText).append("\n");
                String source = root.path("AbstractSource").asText("");
                if (!source.isBlank()) {
                    sb.append("Source: ").append(source).append("\n");
                }
                String abstractUrl = root.path("AbstractURL").asText("");
                if (!abstractUrl.isBlank()) {
                    sb.append("URL: ").append(abstractUrl).append("\n");
                }
                sb.append("\n");
            }

            // Answer (instant answer)
            String answer = root.path("Answer").asText("");
            if (!answer.isBlank()) {
                sb.append("Answer: ").append(answer).append("\n\n");
            }

            // Related topics
            JsonNode topics = root.get("RelatedTopics");
            if (topics != null && topics.isArray()) {
                int count = 0;
                for (JsonNode topic : topics) {
                    if (count >= 5) break;
                    if (topic.has("Text")) {
                        count++;
                        sb.append(count).append(". ").append(topic.path("Text").asText("")).append("\n");
                        String topicUrl = topic.path("FirstURL").asText("");
                        if (!topicUrl.isBlank()) {
                            sb.append("   URL: ").append(topicUrl).append("\n");
                        }
                        sb.append("\n");
                    }
                }
            }

            if (sb.toString().isBlank()) {
                return "No results found for: " + query;
            }
            return sb.toString().trim();
        }
    }
}
