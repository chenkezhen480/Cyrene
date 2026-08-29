package com.harness.input.gap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.provider.SmallTaskModelProvider;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Uses the small-task model for the Tier 2 gap-routing decision. */
public class GapModelAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GapModelAnalyzer.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String SYSTEM_PROMPT = """
            Output JSON: {"t":bool,"k":bool,"s":bool}
            t=needsThinking k=needsKnowledgeBase s=needsWebSearch
            Output ONLY the JSON, no explanation.""";

    private final SmallTaskModelProvider provider;

    public GapModelAnalyzer(SmallTaskModelProvider provider) {
        this.provider = java.util.Objects.requireNonNull(provider, "provider");
        log.info("[GapModelAnalyzer] available={}", provider.isAvailable());
    }

    /** Returns a partial routing decision, or {@code null} when model analysis fails. */
    public GapAnalysis infer(String query) {
        if (!provider.isAvailable() || query == null || query.isBlank()) {
            return null;
        }
        try {
            ChatModel model = provider.chatModel();
            if (model == null) return null;

            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(SYSTEM_PROMPT),
                            UserMessage.from(query)))
                    .build());
            String text = response.aiMessage().text();
            if (text == null || text.isBlank()) {
                log.warn("[GapModelAnalyzer] empty response for query: \"{}\"", truncate(query));
                return null;
            }
            return parseResponse(text.trim(), query);
        } catch (Exception e) {
            log.warn("[GapModelAnalyzer] model analysis failed for query \"{}\": {}",
                    truncate(query), e.getMessage());
            return null;
        }
    }

    private GapAnalysis parseResponse(String json, String query) {
        try {
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*", "")
                        .replaceAll("```\\s*", "")
                        .trim();
            }
            JsonNode node = mapper.readTree(json);
            Boolean thinking = optionalBoolean(node, "t");
            Boolean knowledge = optionalBoolean(node, "k");
            Boolean webSearch = optionalBoolean(node, "s");
            GapAnalysis result = new GapAnalysis(knowledge, thinking, webSearch, "llm");
            log.debug("[GapModelAnalyzer] query=\"{}\" -> {}", truncate(query), result);
            return result;
        } catch (Exception e) {
            log.warn("[GapModelAnalyzer] failed to parse response: \"{}\" - {}",
                    json, e.getMessage());
            return null;
        }
    }

    private static Boolean optionalBoolean(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull()
                ? node.get(field).asBoolean()
                : null;
    }

    private static String truncate(String value) {
        return value.length() <= 50 ? value : value.substring(0, 50) + "...";
    }
}
