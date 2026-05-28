package com.harness.ai.model.impl;

import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.util.List;

public class OpenAiEmbeddingModelProvider implements EmbeddingModelProvider {

    private final OpenAiEmbeddingModel model;
    private final int dim;

    public OpenAiEmbeddingModelProvider() {
        EnvConfig cfg = EnvConfig.get();
        String apiKey = cfg.requireString(EnvKey.MODEL_EMBEDDING_API_KEY);
        String baseUrl = cfg.getString(EnvKey.MODEL_EMBEDDING_BASE_URL, "https://api.openai.com/v1");
        String modelName = cfg.getString(EnvKey.MODEL_EMBEDDING_MODEL, "text-embedding-3-small");
        this.dim = cfg.getInt(EnvKey.MODEL_EMBEDDING_DIM, 1536);

        this.model = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .dimensions(dim)
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
    public String providerName() { return "openai"; }

    @Override
    public String modelName() { return model.modelName(); }
}
