package com.harness.provider.impl;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;

import java.util.List;

public class OllamaEmbeddingModelProvider implements EmbeddingModelProvider {

    private final OllamaEmbeddingModel model;
    private final int dim;

    public OllamaEmbeddingModelProvider() {
        this(EnvConfig.get());
    }

    public OllamaEmbeddingModelProvider(EnvConfig cfg) {
        String baseUrl = cfg.getString(EnvKey.MODEL_EMBEDDING_BASE_URL, "http://localhost:11434");
        String modelName = cfg.getString(EnvKey.MODEL_EMBEDDING_MODEL, "nomic-embed-text");
        this.dim = cfg.getInt(EnvKey.MODEL_EMBEDDING_DIM, 768);

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
