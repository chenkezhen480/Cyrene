package com.harness.ai.model.impl;

import com.harness.ai.model.EmbeddingModelProvider;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import java.util.List;

public class NoOpEmbeddingModelProvider implements EmbeddingModelProvider {
    @Override public Embedding embed(String text) {
        throw new UnsupportedOperationException("Embedding model not configured. Set HARNESS_MODEL_EMBEDDING_PROVIDER.");
    }
    @Override public Embedding embed(TextSegment segment) {
        throw new UnsupportedOperationException("Embedding model not configured. Set HARNESS_MODEL_EMBEDDING_PROVIDER.");
    }
    @Override public List<Embedding> embedAll(List<TextSegment> segments) {
        throw new UnsupportedOperationException("Embedding model not configured. Set HARNESS_MODEL_EMBEDDING_PROVIDER.");
    }
    @Override public int dimension() { return 0; }
    @Override public boolean isAvailable() { return false; }
    @Override public String providerName() { return "none"; }
    @Override public String modelName() { return "none"; }
}
