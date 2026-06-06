package com.harness.preprocess;

import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.ai.model.RerankModelProvider;
import com.harness.preprocess.rag.PgVectorRagRetriever;
import com.harness.preprocess.rag.RagRetriever;
import com.harness.preprocess.rag.SemanticContextRetriever;
import com.harness.preprocess.rerank.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer 2: Preprocessing.
 * Orchestrates RAG retrieval + semantic enhancement + reranking to build context for the AI layer.
 */
public class ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    private final RagRetriever ragRetriever;
    private final Reranker reranker;
    private final SemanticContextRetriever semanticRetriever;

    public ContextBuilder(RerankModelProvider rerankModelProvider,
                          EmbeddingModelProvider embeddingModelProvider) {
        this.ragRetriever = new RagRetriever(embeddingModelProvider);
        this.reranker = new Reranker(rerankModelProvider);
        PgVectorRagRetriever pgVector = ragRetriever.getPgVectorRetriever();
        this.semanticRetriever = pgVector != null
                ? new SemanticContextRetriever(pgVector) : null;
    }

    /**
     * Build enriched context from user input.
     *
     * @param userText the raw user input text
     * @return context result with RAG hits and formatted context string
     */
    public ContextResult build(String userText) {
        log.info("[L2-RAG] Building context for text ({} chars)", userText != null ? userText.length() : 0);

        // Step 1: RAG retrieval
        List<RagRetriever.RagDocument> ragDocs = ragRetriever.retrieve(userText);
        log.info("[L2-RAG] Retrieved {} docs", ragDocs.size());

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
        log.info("[L2-RAG] Reranked {} → {} docs in {}ms", ragDocs.size(), reranked.size(), rerankMs);

        // Step 4: Format context string for injection into prompt
        String contextBlock = formatContext(reranked);
        if (contextBlock.isEmpty()) {
            log.info("[L2-RAG] No RAG results, skipping context injection");
        } else {
            log.debug("[L2-RAG] Context block: {} chars", contextBlock.length());
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put("rerank_ms", String.valueOf(rerankMs));
        metadata.put("rag_doc_count", String.valueOf(ragDocs.size()));
        metadata.put("reranked_doc_count", String.valueOf(reranked.size()));
        metadata.put("lookback_count", String.valueOf(totalLookback));
        if (!lookbackChunkIds.isEmpty()) {
            metadata.put("lookback_chunk_ids", String.join(",", lookbackChunkIds));
        }

        return new ContextResult(
                reranked.stream().map(RagRetriever.RagDocument::id).toList(),
                contextBlock,
                metadata
        );
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

    public record ContextResult(
            List<String> ragHitIds,
            String contextBlock,
            Map<String, String> metadata
    ) {
        public boolean hasContext() {
            return contextBlock != null && !contextBlock.isBlank();
        }
    }
}
