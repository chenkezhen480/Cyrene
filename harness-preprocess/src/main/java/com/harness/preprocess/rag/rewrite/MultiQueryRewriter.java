package com.harness.preprocess.rag.rewrite;

import com.harness.ai.model.ChatModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-Query rewriter.
 * Generates N alternative formulations of the user's query to improve recall
 * by covering different phrasings and perspectives.
 */
public class MultiQueryRewriter implements QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(MultiQueryRewriter.class);
    private static final String PROMPT = """
            请为以下搜索查询生成 %d 个不同的改写版本，以帮助检索到更全面的相关文档。
            要求：
            - 每个版本用不同的措辞或角度表达相同的意思
            - 每行一个改写版本
            - 不要编号，不要加任何解释

            原始查询：%s""";

    private final ChatModel model;
    private final int count;

    public MultiQueryRewriter(ChatModelProvider chatModelProvider) {
        this.model = chatModelProvider.chatModelNoThinking();
        this.count = EnvConfig.get().getInt(EnvKey.RAG_QUERY_REWRITE_COUNT, 3);
    }

    @Override
    public List<String> rewrite(String originalQuery) {
        try {
            String prompt = String.format(PROMPT, count, originalQuery);
            String response = model.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build()).aiMessage().text();

            if (response == null || response.isBlank()) {
                log.warn("[MultiQuery] LLM returned empty response, falling back to original query");
                return List.of(originalQuery);
            }

            List<String> queries = new ArrayList<>();
            queries.add(originalQuery); // always include original as first

            for (String line : response.strip().split("\\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.equals(originalQuery)) {
                    queries.add(trimmed);
                }
                if (queries.size() >= count + 1) break; // original + N alternatives
            }

            return queries;
        } catch (Exception e) {
            log.warn("[MultiQuery] LLM call failed: {}, falling back to original query", e.getMessage());
            return List.of(originalQuery);
        }
    }

    @Override
    public String strategyName() {
        return "multi-query";
    }
}
