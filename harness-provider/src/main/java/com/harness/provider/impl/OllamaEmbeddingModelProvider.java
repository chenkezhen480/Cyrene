package com.harness.provider.impl;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;

import java.util.List;

public class OllamaEmbeddingModelProvider implements EmbeddingModelProvider {

    private final OllamaEmbeddingModel model;
    private final int dim;

    public OllamaEmbeddingModelProvider(ModelConfig cfg) {
        String baseUrl = cfg.getString(ModelConfigKey.EMBEDDING_BASE_URL, "http://localhost:11434");
        String modelName = cfg.getString(ModelConfigKey.EMBEDDING_MODEL, "nomic-embed-text");
        this.dim = cfg.getInt(ModelConfigKey.EMBEDDING_DIMENSION, 768);

        this.model = OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
    }

    @Override
    public Embedding embed(String text) {
        return model.embed(text).content();
    }

    @Override
    public Embedding embed(TextSegment segment) {
        return model.embed(segment).content();
    }

    @Override
    public List<Embedding> embedAll(List<TextSegment> segments) {
        return model.embedAll(segments).content();
    }

    @Override
    public int dimension() { return dim; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public String providerName() { return "ollama"; }

    @Override
    public String modelName() { return model.modelName(); }
}
