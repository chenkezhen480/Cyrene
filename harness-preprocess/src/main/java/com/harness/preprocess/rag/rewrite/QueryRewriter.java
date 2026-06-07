package com.harness.preprocess.rag.rewrite;

import java.util.List;

/**
 * Pluggable query rewriting strategy.
 * Transforms the user query before retrieval to improve recall.
 */
public interface QueryRewriter {

    /**
     * Rewrite the user query into one or more retrieval queries.
     * The returned list must contain at least one element.
     * The first element is considered the "primary" query.
     *
     * @param originalQuery the raw user input
     * @return non-empty list of rewritten queries
     */
    List<String> rewrite(String originalQuery);

    /**
     * Human-readable strategy name for logging.
     */
    String strategyName();
}
