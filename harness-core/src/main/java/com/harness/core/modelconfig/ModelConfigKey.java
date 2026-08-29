package com.harness.core.modelconfig;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Canonical keys and UI metadata for the standalone {@code model.conf} file. */
public final class ModelConfigKey {

    private ModelConfigKey() {}

    public static final String API_MAX_CONCURRENT = "api.maxConcurrent";
    public static final String CHAT_PROVIDER = "chat.provider";
    public static final String CHAT_API_KEY = "chat.apiKey";
    public static final String CHAT_BASE_URL = "chat.baseUrl";
    public static final String CHAT_MODEL = "chat.model";
    public static final String CHAT_API_FORMAT = "chat.apiFormat";
    public static final String CHAT_MAX_TOKENS = "chat.maxTokens";
    public static final String CHAT_TEMPERATURE = "chat.temperature";
    public static final String CHAT_THINKING = "chat.thinking";
    public static final String CHAT_TIMEOUT_SECONDS = "chat.timeoutSeconds";
    public static final String CHAT_CONTEXT_WINDOW = "chat.contextWindow";
    public static final String CHAT_CAPABILITIES = "chat.capabilities";
    public static final String VISION_PROVIDER = "vision.provider";
    public static final String VISION_API_KEY = "vision.apiKey";
    public static final String VISION_BASE_URL = "vision.baseUrl";
    public static final String VISION_MODEL = "vision.model";
    public static final String VOICE_PROVIDER = "voice.provider";
    public static final String VOICE_API_KEY = "voice.apiKey";
    public static final String VOICE_BASE_URL = "voice.baseUrl";
    public static final String VOICE_ASR_MODEL = "voice.asrModel";
    public static final String VOICE_TTS_MODEL = "voice.ttsModel";
    public static final String VOICE_TIMEOUT_SECONDS = "voice.timeoutSeconds";
    public static final String VOICE_ASR_MAX_SIZE_MB = "voice.asrMaxSizeMb";
    public static final String VOICE_DEFAULT_VOICE = "voice.defaultVoice";
    public static final String EMBEDDING_PROVIDER = "embedding.provider";
    public static final String EMBEDDING_API_KEY = "embedding.apiKey";
    public static final String EMBEDDING_BASE_URL = "embedding.baseUrl";
    public static final String EMBEDDING_MODEL = "embedding.model";
    public static final String EMBEDDING_DIMENSION = "embedding.dimension";
    public static final int EMBEDDING_DIMENSION_DEFAULT = 1024;
    public static final String RERANK_PROVIDER = "rerank.provider";
    public static final String RERANK_API_KEY = "rerank.apiKey";
    public static final String RERANK_BASE_URL = "rerank.baseUrl";
    public static final String RERANK_MODEL = "rerank.model";
    public static final String RERANK_ENABLED = "rerank.enabled";
    public static final String RERANK_TOP_N = "rerank.topN";
    public static final String REALTIME_PROVIDER = "realtime.provider";
    public static final String REALTIME_API_KEY = "realtime.apiKey";
    public static final String REALTIME_BASE_URL = "realtime.baseUrl";
    public static final String SMALL_TASK_PROVIDER = "smallTask.provider";
    public static final String SMALL_TASK_API_KEY = "smallTask.apiKey";
    public static final String SMALL_TASK_BASE_URL = "smallTask.baseUrl";
    public static final String SMALL_TASK_MODEL = "smallTask.model";
    public static final String SMALL_TASK_MAX_TOKENS = "smallTask.maxTokens";
    public static final String SMALL_TASK_TIMEOUT_SECONDS = "smallTask.timeoutSeconds";
    public static final String IMAGE_PROVIDER = "imageGeneration.provider";
    public static final String IMAGE_API_KEY = "imageGeneration.apiKey";
    public static final String IMAGE_BASE_URL = "imageGeneration.baseUrl";
    public static final String IMAGE_MODEL = "imageGeneration.model";
    public static final String VIDEO_PROVIDER = "videoGeneration.provider";
    public static final String VIDEO_API_KEY = "videoGeneration.apiKey";
    public static final String VIDEO_BASE_URL = "videoGeneration.baseUrl";
    public static final String VIDEO_MODEL = "videoGeneration.model";
    public static final String VIDEO_SUBMIT_PATH = "videoGeneration.submitPath";
    public static final String VIDEO_STATUS_PATH = "videoGeneration.statusPath";

