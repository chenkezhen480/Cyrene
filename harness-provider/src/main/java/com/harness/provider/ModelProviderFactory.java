package com.harness.provider;

import com.harness.provider.impl.*;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating model providers from one immutable {@code model.conf} snapshot.
 */
public final class ModelProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderFactory.class);

    private ModelProviderFactory() {}

    /** Create and validate a complete provider set from an isolated configuration snapshot. */
    public static ModelProviders createAll(ModelConfig config) {
        return new ModelProviders(
                createChat(config),
                createVision(config),
                createVoice(config),
                createEmbedding(config),
                createRerank(config),
                createRealtime(config),
                createSmallTask(config), createRouting(config));
    }

    /**
     * 1. General Chat Model.
     *
     * <p>The process may start without this provider so an administrator can
     * complete the initial configuration through the Web console. Agent calls
     * remain unavailable until a concrete provider is activated.</p>
     */
    public static ChatModelProvider createChat(ModelConfig config) {
        String provider = normalizedProvider(config, ModelConfigKey.CHAT_PROVIDER);
        if (provider.isBlank() || "none".equals(provider)) {
            log.info("[Model] Chat model not configured; Agent execution is disabled until configured");
            return new NoOpChatModelProvider();
        }
        ChatApiFormat apiFormat = validateChatApiFormat(
                provider,
                config.getString(
                        ModelConfigKey.CHAT_API_FORMAT,
                        defaultChatApiFormat(provider).configValue()));
        log.info("Creating chat model provider: {}", provider);
        ChatModelProvider chatProvider = switch (provider) {
            case "openai", "dashscope" -> new OpenAiChatModelProvider(config, apiFormat);
            case "deepseek" -> apiFormat == ChatApiFormat.MESSAGES
                    ? new DeepSeekMessagesChatModelProvider(config)
                    : new OpenAiChatModelProvider(config, apiFormat);
            case "anthropic", "claude" -> new AnthropicChatModelProvider(config);
            case "ollama" -> new OllamaChatModelProvider(config);
            default -> throw new IllegalStateException("Unknown chat model provider: " + provider);
        };
        log.info("[Model] Chat model={}, contextWindow={}", chatProvider.modelName(), chatProvider.contextWindow());

        // Wrap with Semaphore for LLM API concurrency control
        // 同一个 provider 的 chat/streaming 共享一个 Semaphore（同一个 API 端点）
        int maxConcurrent = config.getInt(ModelConfigKey.API_MAX_CONCURRENT, 10);
        if (maxConcurrent <= 0) {
            throw new IllegalStateException(ModelConfigKey.API_MAX_CONCURRENT + " must be positive");
        }
        Semaphore semaphore = new Semaphore(maxConcurrent, true);
        log.info("[Semaphore] LLM API concurrency limit={}, fair=true", maxConcurrent);
        ChatModel chatModel = new SemaphoreChatModel(chatProvider.chatModel(), semaphore);
        StreamingChatModel streamingRaw = chatProvider.streamingModel();
        StreamingChatModel streamingModel = streamingRaw != null
                ? new SemaphoreStreamingChatModel(streamingRaw, semaphore) : null;
        return new SemaphoreChatModelProvider(
                chatProvider, chatModel, streamingModel,
                configuredContextWindow(config, chatProvider.modelName()),
                ModalCapabilityRegistry.getCapabilities(
                        chatProvider.modelName(),
                        config.getCommaList(ModelConfigKey.CHAT_CAPABILITIES)));
    }

    static ChatApiFormat validateChatApiFormat(
            String provider,
            String configuredFormat
    ) {
        ChatApiFormat apiFormat = ChatApiFormat.parse(configuredFormat);
        boolean openAiCompatible = "openai".equals(provider)
                || "dashscope".equals(provider)
                || "deepseek".equals(provider);
        if (apiFormat == ChatApiFormat.RESPONSES && !openAiCompatible) {
            throw new IllegalStateException(
                    ModelConfigKey.CHAT_API_FORMAT + "=responses requires an OpenAI-compatible "
                            + "chat provider, but " + ModelConfigKey.CHAT_PROVIDER + "=" + provider);
        }
        if (apiFormat == ChatApiFormat.MESSAGES && !"deepseek".equals(provider)) {
            throw new IllegalStateException(
                    ModelConfigKey.CHAT_API_FORMAT + "=messages is currently supported for "
                            + ModelConfigKey.CHAT_PROVIDER + "=deepseek");
        }
        return apiFormat;
    }

    static ChatApiFormat defaultChatApiFormat(String provider) {
        return "deepseek".equals(provider)
                ? ChatApiFormat.MESSAGES
                : ChatApiFormat.CHAT_COMPLETIONS;
    }

    /**
     * 2. Vision Model (optional, falls back to chat model if not configured)
     */
    public static VisionModelProvider createVision(ModelConfig config) {
        String provider = normalizedProvider(config, ModelConfigKey.VISION_PROVIDER);
        if (provider.isBlank()) {
            provider = normalizedProvider(config, ModelConfigKey.CHAT_PROVIDER);
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
    public static VoiceModelProvider createVoice(ModelConfig config) {
        String provider = normalizedProvider(config, ModelConfigKey.VOICE_PROVIDER);
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
     * 4. Embedding Model (optional, needed for RAG)
     */
    public static EmbeddingModelProvider createEmbedding(ModelConfig config) {
        String provider = normalizedProvider(config, ModelConfigKey.EMBEDDING_PROVIDER);
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
    public static RerankModelProvider createRerank(ModelConfig config) {
        int topN = config.getInt(ModelConfigKey.RERANK_TOP_N, 3);
        if (topN <= 0) {
            throw new IllegalStateException(ModelConfigKey.RERANK_TOP_N + " must be positive");
        }
        if (!config.getBool(ModelConfigKey.RERANK_ENABLED, true)) {
            return new NoOpRerankModelProvider(topN);
        }
        String provider = normalizedProvider(config, ModelConfigKey.RERANK_PROVIDER);
        if (provider.isBlank()) {
            return new NoOpRerankModelProvider(topN);
        }
        log.info("Creating rerank model provider: {}", provider);
        return switch (provider) {
            case "openai", "dashscope" -> new OpenAiRerankModelProvider(config);
            case "none" -> new NoOpRerankModelProvider(topN);
            default -> throw unsupportedProvider("rerank", provider);
        };
    }

    /** 6. Realtime Model (optional). */
    public static RealtimeModelProvider createRealtime(ModelConfig config) {
        String provider = normalizedProvider(config, ModelConfigKey.REALTIME_PROVIDER);
        if (provider.isBlank() || "none".equals(provider)) {
            return new NoOpRealtimeModelProvider();
        }
        log.info("Creating realtime model provider: {}", provider);
        return switch (provider) {
            case "qwen", "dashscope" -> new QwenRealtimeModelProvider(config);
            default -> throw unsupportedProvider("realtime", provider);
        };
    }

    /**
     * 7. Small-task Model (optional, independent of pre-loop routing)
     */
    public static SmallTaskModelProvider createSmallTask(ModelConfig config) {
        String provider = normalizedProvider(config, ModelConfigKey.SMALL_TASK_PROVIDER);
        if (provider.isBlank()) {
            log.info("[Model] Small-task model not configured");
            return new NoOpSmallTaskModelProvider();
        }
        log.info("Creating small-task model provider: {}", provider);
        return switch (provider) {
            case "openai", "dashscope" -> new OpenAiSmallTaskModelProvider(config);
            case "none" -> new NoOpSmallTaskModelProvider();
            default -> throw unsupportedProvider("small-task", provider);
        };
    }

    public static RoutingModelProvider createRouting(ModelConfig config) {
        return switch (normalizedProvider(config, ModelConfigKey.ROUTING_PROVIDER)) {
            case "", "none" -> RoutingModelProvider.DISABLED;
            case "jev" -> new JevRoutingModelProvider(config);
            default -> throw unsupportedProvider("routing", config.getString(ModelConfigKey.ROUTING_PROVIDER));
        };
    }

    private static String normalizedProvider(ModelConfig config, String key) {
        return config.getString(key, "").trim().toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException unsupportedProvider(String capability, String provider) {
        return new IllegalStateException(
                "Unsupported " + capability + " model provider: " + provider);
    }

    private static int configuredContextWindow(ModelConfig config, String modelName) {
        int configured = config.getInt(ModelConfigKey.CHAT_CONTEXT_WINDOW, 0);
        if (config.getString(ModelConfigKey.CHAT_CONTEXT_WINDOW) != null && configured <= 0) {
            throw new IllegalStateException(ModelConfigKey.CHAT_CONTEXT_WINDOW + " must be positive");
        }
        return configured > 0
                ? configured
                : ChatModelProvider.resolveContextWindow(modelName);
    }
}
