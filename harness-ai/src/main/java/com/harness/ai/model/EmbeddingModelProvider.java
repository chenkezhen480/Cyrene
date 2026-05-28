package com.harness.ai.model;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import java.util.List;

/**
 * 4. Embedding Model Provider
 * Handles: text-to-vector, multimodal vectorization for RAG.
 * Backed by LangChain4j EmbeddingModel.
 */
public interface EmbeddingModelProvider {

    /**
     * Embed a single text.
     */
    Embedding embed(String text);

    /**
     * Embed a text segment.
     */
    Embedding embed(TextSegment segment);

    /**
     * Batch embed multiple texts.
     */
    List<Embedding> embedAll(List<TextSegment> segments);

    /**
     * Get the embedding dimension.
     */
    int dimension();

    /**
     * Check if this provider is available.
     */
    boolean isAvailable();

    String providerName();
    String modelName();
}