    public static final List<Definition> DEFINITIONS = List.of(
            d(API_MAX_CONCURRENT, "global", "模型接口最大并发数"),
            d(CHAT_PROVIDER, "chat", "对话模型服务商"), d(CHAT_API_KEY, "chat", "对话模型密钥", true),
            d(CHAT_BASE_URL, "chat", "对话模型接口地址"), d(CHAT_MODEL, "chat", "对话模型名称"),
            d(CHAT_API_FORMAT, "chat", "对话接口格式"), d(CHAT_MAX_TOKENS, "chat", "最大输出 Token"),
            d(CHAT_TEMPERATURE, "chat", "生成温度"), d(CHAT_THINKING, "chat", "默认启用思考"),
            d(CHAT_TIMEOUT_SECONDS, "chat", "请求超时秒数"), d(CHAT_CONTEXT_WINDOW, "chat", "上下文窗口"),
            d(CHAT_CAPABILITIES, "chat", "多模态能力声明"),
            d(VISION_PROVIDER, "vision", "视觉模型服务商"), d(VISION_API_KEY, "vision", "视觉模型密钥", true),
            d(VISION_BASE_URL, "vision", "视觉模型接口地址"), d(VISION_MODEL, "vision", "视觉模型名称"),
            d(VOICE_PROVIDER, "voice", "语音模型服务商"), d(VOICE_API_KEY, "voice", "语音模型密钥", true),
            d(VOICE_BASE_URL, "voice", "语音模型接口地址"), d(VOICE_ASR_MODEL, "voice", "语音识别模型"),
            d(VOICE_TTS_MODEL, "voice", "语音合成模型"), d(VOICE_TIMEOUT_SECONDS, "voice", "语音请求超时秒数"),
            d(VOICE_ASR_MAX_SIZE_MB, "voice", "语音文件上限 MB"), d(VOICE_DEFAULT_VOICE, "voice", "默认音色"),
            d(EMBEDDING_PROVIDER, "embedding", "向量模型服务商"), d(EMBEDDING_API_KEY, "embedding", "向量模型密钥", true),
            d(EMBEDDING_BASE_URL, "embedding", "向量模型接口地址"), d(EMBEDDING_MODEL, "embedding", "向量模型名称"),
            d(EMBEDDING_DIMENSION, "embedding", "向量维度"),
            d(RERANK_PROVIDER, "rerank", "重排模型服务商"), d(RERANK_API_KEY, "rerank", "重排模型密钥", true),
            d(RERANK_BASE_URL, "rerank", "重排模型接口地址"), d(RERANK_MODEL, "rerank", "重排模型名称"),
            d(RERANK_ENABLED, "rerank", "启用结果重排"), d(RERANK_TOP_N, "rerank", "重排结果数量"),
            d(REALTIME_PROVIDER, "realtime", "实时模型服务商"), d(REALTIME_API_KEY, "realtime", "实时模型密钥", true),
            d(REALTIME_BASE_URL, "realtime", "实时模型接口地址"),
            d(SMALL_TASK_PROVIDER, "smallTask", "小任务模型服务商"), d(SMALL_TASK_API_KEY, "smallTask", "小任务模型密钥", true),
            d(SMALL_TASK_BASE_URL, "smallTask", "小任务模型接口地址"), d(SMALL_TASK_MODEL, "smallTask", "小任务模型名称"),
            d(SMALL_TASK_MAX_TOKENS, "smallTask", "小任务最大输出 Token"),
            d(SMALL_TASK_TIMEOUT_SECONDS, "smallTask", "小任务请求超时秒数"),
            d(IMAGE_PROVIDER, "imageGeneration", "图片生成服务商"), d(IMAGE_API_KEY, "imageGeneration", "图片生成密钥", true),
            d(IMAGE_BASE_URL, "imageGeneration", "图片生成接口地址"), d(IMAGE_MODEL, "imageGeneration", "图片生成模型"),
            d(VIDEO_PROVIDER, "videoGeneration", "视频生成服务商"), d(VIDEO_API_KEY, "videoGeneration", "视频生成密钥", true),
            d(VIDEO_BASE_URL, "videoGeneration", "视频生成接口地址"), d(VIDEO_MODEL, "videoGeneration", "视频生成模型"),
            d(VIDEO_SUBMIT_PATH, "videoGeneration", "视频任务提交路径"), d(VIDEO_STATUS_PATH, "videoGeneration", "视频状态查询路径")
    );

    private static final Map<String, Definition> BY_KEY = DEFINITIONS.stream()
            .collect(Collectors.toUnmodifiableMap(Definition::key, Function.identity()));

    public static boolean isKnown(String key) { return BY_KEY.containsKey(key); }
    public static Definition definition(String key) { return BY_KEY.get(key); }

    private static Definition d(String key, String section, String label) {
        return d(key, section, label, false);
    }

    private static Definition d(String key, String section, String label, boolean sensitive) {
        return new Definition(key, section, label, sensitive);
    }

    public record Definition(String key, String section, String label, boolean sensitive) {}
}
