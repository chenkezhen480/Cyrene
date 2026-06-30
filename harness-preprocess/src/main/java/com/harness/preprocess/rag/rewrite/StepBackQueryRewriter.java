package com.harness.preprocess.rag.rewrite;

import com.harness.ai.model.ChatModelProvider;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Step-Back query rewriter.
 * Generates a more general, abstract version of the user's question
 * to retrieve relevant background context that the specific question
 * might miss.
 */
public class StepBackQueryRewriter implements QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(StepBackQueryRewriter.class);
    private static final String PROMPT = """
            请将以下具体问题改写为一个更通用、更抽象的版本，以便检索到相关的背景知识。
            改写后的问题应该涵盖原始问题所属的更大主题领域。
            直接输出改写后的问题，不要解释。

            原始问题：%s""";

    private final ChatModel model;

    public StepBackQueryRewriter(ChatModelProvider chatModelProvider) {
        this.model = chatModelProvider.chatModel();
    }

    @Override
    public List<String> rewrite(String originalQuery) {
        try {
            String prompt = String.format(PROMPT, originalQuery);
            String stepBack = model.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build()).aiMessage().text();

            if (stepBack == null || stepBack.isBlank()) {
                log.warn("[StepBack] LLM returned empty response, falling back to original query");
                return List.of(originalQuery);
            }
            return List.of(stepBack.strip());
        } catch (Exception e) {
            log.warn("[StepBack] LLM call failed: {}, falling back to original query", e.getMessage());
            return List.of(originalQuery);
        }
    }

    @Override
    public String strategyName() {
        return "step-back";
    }
}
