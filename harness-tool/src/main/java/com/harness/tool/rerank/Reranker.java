package com.harness.tool.rerank;

import com.harness.provider.RerankModelProvider;
import com.harness.tool.rag.RagRetriever;
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

    private final RerankModelProvider rerankModelProvider;

    public Reranker(RerankModelProvider rerankModelProvider) {
        this.rerankModelProvider = rerankModelProvider;
    }

    /**
     * Rerank result with documents and the top relevance score.
     */
    public record RerankResult(List<RagRetriever.RagDocument> documents, double topScore) {
        public boolean isEmpty() { return documents == null || documents.isEmpty(); }
    }

    /**
     * Rerank documents. Uses RerankModelProvider if available, otherwise sorts by original score.
     * Returns both the reranked documents and the top relevance score.
     */
    public RerankResult rerank(String query, List<RagRetriever.RagDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return new RerankResult(Collections.emptyList(), 0.0);
        }
        int topN = rerankModelProvider != null
                ? rerankModelProvider.defaultTopN()
                : 3;
        if (topN <= 0) {
            throw new IllegalStateException("rerank.topN in model.conf must be positive");
        }

        if (rerankModelProvider != null && rerankModelProvider.isAvailable()) {
            log.debug("Using RerankModelProvider: {}", rerankModelProvider.providerName());
            List<String> docTexts = documents.stream().map(RagRetriever.RagDocument::content).toList();
            List<RerankModelProvider.RankedResult> ranked = rerankModelProvider.rerank(query, docTexts, topN);
            List<RagRetriever.RagDocument> reranked = ranked.stream()
                    .filter(r -> r.index() >= 0 && r.index() < documents.size())
                    .map(r -> documents.get(r.index()))
                    .toList();
            double topScore = ranked.isEmpty() ? 0.0 : ranked.get(0).score();
            return new RerankResult(reranked, topScore);
        }

        log.debug("Rerank provider not available, falling back to score sort (topN={})", topN);
        List<RagRetriever.RagDocument> sorted = documents.stream()
                .sorted(Comparator.comparingDouble(RagRetriever.RagDocument::score).reversed())
                .limit(topN)
                .toList();
        double topScore = sorted.isEmpty() ? 0.0 : sorted.get(0).score();
        return new RerankResult(sorted, topScore);
    }
}
