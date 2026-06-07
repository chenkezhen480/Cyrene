package com.harness.preprocess.rag.rewrite;

import com.harness.ai.model.ChatModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating QueryRewriter instances based on env config.
 * Reads HARNESS_RAG_QUERY_REWRITE to determine strategy.
 */
public final class QueryRewriterFactory {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriterFactory.class);

    private QueryRewriterFactory() {}

    public static QueryRewriter create(ChatModelProvider chatModelProvider) {
        String strategy = EnvConfig.get().getString(EnvKey.RAG_QUERY_REWRITE, "none").toLowerCase();

        if ("none".equals(strategy)) {
            return new NoOpQueryRewriter();
        }

        if (chatModelProvider == null) {
            log.warn("[QueryRewriter] Strategy '{}' requires ChatModelProvider, but none available. Using no-op.", strategy);
            return new NoOpQueryRewriter();
        }

        QueryRewriter rewriter = switch (strategy) {
            case "hyde" -> new HydeQueryRewriter(chatModelProvider);
            case "multi-query" -> new MultiQueryRewriter(chatModelProvider);
            case "step-back" -> new StepBackQueryRewriter(chatModelProvider);
            default -> {
                log.warn("[QueryRewriter] Unknown strategy '{}', using no-op", strategy);
                yield new NoOpQueryRewriter();
            }
        };

        log.info("[QueryRewriter] Initialized with strategy: {}", rewriter.strategyName());
        return rewriter;
    }
}
