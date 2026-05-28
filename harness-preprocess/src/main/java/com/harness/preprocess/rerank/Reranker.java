package com.harness.preprocess.rerank;

import com.harness.ai.model.RerankModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.preprocess.rag.RagRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reranker that reorders RAG results for better relevance.
 * Uses the injected RerankModelProvider when available, otherwise falls back to score sorting.
 */
public class Reranker {

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    private final int topN;
    private final RerankModelProvider rerankModelProvider;

    public Reranker(RerankModelProvider rerankModelProvider) {
        EnvConfig cfg = EnvConfig.get();
        this.topN = cfg.getInt(EnvKey.RERANK_TOP_N, 3);
        this.rerankModelProvider = rerankModelProvider;
    }

    /**
     * Rerank documents. Uses RerankModelProvider if available, otherwise sorts by original score.
     */
    public List<RagRetriever.RagDocument> rerank(String query, List<RagRetriever.RagDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        if (rerankModelProvider != null && rerankModelProvider.isAvailable()) {
            log.debug("Using RerankModelProvider: {}", rerankModelProvider.providerName());
            List<String> docTexts = documents.stream().map(RagRetriever.RagDocument::content).toList();
            List<RerankModelProvider.RankedResult> ranked = rerankModelProvider.rerank(query, docTexts, topN);
            return ranked.stream()
                    .filter(r -> r.index() >= 0 && r.index() < documents.size())
                    .map(r -> documents.get(r.index()))
                    .toList();
        }

        log.debug("Rerank provider not available, falling back to score sort (topN={})", topN);
        return documents.stream()
                .sorted(Comparator.comparingDouble(RagRetriever.RagDocument::score).reversed())
                .limit(topN)
                .toList();
    }
}
