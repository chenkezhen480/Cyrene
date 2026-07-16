package com.harness.preprocess.gap;

import com.harness.ai.model.ClassifierModelProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Tier 2 轻量 LLM 分类器：调用分类模型一次性输出四字段 JSON。
 * <p>
 * 设计约束：
 * <ul>
 *   <li>thinking 固定关闭（由 ClassifierModelProvider 构建时保证）</li>
 *   <li>max_tokens 锁定 50，只够输出压缩 JSON</li>
 *   <li>非流式——需要完整 JSON 做反序列化</li>
 *   <li>失败时返回 null，降级到环境变量默认值</li>
 * </ul>
 */
public class GapClassifier {

    private static final Logger log = LoggerFactory.getLogger(GapClassifier.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            Output JSON: {"t":bool,"k":bool,"w":"NONE|HYDE|MULTI_QUERY|STEP_BACK","s":bool}
            t=needsThinking k=needsKnowledgeBase w=rewriteStrategy(k=false→NONE) s=needsWebSearch
            w: NONE=default, HYDE=ambiguous→hypothetical answer, MULTI_QUERY=broad coverage, STEP_BACK=specific→general
            Output ONLY the JSON, no explanation.""";

    private final ChatModel model;
    private final boolean available;

    public GapClassifier(ClassifierModelProvider provider) {
        this.available = provider.isAvailable() && provider.chatModel() != null;
        this.model = available ? provider.chatModel() : null;
        log.info("[GapClassifier] available={}", available);
    }

    /**
     * Tier 2 分类。调用 LLM 返回 GapAnalysis。
     *
     * @param query 用户查询文本
     * @return GapAnalysis（四字段可能部分为 null），失败时返回 null
     */
    public GapAnalysis classify(String query) {
        if (!available || query == null || query.isBlank()) {
            return null;
        }

        try {
            log.info("[GapClassifier] === Tier 2 LLM Classification ===");
            log.info("[GapClassifier] System Prompt:\n{}", SYSTEM_PROMPT);
            log.info("[GapClassifier] User Query: {}", query);

            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from(SYSTEM_PROMPT),
                            UserMessage.from(query)
                    ))
                    .build();

            ChatResponse response = model.chat(request);
            String text = response.aiMessage().text();
            log.info("[GapClassifier] LLM Response: {}", text);
            if (text == null || text.isBlank()) {
                log.warn("[GapClassifier] empty response for query: \"{}\"", truncate(query));
                return null;
            }

            // 解析压缩 JSON：{"t":bool,"k":bool,"w":"NONE","s":bool}
            return parseResponse(text.trim(), query);
        } catch (Exception e) {
            log.warn("[GapClassifier] classification failed for query \"{}\": {}", truncate(query), e.getMessage());
            return null; // 降级到环境变量默认值
        }
    }

    private GapAnalysis parseResponse(String json, String query) {
        try {
            // 处理可能的 markdown 包裹
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            JsonNode node = mapper.readTree(json);

            Boolean t = node.has("t") && !node.get("t").isNull() ? node.get("t").asBoolean() : null;
            Boolean k = node.has("k") && !node.get("k").isNull() ? node.get("k").asBoolean() : null;
            Boolean s = node.has("s") && !node.get("s").isNull() ? node.get("s").asBoolean() : null;

            String wStr = node.has("w") && !node.get("w").isNull() ? node.get("w").asText() : null;
            RewriteStrategy w = RewriteStrategy.fromString(wStr);

            GapAnalysis result = new GapAnalysis(k, w, t, s, "llm");
            log.debug("[GapClassifier] query=\"{}\" → {}", truncate(query), result);
            return result;
        } catch (Exception e) {
            log.warn("[GapClassifier] failed to parse response: \"{}\" — {}", json, e.getMessage());
            return null;
        }
    }

    private static String truncate(String s) {
        return s.length() <= 50 ? s : s.substring(0, 50) + "...";
    }
}
