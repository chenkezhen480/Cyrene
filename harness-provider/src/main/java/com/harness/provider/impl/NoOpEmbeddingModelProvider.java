package com.harness.provider.impl;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.core.modelconfig.ModelConfigKey;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import java.util.List;

public class NoOpEmbeddingModelProvider implements EmbeddingModelProvider {
    @Override public Embedding embed(String text) {
        throw unavailable();
    }
    @Override public Embedding embed(TextSegment segment) {
        throw unavailable();
    }
    @Override public List<Embedding> embedAll(List<TextSegment> segments) {
        throw unavailable();
    }
    @Override public int dimension() { return 0; }
    @Override public boolean isAvailable() { return false; }
    @Override public String providerName() { return "none"; }
    @Override public String modelName() { return "none"; }
    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("Embedding model not configured. Set "
                + ModelConfigKey.EMBEDDING_PROVIDER + " in model.conf.");
    }
}
