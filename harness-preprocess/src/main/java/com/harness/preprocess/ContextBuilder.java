package com.harness.preprocess;

import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.ai.model.RerankModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.preprocess.rag.*;
import com.harness.preprocess.gap.GapAnalysis;
import com.harness.preprocess.gap.RewriteStrategy;
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
    private final ChatModelProvider chatModelProvider;

    public ContextBuilder(RerankModelProvider rerankModelProvider,
                          EmbeddingModelProvider embeddingModelProvider,
                          ChatModelProvider chatModelProvider) {
        this.vectorStore = VectorStoreFactory.create(embeddingModelProvider);
        this.semanticRetriever = vectorStore != null ? new SemanticContextRetriever(vectorStore) : null;
        this.reranker = new Reranker(rerankModelProvider);
        this.queryRewriter = QueryRewriterFactory.create(chatModelProvider);
        this.chatModelProvider = chatModelProvider;
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
     * @param gapAnalysis 动态路由分析结果，控制是否检索、用哪种改写策略
     * @return context result with RAG hits and formatted context string
     */
    public ContextResult build(String userText, GapAnalysis gapAnalysis) {
        log.debug("[L2-RAG] Building context for text ({} chars)", userText != null ? userText.length() : 0);

        // GapAnalysis: 显式禁用检索时直接返回空结果
        if (Boolean.FALSE.equals(gapAnalysis.needsKnowledgeBase())) {
            log.debug("[L2-RAG] Skipped: needsKnowledgeBase=false (explicit)");
            return ContextResult.empty();
        }

        // Step 0: Query rewriting（按 GapAnalysis 动态选择策略，null 时用默认）
        QueryRewriter activeRewriter = resolveRewriter(gapAnalysis.rewriteStrategy());
        List<String> queries = activeRewriter.rewrite(userText);
        if (queries.size() > 1) {
            log.debug("[L2-RAG] Query rewrite [{}]: {} queries", activeRewriter.strategyName(), queries.size());
        }

        // Step 1: RAG retrieval (support multi-query)
        List<RagRetriever.RagDocument> ragDocs;
        if (queries.size() == 1) {
            ragDocs = doRetrieve(queries.get(0));
        } else {
            ragDocs = queries.stream()
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
        log.debug("[L2-RAG] Retrieved {} docs", ragDocs.size());

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
                log.debug("[L2-RAG] Semantic enhancement: {} lookbacks for {} chunks", totalLookback, lookbackChunkIds.size());
            }
        }

        // Step 3: Rerank (with timing)
        long rerankStart = System.currentTimeMillis();
        List<RagRetriever.RagDocument> reranked = reranker.rerank(userText, ragDocs);
        long rerankMs = System.currentTimeMillis() - rerankStart;
        log.debug("[L2-RAG] Reranked {} → {} docs in {}ms", ragDocs.size(), reranked.size(), rerankMs);

        // Step 4: Format context string for injection into prompt
        String contextBlock = formatContext(reranked);
        if (contextBlock.isEmpty()) {
            log.debug("[L2-RAG] No RAG results, skipping context injection");
        } else {
            log.debug("[L2-RAG] Context block: {} chars", contextBlock.length());
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put("rerank_ms", String.valueOf(rerankMs));
        metadata.put("rag_doc_count", String.valueOf(ragDocs.size()));
        metadata.put("reranked_doc_count", String.valueOf(reranked.size()));
        metadata.put("lookback_count", String.valueOf(totalLookback));
        metadata.put("query_rewrite", activeRewriter.strategyName());
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
     * 根据 GapAnalysis 的 rewriteStrategy 动态选择 QueryRewriter。
     * null 时返回构造时的默认 rewriter。
     */
    private QueryRewriter resolveRewriter(RewriteStrategy strategy) {
        if (strategy == null) return this.queryRewriter;
        if (chatModelProvider == null) {
            log.warn("[L2-RAG] Strategy '{}' requires ChatModelProvider, falling back to default", strategy);
            return this.queryRewriter;
        }
        return switch (strategy) {
            case NONE -> new NoOpQueryRewriter();
            case HYDE -> new HydeQueryRewriter(chatModelProvider);
            case MULTI_QUERY -> new MultiQueryRewriter(chatModelProvider);
            case STEP_BACK -> new StepBackQueryRewriter(chatModelProvider);
        };
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

        public static ContextResult empty() {
            return new ContextResult(List.of(), "", Map.of());
        }
    }
}
