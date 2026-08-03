package com.harness.ai.model;

import com.harness.ai.model.impl.*;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.concurrent.Semaphore;
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
        ChatModelProvider chatProvider = switch (provider.toLowerCase()) {
            case "openai", "dashscope" -> new OpenAiChatModelProvider();
            case "anthropic", "claude" -> new AnthropicChatModelProvider();
            case "ollama" -> new OllamaChatModelProvider();
            default -> throw new IllegalStateException("Unknown chat model provider: " + provider);
        };
        log.info("[Model] Chat model={}, contextWindow={}", chatProvider.modelName(), chatProvider.contextWindow());

        // Wrap with Semaphore for LLM API concurrency control
        // 同一个 provider 的 chat/streaming 共享一个 Semaphore（同一个 API 端点）
        int maxConcurrent = EnvConfig.get().getInt(EnvKey.MODEL_API_MAX_CONCURRENT, 10);
        Semaphore semaphore = new Semaphore(maxConcurrent, true);
        log.info("[Semaphore] LLM API concurrency limit={}, fair=true", maxConcurrent);
        ChatModel chatModel = new SemaphoreChatModel(chatProvider.chatModel(), semaphore);
        StreamingChatModel streamingRaw = chatProvider.streamingModel();
        StreamingChatModel streamingModel = streamingRaw != null
                ? new SemaphoreStreamingChatModel(streamingRaw, semaphore) : null;
        return new SemaphoreChatModelProvider(chatProvider, chatModel, streamingModel);
    }

    /**
     * 2. Vision Model (optional, falls back to chat model if not configured)
     */
    public static VisionModelProvider createVision() {
        String provider = EnvConfig.get().getString(EnvKey.MODEL_VISION_PROVIDER, "");
        if (provider.isBlank()) {
            provider = EnvConfig.get().getString(EnvKey.MODEL_CHAT_PROVIDER, "");
            if (provider.isBlank()) {
                return new NoOpVisionModelProvider();
            }
            log.info("Vision provider not set; reusing multimodal chat provider: {}", provider);
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

    /**
     * 7. Classifier Model (optional, for GapAnalyzer Tier 2 LLM classification)
     */
    public static ClassifierModelProvider createClassifier() {
        String provider = EnvConfig.get().getString(EnvKey.MODEL_CLASSIFIER_PROVIDER, "");
        if (provider.isBlank()) {
            log.info("[Model] Classifier model not configured, Tier 2 disabled");
            return new NoOpClassifierModelProvider();
        }
        log.info("Creating classifier model provider: {}", provider);
        return switch (provider.toLowerCase()) {
            case "openai", "dashscope" -> new OpenAiClassifierModelProvider();
            default -> {
                log.warn("Unknown classifier provider '{}', falling back to NoOp", provider);
                yield new NoOpClassifierModelProvider();
            }
        };
    }
}
