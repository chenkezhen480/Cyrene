package com.harness.core.modelconfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates {@code model.conf} and provides a one-time bridge from pre-0.5.10 keys.
 *
 * <p>The legacy source is consulted only when the target file does not exist.
 * Once {@code model.conf} exists, it remains the sole source of model settings
 * and is never overwritten by this migration.</p>
 */
public final class ModelConfigInitializer {

    private static final List<KeyMapping> KEY_MAPPINGS = List.of(
            m("HARNESS_MODEL_API_MAX_CONCURRENT", ModelConfigKey.API_MAX_CONCURRENT),
            m("HARNESS_MODEL_CHAT_PROVIDER", ModelConfigKey.CHAT_PROVIDER),
            m("HARNESS_MODEL_CHAT_API_KEY", ModelConfigKey.CHAT_API_KEY),
            m("HARNESS_MODEL_CHAT_BASE_URL", ModelConfigKey.CHAT_BASE_URL),
            m("HARNESS_MODEL_CHAT_MODEL", ModelConfigKey.CHAT_MODEL),
            m("HARNESS_MODEL_CHAT_API_FORMAT", ModelConfigKey.CHAT_API_FORMAT),
            m("HARNESS_MODEL_CHAT_MAX_TOKENS", ModelConfigKey.CHAT_MAX_TOKENS),
            m("HARNESS_MODEL_CHAT_TEMPERATURE", ModelConfigKey.CHAT_TEMPERATURE),
            m("HARNESS_MODEL_CHAT_THINKING", ModelConfigKey.CHAT_THINKING),
            m("HARNESS_MODEL_CHAT_TIMEOUT_SECONDS", ModelConfigKey.CHAT_TIMEOUT_SECONDS),
            m("HARNESS_MODEL_CHAT_CONTEXT_WINDOW", ModelConfigKey.CHAT_CONTEXT_WINDOW),
            m("HARNESS_MODEL_CHAT_CAPABILITIES", ModelConfigKey.CHAT_CAPABILITIES),
            m("HARNESS_MODEL_VISION_PROVIDER", ModelConfigKey.VISION_PROVIDER),
            m("HARNESS_MODEL_VISION_API_KEY", ModelConfigKey.VISION_API_KEY),
            m("HARNESS_MODEL_VISION_BASE_URL", ModelConfigKey.VISION_BASE_URL),
            m("HARNESS_MODEL_VISION_MODEL", ModelConfigKey.VISION_MODEL),
            m("HARNESS_MODEL_VOICE_PROVIDER", ModelConfigKey.VOICE_PROVIDER),
            m("HARNESS_MODEL_VOICE_API_KEY", ModelConfigKey.VOICE_API_KEY),
            m("HARNESS_MODEL_VOICE_BASE_URL", ModelConfigKey.VOICE_BASE_URL),
            m("HARNESS_MODEL_VOICE_ASR_MODEL", ModelConfigKey.VOICE_ASR_MODEL),
            m("HARNESS_MODEL_VOICE_TTS_MODEL", ModelConfigKey.VOICE_TTS_MODEL),
            m("HARNESS_MODEL_VOICE_TIMEOUT_SECONDS", ModelConfigKey.VOICE_TIMEOUT_SECONDS),
            m("HARNESS_MODEL_VOICE_ASR_MAX_SIZE_MB", ModelConfigKey.VOICE_ASR_MAX_SIZE_MB),
            m("HARNESS_MODEL_VOICE_TTS_DEFAULT_VOICE", ModelConfigKey.VOICE_DEFAULT_VOICE),
            m("HARNESS_MODEL_EMBEDDING_PROVIDER", ModelConfigKey.EMBEDDING_PROVIDER),
            m("HARNESS_MODEL_EMBEDDING_API_KEY", ModelConfigKey.EMBEDDING_API_KEY),
            m("HARNESS_MODEL_EMBEDDING_BASE_URL", ModelConfigKey.EMBEDDING_BASE_URL),
            m("HARNESS_MODEL_EMBEDDING_MODEL", ModelConfigKey.EMBEDDING_MODEL),
            m("HARNESS_MODEL_EMBEDDING_DIM", ModelConfigKey.EMBEDDING_DIMENSION),
            m("HARNESS_RERANK_ENABLED", ModelConfigKey.RERANK_ENABLED),
            m("HARNESS_MODEL_RERANK_PROVIDER", ModelConfigKey.RERANK_PROVIDER),
            m("HARNESS_MODEL_RERANK_API_KEY", ModelConfigKey.RERANK_API_KEY),
            m("HARNESS_MODEL_RERANK_BASE_URL", ModelConfigKey.RERANK_BASE_URL),
            m("HARNESS_MODEL_RERANK_MODEL", ModelConfigKey.RERANK_MODEL),
            m("HARNESS_RERANK_TOP_N", ModelConfigKey.RERANK_TOP_N),
            m("HARNESS_MODEL_REALTIME_PROVIDER", ModelConfigKey.REALTIME_PROVIDER),
            m("HARNESS_MODEL_REALTIME_API_KEY", ModelConfigKey.REALTIME_API_KEY),
            m("HARNESS_MODEL_REALTIME_BASE_URL", ModelConfigKey.REALTIME_BASE_URL),
            m("HARNESS_MODEL_CLASSIFIER_PROVIDER", ModelConfigKey.SMALL_TASK_PROVIDER),
            m("HARNESS_MODEL_CLASSIFIER_API_KEY", ModelConfigKey.SMALL_TASK_API_KEY),
            m("HARNESS_MODEL_CLASSIFIER_BASE_URL", ModelConfigKey.SMALL_TASK_BASE_URL),
            m("HARNESS_MODEL_CLASSIFIER_MODEL", ModelConfigKey.SMALL_TASK_MODEL),
            m("HARNESS_MODEL_CLASSIFIER_MAX_TOKENS", ModelConfigKey.SMALL_TASK_MAX_TOKENS),
            m("HARNESS_TOOL_IMAGE_GEN_PROVIDER", ModelConfigKey.IMAGE_PROVIDER),
            m("HARNESS_TOOL_IMAGE_GEN_API_KEY", ModelConfigKey.IMAGE_API_KEY),
            m("HARNESS_TOOL_IMAGE_GEN_BASE_URL", ModelConfigKey.IMAGE_BASE_URL),
            m("HARNESS_TOOL_IMAGE_GEN_MODEL", ModelConfigKey.IMAGE_MODEL),
            m("HARNESS_TOOL_VIDEO_GEN_PROVIDER", ModelConfigKey.VIDEO_PROVIDER),
            m("HARNESS_TOOL_VIDEO_GEN_API_KEY", ModelConfigKey.VIDEO_API_KEY),
            m("HARNESS_TOOL_VIDEO_GEN_BASE_URL", ModelConfigKey.VIDEO_BASE_URL),
            m("HARNESS_TOOL_VIDEO_GEN_MODEL", ModelConfigKey.VIDEO_MODEL),
            m("HARNESS_TOOL_VIDEO_GEN_SUBMIT_PATH", ModelConfigKey.VIDEO_SUBMIT_PATH),
            m("HARNESS_TOOL_VIDEO_GEN_STATUS_PATH", ModelConfigKey.VIDEO_STATUS_PATH)
    );

    private ModelConfigInitializer() {}

    public static InitializationResult initializeIfMissing(
            ModelConfigFile configFile,
            Map<String, String> legacyValues
    ) throws IOException {
        Objects.requireNonNull(configFile, "configFile");
        Objects.requireNonNull(legacyValues, "legacyValues");
        if (Files.exists(configFile.path())) {
            return InitializationResult.EXISTING;
        }

        Map<String, String> migratedValues = new LinkedHashMap<>();
        for (KeyMapping mapping : KEY_MAPPINGS) {
            String value = legacyValues.get(mapping.legacyKey());
            if (value != null && !value.isBlank()) {
                migratedValues.put(mapping.modelConfigKey(), value.trim());
            }
        }
        configFile.replace(ModelConfig.of(migratedValues));
        return migratedValues.isEmpty()
                ? InitializationResult.CREATED_EMPTY
                : InitializationResult.MIGRATED_LEGACY;
    }

    private static KeyMapping m(String legacyKey, String modelConfigKey) {
        return new KeyMapping(legacyKey, modelConfigKey);
    }

    private record KeyMapping(String legacyKey, String modelConfigKey) {}

    public enum InitializationResult {
        EXISTING,
        CREATED_EMPTY,
        MIGRATED_LEGACY
    }
}
