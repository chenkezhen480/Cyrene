package com.harness.preprocess.rag.rewrite;

import com.harness.ai.model.ChatModelProvider;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * HyDE (Hypothetical Document Embedding) query rewriter.
 * Generates a hypothetical answer to the user's question, then uses that
 * as the retrieval query. The hypothesis is closer in embedding space to
 * actual documents than the raw question.
 */
public class HydeQueryRewriter implements QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(HydeQueryRewriter.class);
    private static final String PROMPT = """
            请针对以下问题，写一段简短的假设性回答（约2-3句话）。
            这段回答不需要准确，只需要是一个合理的、可能出现在文档中的答案段落。
            直接输出回答内容，不要解释或加前缀。

            问题：%s""";

    private final ChatModel model;

    public HydeQueryRewriter(ChatModelProvider chatModelProvider) {
        this.model = chatModelProvider.chatModelNoThinking();
    }

    @Override
    public List<String> rewrite(String originalQuery) {
        try {
            String prompt = String.format(PROMPT, originalQuery);
            String hypothesis = model.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build()).aiMessage().text();

            if (hypothesis == null || hypothesis.isBlank()) {
                log.warn("[HyDE] LLM returned empty response, falling back to original query");
                return List.of(originalQuery);
            }
            return List.of(hypothesis.strip());
        } catch (Exception e) {
            log.warn("[HyDE] LLM call failed: {}, falling back to original query", e.getMessage());
            return List.of(originalQuery);
        }
    }

    @Override
    public String strategyName() {
        return "hyde";
    }
}
