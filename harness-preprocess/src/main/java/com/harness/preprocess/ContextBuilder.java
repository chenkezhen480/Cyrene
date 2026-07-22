package com.harness.preprocess;

import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.ai.model.RerankModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.preprocess.rag.*;
import com.harness.preprocess.gap.GapAnalysis;
import com.harness.preprocess.rag.rewrite.*;
import com.harness.preprocess.rerank.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Layer 2: Preprocessing.
 * Orchestrates RAG retrieval + semantic enhancement + reranking to build context for the AI layer.
 * Supports optional query rewriting (HyDE, Multi-Query, Step-Back) before retrieval.
 */
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final VectorStore vectorStore;
    private final Reranker reranker;
    private final SemanticContextRetriever semanticRetriever;
    private final QueryRewriter queryRewriter;

    public ContextBuilder(RerankModelProvider rerankModelProvider,
                          EmbeddingModelProvider embeddingModelProvider,
                          ChatModelProvider chatModelProvider) {
        this.vectorStore = VectorStoreFactory.create(embeddingModelProvider);
        this.semanticRetriever = vectorStore != null ? new SemanticContextRetriever(vectorStore) : null;
        this.reranker = new Reranker(rerankModelProvider);
        this.queryRewriter = QueryRewriterFactory.create(chatModelProvider);
    }

    /**
     * Build enriched context from user input.
     *
     * @param userText the raw user input text
     * @return context result with RAG hits and formatted context string
     */
    public ContextResult build(String userText) {
        return build(userText, GapAnalysis.defaults());
    }

    /**
     * Build enriched context from user input, with GapAnalysis controlling behavior.
     *
     * @param userText    the raw user input text
     * @param gapAnalysis 动态路由分析结果，控制是否检索
     * @return context result with RAG hits and formatted context string
     */
    public ContextResult build(String userText, GapAnalysis gapAnalysis) {
        log.debug("[L3-RAG] Building context for text ({} chars)", userText != null ? userText.length() : 0);

        // GapAnalysis: 显式禁用检索时直接返回空结果
        if (Boolean.FALSE.equals(gapAnalysis.needsKnowledgeBase())) {
            log.debug("[L3-RAG] Skipped: needsKnowledgeBase=false (explicit)");
            return ContextResult.empty();
        }

        // RAG provider 未配置时提前返回，避免查询改写白跑 LLM 调用
        if (vectorStore == null) {
            log.warn("[L3-RAG] GapAnalyzer decided needsKnowledgeBase=true but RAG provider is none, skipping");
            return ContextResult.empty();
        }

        // Step 0: Query rewriting（使用构造时的默认 rewriter）
        List<String> queries = queryRewriter.rewrite(userText);
        if (queries.size() > 1) {
            log.debug("[L3-RAG] Query rewrite [{}]: {} queries", queryRewriter.strategyName(), queries.size());
        }

        // Step 1-4: retrieve → enhance → rerank → format
        return executeRetrieval(queries, userText);
    }

    /**
     * 为 KnowledgeBaseTool 提供的 RAG 检索入口（无改写）。
     * 跳过 GapAnalysis 路由，直接执行检索流程。
     *
     * @param query   用户查询（已由 LLM 保证完整独立）
     * @param rewrite 是否进行查询改写（由工具层调用 LLM 生成多查询后调用 buildRagWithQueries）
     * @return context result
     */
    public ContextResult buildRagForTool(String query, boolean rewrite) {
        if (vectorStore == null) {
            log.warn("[L3-RAG] RAG provider is none, skipping tool-based retrieval");
            return ContextResult.empty();
        }
        return executeRetrieval(List.of(query), query);
    }

    /**
     * 为 KnowledgeBaseTool 提供的多查询 RAG 检索入口。
     * 由工具层完成查询改写后，传入多个查询执行检索。
     *
     * @param queries 多个检索查询（至少 1 个）
     * @return context result
     */
    public ContextResult buildRagWithQueries(List<String> queries) {
        if (vectorStore == null) {
            log.warn("[L3-RAG] RAG provider is none, skipping multi-query retrieval");
            return ContextResult.empty();
        }
        if (queries == null || queries.isEmpty()) {
            return ContextResult.empty();
        }
        // 使用第一个查询作为 rerank 的参考文本
        return executeRetrieval(queries, queries.get(0));
    }

    /**
     * 核心检索流程：多查询检索 → 合并去重 → 语义增强 → Rerank → 格式化。
     *
     * @param queries    检索查询列表
     * @param rerankText 用于 rerank 的参考文本（通常为原始查询）
     * @return context result
     */
    private ContextResult executeRetrieval(List<String> queries, String rerankText) {
        // Step 1: RAG retrieval (support multi-query with dedup)
        List<RagRetriever.RagDocument> ragDocs = doMultiRetrieve(queries);
        log.debug("[L3-RAG] Retrieved {} docs from {} queries", ragDocs.size(), queries.size());

        // Step 2: Semantic enhancement (lookback for truncated chunks)
        int totalLookback = 0;
        List<String> lookbackChunkIds = List.of();
        if (semanticRetriever != null && !ragDocs.isEmpty()) {
            List<SemanticContextRetriever.EnhancedChunk> enhanced = semanticRetriever.enhance(ragDocs);
            totalLookback = enhanced.stream().mapToInt(SemanticContextRetriever.EnhancedChunk::lookbackCount).sum();
            lookbackChunkIds = enhanced.stream()
                    .flatMap(e -> e.lookbackChunkIds().stream())
                    .toList();
            ragDocs = enhanced.stream().map(SemanticContextRetriever.EnhancedChunk::document).toList();
            if (totalLookback > 0) {
                log.debug("[L3-RAG] Semantic enhancement: {} lookbacks for {} chunks", totalLookback, lookbackChunkIds.size());
            }
        }

        // Step 3: Rerank (with timing)
        long rerankStart = System.currentTimeMillis();
        Reranker.RerankResult rerankResult = reranker.rerank(rerankText, ragDocs);
        long rerankMs = System.currentTimeMillis() - rerankStart;
        List<RagRetriever.RagDocument> reranked = rerankResult.documents();
        log.debug("[L3-RAG] Reranked {} → {} docs in {}ms (topScore={})", ragDocs.size(), reranked.size(), rerankMs,
                String.format("%.4f", rerankResult.topScore()));

        // Step 4: Format context string
        String contextBlock = formatContext(reranked);
        if (contextBlock.isEmpty()) {
            log.debug("[L3-RAG] No RAG results");
        } else {
            log.debug("[L3-RAG] Context block: {} chars", contextBlock.length());
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put("rerank_ms", String.valueOf(rerankMs));
        metadata.put("rag_doc_count", String.valueOf(ragDocs.size()));
        metadata.put("reranked_doc_count", String.valueOf(reranked.size()));
        metadata.put("top_score", String.valueOf(rerankResult.topScore()));
        metadata.put("lookback_count", String.valueOf(totalLookback));
        metadata.put("query_count", String.valueOf(queries.size()));
        metadata.put("provider", vectorStore != null ? vectorStore.providerName() : "none");
        if (!lookbackChunkIds.isEmpty()) {
            metadata.put("lookback_chunk_ids", String.join(",", lookbackChunkIds));
        }

        return new ContextResult(
                reranked.stream().map(RagRetriever.RagDocument::id).toList(),
                contextBlock,
                metadata
        );
    }

    /**
     * 多查询检索并合并去重。
     * 每个查询独立检索，结果按 document ID 去重，保留最高分。
     */
    private List<RagRetriever.RagDocument> doMultiRetrieve(List<String> queries) {
        if (queries.size() == 1) {
            return doRetrieve(queries.get(0));
        }
        return queries.stream()
                .map(this::doRetrieve)
                .flatMap(List::stream)
                .collect(Collectors.toMap(
                        RagRetriever.RagDocument::id,
                        Function.identity(),
                        (a, b) -> a.score() >= b.score() ? a : b))
                .values().stream()
                .sorted(Comparator.comparingDouble(RagRetriever.RagDocument::score).reversed())
                .toList();
    }

    private List<RagRetriever.RagDocument> doRetrieve(String query) {
        if (vectorStore == null) return List.of();
        EnvConfig cfg = EnvConfig.get();
        String collection = cfg.getString(EnvKey.RAG_COLLECTION, "default");
        int topK = cfg.getInt(EnvKey.RAG_TOP_K, 5);
        List<VectorStore.Document> docs = vectorStore.searchText(collection, query, topK);
        return docs.stream()
                .map(d -> new RagRetriever.RagDocument(d.id(), d.content(), d.source(), d.score()))
                .toList();
    }

    private String formatContext(List<RagRetriever.RagDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Retrieved Context]\n");
        for (int i = 0; i < docs.size(); i++) {
            RagRetriever.RagDocument doc = docs.get(i);
            sb.append(String.format("--- Source %d (score: %.2f) ---\n", i + 1, doc.score()));
            sb.append(doc.content()).append("\n");
        }
        return sb.toString();
    }

    public VectorStore vectorStore() {
        return vectorStore;
    }

    public record ContextResult(
            List<String> ragHitIds,
            String contextBlock,
            Map<String, String> metadata
    ) {
        public boolean hasContext() {
            return contextBlock != null && !contextBlock.isBlank();
        }

        /**
         * Top rerank relevance score. 0.0 if not available.
         */
        public double topScore() {
            if (metadata == null || !metadata.containsKey("top_score")) return 0.0;
            try {
                return Double.parseDouble(metadata.get("top_score"));
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }

        public static ContextResult empty() {
            return new ContextResult(List.of(), "", Map.of());
        }
    }
}
