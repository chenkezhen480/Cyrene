package com.harness.ai.model;

import com.harness.ai.model.impl.*;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating all 6 model type providers.
 * Each model type is independently configured via environment variables.
 */
public final class ModelProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderFactory.class);

    private ModelProviderFactory() {}

    /**
     * 1. General Chat Model (required)
     */
    public static ChatModelProvider createChat() {
        String provider = EnvConfig.get().getString(EnvKey.MODEL_CHAT_PROVIDER, "openai");
        log.info("Creating chat model provider: {}", provider);
        return switch (provider.toLowerCase()) {
            case "openai", "dashscope" -> new OpenAiChatModelProvider();
            case "anthropic", "claude" -> new AnthropicChatModelProvider();
            case "ollama" -> new OllamaChatModelProvider();
            default -> throw new IllegalStateException("Unknown chat model provider: " + provider);
        };
    }

    /**
     * 2. Vision Model (optional, falls back to chat model if not configured)
     */
    public static VisionModelProvider createVision() {
        String provider = EnvConfig.get().getString(EnvKey.MODEL_VISION_PROVIDER, "");
        if (provider.isBlank()) {
            log.info("No separate vision model configured, vision will use chat model");
            return new NoOpVisionModelProvider();
        }
        log.info("Creating vision model provider: {}", provider);
        return switch (provider.toLowerCase()) {
            case "openai", "dashscope" -> new OpenAiVisionModelProvider();
            case "anthropic", "claude" -> new AnthropicVisionModelProvider();
            default -> new NoOpVisionModelProvider();
        };
    }

    /**
     * 3. Voice Model (optional, ASR + TTS)
     */
    public static VoiceModelProvider createVoice() {
        String provider = EnvConfig.get().getString(EnvKey.MODEL_VOICE_PROVIDER, "");
        if (provider.isBlank()) {
            log.info("No voice model configured");
            return new NoOpVoiceModelProvider();
        }
        log.info("Creating voice model provider: {}", provider);
        return switch (provider.toLowerCase()) {
            case "openai" -> new OpenAiVoiceModelProvider();
            default -> new NoOpVoiceModelProvider();
        };
    }

    /**
     * 4. Embedding Model (optional, needed for pgvector RAG)
     */
    public static EmbeddingModelProvider createEmbedding() {
        String provider = EnvConfig.get().getString(EnvKey.MODEL_EMBEDDING_PROVIDER, "");
        if (provider.isBlank()) {
            log.info("No embedding model configured");
            return new NoOpEmbeddingModelProvider();
        }
        log.info("Creating embedding model provider: {}", provider);
        return switch (provider.toLowerCase()) {
            case "openai", "dashscope" -> new OpenAiEmbeddingModelProvider();
            case "ollama" -> new OllamaEmbeddingModelProvider();
            default -> new NoOpEmbeddingModelProvider();
        };
    }

    /**
     * 5. Rerank Model (optional)
     */
    public static RerankModelProvider createRerank() {
        String provider = EnvConfig.get().getString(EnvKey.MODEL_RERANK_PROVIDER, "");
        if (provider.isBlank()) {
            log.info("No rerank model configured");
            return new NoOpRerankModelProvider();
        }
        log.info("Creating rerank model provider: {}", provider);
        return switch (provider.toLowerCase()) {
            case "openai", "dashscope" -> new OpenAiRerankModelProvider();
            default -> new NoOpRerankModelProvider();
        };
    }

    /**
     * 6. Realtime Model (optional, reserved)
     */
    public static RealtimeModelProvider createRealtime() {
        String provider = EnvConfig.get().getString(EnvKey.MODEL_REALTIME_PROVIDER, "");
        if (provider.isBlank()) {
            return new NoOpRealtimeModelProvider();
        }
        log.info("Realtime model provider not yet implemented: {}", provider);
        return new NoOpRealtimeModelProvider();
    }
}
