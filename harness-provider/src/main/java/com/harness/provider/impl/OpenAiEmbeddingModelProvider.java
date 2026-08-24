package com.harness.provider.impl;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.provider.OpenAiTextTokenEstimator;
import com.harness.core.text.TextTokenEstimator;
import com.harness.core.text.UnicodeAwareTextTokenEstimator;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class OpenAiEmbeddingModelProvider implements EmbeddingModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingModelProvider.class);

    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final int dim;
    private final TextTokenEstimator tokenEstimator;
    private volatile OpenAiEmbeddingModel model;

    public OpenAiEmbeddingModelProvider() {
        EnvConfig cfg = EnvConfig.get();
        this.apiKey = cfg.requireString(EnvKey.MODEL_EMBEDDING_API_KEY);
        this.baseUrl = cfg.getString(EnvKey.MODEL_EMBEDDING_BASE_URL, "https://api.openai.com/v1");
        this.modelName = cfg.getString(EnvKey.MODEL_EMBEDDING_MODEL, "text-embedding-3-small");
        this.dim = cfg.getInt(EnvKey.MODEL_EMBEDDING_DIM, EnvKey.MODEL_EMBEDDING_DIM_DEFAULT);
        this.tokenEstimator = createTokenEstimator(modelName);
    }

    private OpenAiEmbeddingModel getOrCreate() {
        if (model == null) {
            synchronized (this) {
                if (model == null) {
                    model = OpenAiEmbeddingModel.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(modelName)
                            .dimensions(dim)
                            .build();
                }
            }
        }
        return model;
    }

    @Override
    public Embedding embed(String text) {
        return getOrCreate().embed(text).content();
    }

    @Override
    public Embedding embed(TextSegment segment) {
        return getOrCreate().embed(segment).content();
    }

    @Override
    public List<Embedding> embedAll(List<TextSegment> segments) {
        return getOrCreate().embedAll(segments).content();
    }

    @Override
    public int dimension() { return dim; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public String providerName() { return "openai"; }

    @Override
    public String modelName() { return modelName; }

    @Override
    public TextTokenEstimator tokenEstimator() { return tokenEstimator; }

    static TextTokenEstimator createTokenEstimator(String modelName) {
        try {
            return new OpenAiTextTokenEstimator(modelName);
        } catch (IllegalArgumentException exception) {
            log.warn("No OpenAI tokenizer mapping for embedding model '{}'; "
                    + "using Unicode-aware token estimates", modelName);
            return UnicodeAwareTextTokenEstimator.INSTANCE;
        }
    }
}
