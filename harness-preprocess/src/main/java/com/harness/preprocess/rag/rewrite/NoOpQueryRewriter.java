package com.harness.preprocess.rag.rewrite;

import java.util.List;

/**
 * No-op query rewriter. Passes the original query through unchanged.
 * Used when query rewriting is disabled (default).
 */
public class NoOpQueryRewriter implements QueryRewriter {

    @Override
    public List<String> rewrite(String originalQuery) {
        return List.of(originalQuery);
    }

    @Override
    public String strategyName() {
        return "none";
    }
}
