package com.harness.provider;

import com.harness.provider.impl.*;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.Locale;
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

    /** Create the complete provider set once for the application runtime. */
    public static ModelProviders createAll() {
        return createAll(EnvConfig.get());
    }

    /** Create and validate a complete provider set from an isolated configuration snapshot. */
    public static ModelProviders createAll(EnvConfig config) {
        return new ModelProviders(
                createChat(config),
                createVision(config),
                createVoice(config),
                createEmbedding(config),
                createRerank(config),
                createRealtime(config),
                createClassifier(config));
    }

    /**
     * 1. General Chat Model (required)
     */
    public static ChatModelProvider createChat() {
        return createChat(EnvConfig.get());
    }

    public static ChatModelProvider createChat(EnvConfig config) {
        String provider = config.getString(EnvKey.MODEL_CHAT_PROVIDER, "openai")
                .trim().toLowerCase(Locale.ROOT);
        OpenAiChatApiFormat apiFormat = validateChatApiFormat(
                provider,
                config.getString(
                        EnvKey.MODEL_CHAT_API_FORMAT,
                        OpenAiChatApiFormat.CHAT_COMPLETIONS.configValue()));
        log.info("Creating chat model provider: {}", provider);
        ChatModelProvider chatProvider = switch (provider) {
            case "openai", "dashscope" -> new OpenAiChatModelProvider(config, apiFormat);
            case "anthropic", "claude" -> new AnthropicChatModelProvider(config);
            case "ollama" -> new OllamaChatModelProvider(config);
            default -> throw new IllegalStateException("Unknown chat model provider: " + provider);
        };
        log.info("[Model] Chat model={}, contextWindow={}", chatProvider.modelName(), chatProvider.contextWindow());

        // Wrap with Semaphore for LLM API concurrency control
        // 同一个 provider 的 chat/streaming 共享一个 Semaphore（同一个 API 端点）
        int maxConcurrent = config.getInt(EnvKey.MODEL_API_MAX_CONCURRENT, 10);
        if (maxConcurrent <= 0) {
            throw new IllegalStateException("HARNESS_MODEL_API_MAX_CONCURRENT must be positive");
        }
        Semaphore semaphore = new Semaphore(maxConcurrent, true);
        log.info("[Semaphore] LLM API concurrency limit={}, fair=true", maxConcurrent);
        ChatModel chatModel = new SemaphoreChatModel(chatProvider.chatModel(), semaphore);
        StreamingChatModel streamingRaw = chatProvider.streamingModel();
        StreamingChatModel streamingModel = streamingRaw != null
                ? new SemaphoreStreamingChatModel(streamingRaw, semaphore) : null;
        return new SemaphoreChatModelProvider(
                chatProvider, chatModel, streamingModel,
                configuredContextWindow(config, chatProvider.modelName()));
    }

    static OpenAiChatApiFormat validateChatApiFormat(
            String provider,
            String configuredFormat
    ) {
        OpenAiChatApiFormat apiFormat = OpenAiChatApiFormat.parse(configuredFormat);
        boolean openAiCompatible = "openai".equals(provider) || "dashscope".equals(provider);
        if (apiFormat == OpenAiChatApiFormat.RESPONSES && !openAiCompatible) {
            throw new IllegalStateException(
                    "HARNESS_MODEL_CHAT_API_FORMAT=responses requires an OpenAI-compatible "
                            + "chat provider, but HARNESS_MODEL_CHAT_PROVIDER=" + provider);
        }
        return apiFormat;
    }

    /**
     * 2. Vision Model (optional, falls back to chat model if not configured)
     */
    public static VisionModelProvider createVision() {
        return createVision(EnvConfig.get());
    }

    public static VisionModelProvider createVision(EnvConfig config) {
        String provider = normalizedProvider(config, EnvKey.MODEL_VISION_PROVIDER);
        if (provider.isBlank()) {
            provider = normalizedProvider(config, EnvKey.MODEL_CHAT_PROVIDER);
            if (provider.isBlank()) {
                return new NoOpVisionModelProvider();
            }
            log.info("Vision provider not set; reusing multimodal chat provider: {}", provider);
        }
        log.info("Creating vision model provider: {}", provider);
        return switch (provider) {
            case "openai", "dashscope" -> new OpenAiVisionModelProvider(config);
            case "anthropic", "claude" -> new AnthropicVisionModelProvider(config);
            case "none", "ollama" -> new NoOpVisionModelProvider();
            default -> throw unsupportedProvider("vision", provider);
        };
    }

    /**
     * 3. Voice Model (optional, ASR + TTS)
     */
    public static VoiceModelProvider createVoice() {
        return createVoice(EnvConfig.get());
    }

    public static VoiceModelProvider createVoice(EnvConfig config) {
        String provider = normalizedProvider(config, EnvKey.MODEL_VOICE_PROVIDER);
        if (provider.isBlank()) {
            return new NoOpVoiceModelProvider();
        }
        log.info("Creating voice model provider: {}", provider);
        return switch (provider) {
            case "openai" -> new OpenAiVoiceModelProvider(config);
            case "none" -> new NoOpVoiceModelProvider();
            default -> throw unsupportedProvider("voice", provider);
        };
    }

    /**
     * 4. Embedding Model (optional, needed for pgvector RAG)
     */
    public static EmbeddingModelProvider createEmbedding() {
        return createEmbedding(EnvConfig.get());
    }

    public static EmbeddingModelProvider createEmbedding(EnvConfig config) {
        String provider = normalizedProvider(config, EnvKey.MODEL_EMBEDDING_PROVIDER);
        if (provider.isBlank()) {
            return new NoOpEmbeddingModelProvider();
        }
        log.info("Creating embedding model provider: {}", provider);
        return switch (provider) {
            case "openai", "dashscope" -> new OpenAiEmbeddingModelProvider(config);
            case "ollama" -> new OllamaEmbeddingModelProvider(config);
            case "none" -> new NoOpEmbeddingModelProvider();
            default -> throw unsupportedProvider("embedding", provider);
        };
    }

    /**
     * 5. Rerank Model (optional)
     */
    public static RerankModelProvider createRerank() {
        return createRerank(EnvConfig.get());
    }

    public static RerankModelProvider createRerank(EnvConfig config) {
        String provider = normalizedProvider(config, EnvKey.MODEL_RERANK_PROVIDER);
        if (provider.isBlank()) {
            return new NoOpRerankModelProvider();
        }
        log.info("Creating rerank model provider: {}", provider);
        return switch (provider) {
            case "openai", "dashscope" -> new OpenAiRerankModelProvider(config);
            case "none" -> new NoOpRerankModelProvider();
            default -> throw unsupportedProvider("rerank", provider);
        };
    }

    /**
     * 6. Realtime Model (optional, reserved)
     */
    public static RealtimeModelProvider createRealtime() {
        return createRealtime(EnvConfig.get());
    }

    public static RealtimeModelProvider createRealtime(EnvConfig config) {
        String provider = normalizedProvider(config, EnvKey.MODEL_REALTIME_PROVIDER);
        if (provider.isBlank() || "none".equals(provider)) {
            return new NoOpRealtimeModelProvider();
        }
        throw unsupportedProvider("realtime", provider);
    }

    /**
     * 7. Classifier Model (optional, for GapAnalyzer Tier 2 LLM classification)
     */
    public static ClassifierModelProvider createClassifier() {
        return createClassifier(EnvConfig.get());
    }

    public static ClassifierModelProvider createClassifier(EnvConfig config) {
        String provider = normalizedProvider(config, EnvKey.MODEL_CLASSIFIER_PROVIDER);
        if (provider.isBlank()) {
            log.info("[Model] Classifier model not configured, Tier 2 disabled");
            return new NoOpClassifierModelProvider();
        }
        log.info("Creating classifier model provider: {}", provider);
        return switch (provider) {
            case "openai", "dashscope" -> new OpenAiClassifierModelProvider(config);
            case "none" -> new NoOpClassifierModelProvider();
            default -> throw unsupportedProvider("classifier", provider);
        };
    }

    private static String normalizedProvider(EnvConfig config, String key) {
        return config.getString(key, "").trim().toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException unsupportedProvider(String capability, String provider) {
        return new IllegalStateException(
                "Unsupported " + capability + " model provider: " + provider);
    }

    private static int configuredContextWindow(EnvConfig config, String modelName) {
        int configured = config.getInt(EnvKey.MODEL_CHAT_CONTEXT_WINDOW, 0);
        if (configured < 0) {
            throw new IllegalStateException("HARNESS_MODEL_CHAT_CONTEXT_WINDOW cannot be negative");
        }
        return configured > 0
                ? configured
                : ChatModelProvider.resolveContextWindow(modelName);
    }
}
