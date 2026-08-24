package com.harness.agent.context;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.provider.RerankModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.tool.rag.*;
import com.harness.tool.rerank.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Layer 2: Preprocessing.
 * Orchestrates RAG retrieval and reranking, plus explicit document-window reads.
 */
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final VectorStore vectorStore;
    private final Reranker reranker;

    public ContextBuilder(RerankModelProvider rerankModelProvider,
                          EmbeddingModelProvider embeddingModelProvider) {
        this(VectorStoreFactory.create(embeddingModelProvider), new Reranker(rerankModelProvider));
    }

    ContextBuilder(VectorStore vectorStore, Reranker reranker) {
        this.vectorStore = vectorStore;
        this.reranker = Objects.requireNonNull(reranker, "reranker");
    }

    /**
     * 为 KnowledgeBaseTool 提供的 RAG 检索入口（无改写）。
     * 跳过 GapAnalysis 路由，直接执行检索流程。
     *
     * @param query   用户查询（已由 LLM 保证完整独立）
     * @return context result
     */
    public ContextResult buildRagForTool(String query) {
        return buildRagForTool(query, defaultCollection(), maxSearchLimit());
    }

    public ContextResult buildRagForTool(String query, String collection, int limit) {
        if (vectorStore == null) {
            log.warn("[L3-RAG] RAG provider is none, skipping tool-based retrieval");
            return ContextResult.empty();
        }
        validateSearchScope(collection, limit);
        return executeRetrieval(List.of(query), query, collection, limit);
    }

    /**
     * 为 KnowledgeBaseTool 提供的多查询 RAG 检索入口。
     * 由工具层完成查询改写后，传入多个查询执行检索。
     *
     * @param queries 多个检索查询（至少 1 个）
     * @return context result
     */
    public ContextResult buildRagWithQueries(List<String> queries) {
        return buildRagWithQueries(queries, defaultCollection(), maxSearchLimit());
    }

    public ContextResult buildRagWithQueries(
            List<String> queries,
            String collection,
            int limit
    ) {
        if (vectorStore == null) {
            log.warn("[L3-RAG] RAG provider is none, skipping multi-query retrieval");
            return ContextResult.empty();
        }
        if (queries == null || queries.isEmpty()) {
            return ContextResult.empty();
        }
        validateSearchScope(collection, limit);
        // 使用第一个查询作为 rerank 的参考文本
        return executeRetrieval(queries, queries.get(0), collection, limit);
    }

    public List<RagRetriever.RagDocument> readContext(
            String collection,
            String documentId,
            int anchorChunkIndex,
            int before,
            int after
    ) {
        if (vectorStore == null) {
            throw new IllegalStateException("Knowledge base provider is disabled");
        }
        return vectorStore.readDocumentWindow(
                        collection, documentId, anchorChunkIndex, before, after)
                .stream()
                .map(RagRetriever.RagDocument::from)
                .toList();
    }

    /**
     * 核心检索流程：多查询检索 → 合并去重 → 语义增强 → Rerank → 格式化。
     *
     * @param queries    检索查询列表
     * @param rerankText 用于 rerank 的参考文本（通常为原始查询）
     * @return context result
     */
    private ContextResult executeRetrieval(
            List<String> queries,
            String rerankText,
            String collection,
            int limit
    ) {
        // Step 1: RAG retrieval (support multi-query with dedup)
        RetrievalBatch retrievalBatch = doMultiRetrieve(queries, collection, limit);
        List<RagRetriever.RagDocument> ragDocs = retrievalBatch.documents();
        log.debug("[L3-RAG] Retrieved {} docs from {} queries", ragDocs.size(), queries.size());

        // Step 2: Rerank (with timing)
        long rerankStart = System.currentTimeMillis();
        Reranker.RerankResult rerankResult = reranker.rerank(rerankText, ragDocs);
        long rerankMs = System.currentTimeMillis() - rerankStart;
        List<RagRetriever.RagDocument> reranked = rerankResult.documents();
        log.debug("[L3-RAG] Reranked {} → {} docs in {}ms (topScore={})", ragDocs.size(), reranked.size(), rerankMs,
                String.format("%.4f", rerankResult.topScore()));

        if (reranked.isEmpty()) {
            log.debug("[L3-RAG] No RAG results");
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put("rerank_ms", String.valueOf(rerankMs));
        metadata.put("rag_doc_count", String.valueOf(ragDocs.size()));
        metadata.put("reranked_doc_count", String.valueOf(reranked.size()));
        metadata.put("top_score", String.valueOf(rerankResult.topScore()));
        metadata.put("best_observed_score", String.valueOf(retrievalBatch.bestObservedScore()));
        metadata.put("observed_candidate_count", String.valueOf(retrievalBatch.observedCandidateCount()));
        metadata.put("query_count", String.valueOf(queries.size()));
        metadata.put("collection", collection);
        metadata.put("provider", vectorStore != null ? vectorStore.providerName() : "none");
        return new ContextResult(
                reranked,
                metadata
        );
    }

    /**
     * 多查询检索并合并去重。
     * 每个查询独立检索，结果按 document ID 去重，保留最高分。
     */
    private RetrievalBatch doMultiRetrieve(
            List<String> queries,
            String collection,
            int limit
    ) {
        if (queries.size() == 1) {
            return doRetrieve(queries.get(0), collection, limit);
        }

        Map<String, RagRetriever.RagDocument> documentsById = new HashMap<>();
        double bestObservedScore = 0.0;
        int observedCandidateCount = 0;
        for (String query : queries) {
            RetrievalBatch batch = doRetrieve(query, collection, limit);
            bestObservedScore = Math.max(bestObservedScore, batch.bestObservedScore());
            observedCandidateCount += batch.observedCandidateCount();
            for (RagRetriever.RagDocument document : batch.documents()) {
                documentsById.merge(document.id(), document,
                        (existing, candidate) -> existing.score() >= candidate.score() ? existing : candidate);
            }
        }

        List<RagRetriever.RagDocument> documents = documentsById.values().stream()
                .sorted(Comparator.comparingDouble(RagRetriever.RagDocument::score).reversed())
                .toList();
        return new RetrievalBatch(documents, bestObservedScore, observedCandidateCount);
    }

    private RetrievalBatch doRetrieve(String query, String collection, int limit) {
        if (vectorStore == null) return RetrievalBatch.empty();
        VectorStore.SearchResult searchResult =
                vectorStore.searchTextWithEvidence(collection, query, limit);
        List<RagRetriever.RagDocument> documents = searchResult.documents().stream()
                .map(RagRetriever.RagDocument::from)
                .toList();
        return new RetrievalBatch(
                documents,
                searchResult.bestObservedScore(),
                searchResult.observedCandidateCount());
    }

    private record RetrievalBatch(
            List<RagRetriever.RagDocument> documents,
            double bestObservedScore,
            int observedCandidateCount
    ) {
        private static RetrievalBatch empty() {
            return new RetrievalBatch(List.of(), 0.0, 0);
        }
    }

    public VectorStore vectorStore() {
        return vectorStore;
    }

    public String defaultCollection() {
        return EnvConfig.get().getString(EnvKey.RAG_COLLECTION, "default");
    }

    public int maxSearchLimit() {
        return EnvConfig.get().getInt(EnvKey.RAG_TOP_K, 5);
    }

    private void validateSearchScope(String collection, int limit) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("knowledge collection is required");
        }
        if (limit < 1 || limit > maxSearchLimit()) {
            throw new IllegalArgumentException(
                    "knowledge search limit must be between 1 and " + maxSearchLimit());
        }
    }

    public record ContextResult(
            List<RagRetriever.RagDocument> documents,
            Map<String, String> metadata
    ) {
        public ContextResult {
            documents = documents == null ? List.of() : List.copyOf(documents);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public boolean hasContext() {
            return !documents.isEmpty();
        }

        public List<String> ragHitIds() {
            return documents.stream().map(RagRetriever.RagDocument::id).toList();
        }

        /**
         * Top rerank relevance score. 0.0 if not available.
         */
        public double topScore() {
            return doubleMetadata("top_score");
        }

        /**
         * Highest candidate score observed before the provider applied the hard acceptance threshold.
         */
        public double bestObservedScore() {
            return doubleMetadata("best_observed_score");
        }

        public int observedCandidateCount() {
            if (metadata == null || !metadata.containsKey("observed_candidate_count")) return 0;
            try {
                return Integer.parseInt(metadata.get("observed_candidate_count"));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private double doubleMetadata(String key) {
            if (metadata == null || !metadata.containsKey(key)) return 0.0;
            try {
                return Double.parseDouble(metadata.get(key));
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }

        public static ContextResult empty() {
            return new ContextResult(List.of(), Map.of());
        }
    }
}
