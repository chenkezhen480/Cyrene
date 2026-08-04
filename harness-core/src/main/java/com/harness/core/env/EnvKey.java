package com.harness.core.env;

/**
 * All environment variable keys used by Harness Agent.
 * Convention: HARNESS_&lt;MODULE&gt;_&lt;PARAMETER&gt;
 *
 * <p>变量命名规范：
 * <ul>
 *   <li>通用变量（PG/Milvus 共用）：HARNESS_RAG_*</li>
 *   <li>PG 专用变量（已废弃）：HARNESS_RAG_PG_*</li>
 *   <li>Milvus 专用变量：HARNESS_RAG_MILVUS_*</li>
 * </ul>
 */
public final class EnvKey {

    private EnvKey() {}

    // ==================== Gap Analysis / 动态路由 ====================
    /**
     * 功能总开关，默认 true。关闭后所有字段回退全局静态配置。
     * <p>三级判定漏斗：Tier 0 显式覆盖 → Tier 1 规则引擎（&lt;1ms）→ Tier 2 LLM 分类。
     * 三个独立字段：needsThinking / needsKnowledgeBase / needsWebSearch。
     * 判定结果写入 trace metadata（gap_source = explicit/rule/llm/default）。
     */
    public static final String GAP_ANALYSIS_ENABLED = "HARNESS_GAP_ANALYSIS_ENABLED";

    // ==================== 1. Chat Model (通用对话+工具调用) ====================
    public static final String MODEL_CHAT_PROVIDER    = "HARNESS_MODEL_CHAT_PROVIDER";
    public static final String MODEL_CHAT_API_KEY     = "HARNESS_MODEL_CHAT_API_KEY";
    public static final String MODEL_CHAT_BASE_URL    = "HARNESS_MODEL_CHAT_BASE_URL";
    public static final String MODEL_CHAT_MODEL       = "HARNESS_MODEL_CHAT_MODEL";
    public static final String MODEL_CHAT_MAX_TOKENS  = "HARNESS_MODEL_CHAT_MAX_TOKENS";
    public static final String MODEL_CHAT_TEMPERATURE = "HARNESS_MODEL_CHAT_TEMPERATURE";
    /** 是否开启思考/推理模式（影响 DashScope 等支持思考模式的 API），默认 true */
    public static final String MODEL_CHAT_THINKING   = "HARNESS_MODEL_CHAT_THINKING";
    /** LLM API 超时时间（秒），默认 300（5分钟） */
    public static final String MODEL_CHAT_TIMEOUT_SECONDS = "HARNESS_MODEL_CHAT_TIMEOUT_SECONDS";

    // ==================== 2. Vision Model (图片识别/视频分析) ====================
    public static final String MODEL_VISION_PROVIDER  = "HARNESS_MODEL_VISION_PROVIDER";
    public static final String MODEL_VISION_API_KEY   = "HARNESS_MODEL_VISION_API_KEY";
    public static final String MODEL_VISION_BASE_URL  = "HARNESS_MODEL_VISION_BASE_URL";
    public static final String MODEL_VISION_MODEL     = "HARNESS_MODEL_VISION_MODEL";

    // ==================== 3. Voice Model (ASR语音识别 + TTS语音合成) ====================
    public static final String MODEL_VOICE_PROVIDER   = "HARNESS_MODEL_VOICE_PROVIDER";
    public static final String MODEL_VOICE_API_KEY    = "HARNESS_MODEL_VOICE_API_KEY";
    public static final String MODEL_VOICE_BASE_URL   = "HARNESS_MODEL_VOICE_BASE_URL";
    public static final String MODEL_VOICE_ASR_MODEL  = "HARNESS_MODEL_VOICE_ASR_MODEL";
    public static final String MODEL_VOICE_TTS_MODEL  = "HARNESS_MODEL_VOICE_TTS_MODEL";
    public static final String MODEL_VOICE_TIMEOUT_SECONDS = "HARNESS_MODEL_VOICE_TIMEOUT_SECONDS";
    public static final String MODEL_VOICE_ASR_MAX_SIZE_MB = "HARNESS_MODEL_VOICE_ASR_MAX_SIZE_MB";
    public static final String MODEL_VOICE_TTS_DEFAULT_VOICE = "HARNESS_MODEL_VOICE_TTS_DEFAULT_VOICE";

    // ==================== 4. Embedding Model (多模态向量化) ====================
    public static final String MODEL_EMBEDDING_PROVIDER = "HARNESS_MODEL_EMBEDDING_PROVIDER";
    public static final String MODEL_EMBEDDING_API_KEY  = "HARNESS_MODEL_EMBEDDING_API_KEY";
    public static final String MODEL_EMBEDDING_BASE_URL = "HARNESS_MODEL_EMBEDDING_BASE_URL";
    public static final String MODEL_EMBEDDING_MODEL    = "HARNESS_MODEL_EMBEDDING_MODEL";
    public static final String MODEL_EMBEDDING_DIM      = "HARNESS_MODEL_EMBEDDING_DIM";
    /** Embedding 向量维度默认值 */
    public static final int MODEL_EMBEDDING_DIM_DEFAULT = 1024;

    // ==================== 5. Rerank Model (向量检索结果排序) ====================
    public static final String MODEL_RERANK_PROVIDER  = "HARNESS_MODEL_RERANK_PROVIDER";
    public static final String MODEL_RERANK_API_KEY   = "HARNESS_MODEL_RERANK_API_KEY";
    public static final String MODEL_RERANK_BASE_URL  = "HARNESS_MODEL_RERANK_BASE_URL";
    public static final String MODEL_RERANK_MODEL     = "HARNESS_MODEL_RERANK_MODEL";
    /** 是否启用 rerank，默认 false */
    public static final String RERANK_ENABLED        = "HARNESS_RERANK_ENABLED";
    /** Rerank 取 top N 个结果，默认 3 */
    public static final String RERANK_TOP_N          = "HARNESS_RERANK_TOP_N";

    // ==================== 6. Realtime Model (实时多模态-预留) ====================
    public static final String MODEL_REALTIME_PROVIDER = "HARNESS_MODEL_REALTIME_PROVIDER";
    public static final String MODEL_REALTIME_API_KEY  = "HARNESS_MODEL_REALTIME_API_KEY";
    public static final String MODEL_REALTIME_BASE_URL = "HARNESS_MODEL_REALTIME_BASE_URL";

    // ==================== 7. Classifier Model (意图分类/路由 / GapAnalyzer Tier 2) ====================
    /** 分类器 Provider，留空则禁用 Tier 2（仅 Tier 0+1 生效）。支持 openai 兼容 API */
    public static final String MODEL_CLASSIFIER_PROVIDER   = "HARNESS_MODEL_CLASSIFIER_PROVIDER";
    public static final String MODEL_CLASSIFIER_API_KEY    = "HARNESS_MODEL_CLASSIFIER_API_KEY";
    public static final String MODEL_CLASSIFIER_BASE_URL   = "HARNESS_MODEL_CLASSIFIER_BASE_URL";
    public static final String MODEL_CLASSIFIER_MODEL      = "HARNESS_MODEL_CLASSIFIER_MODEL";
    /** 分类器最大输出 token，默认 50（只够输出压缩 JSON） */
    public static final String MODEL_CLASSIFIER_MAX_TOKENS = "HARNESS_MODEL_CLASSIFIER_MAX_TOKENS";

    // ==================== 8. Image Generation Model (图片生成) ====================
    /** 图片生成 provider（如 openai/azure），默认 openai */
    public static final String TOOL_IMAGE_GEN_PROVIDER   = "HARNESS_TOOL_IMAGE_GEN_PROVIDER";
    /** 图片生成 API key */
    public static final String TOOL_IMAGE_GEN_API_KEY    = "HARNESS_TOOL_IMAGE_GEN_API_KEY";
    /** 图片生成 API base URL，默认 https://api.openai.com/v1 */
    public static final String TOOL_IMAGE_GEN_BASE_URL   = "HARNESS_TOOL_IMAGE_GEN_BASE_URL";
    /** 图片生成模型名，默认 dall-e-3 */
    public static final String TOOL_IMAGE_GEN_MODEL      = "HARNESS_TOOL_IMAGE_GEN_MODEL";

    // ==================== 9. Video Generation Model (视频生成) ====================
    /** 视频生成 provider（如 kling/runway/sora） */
    public static final String TOOL_VIDEO_GEN_PROVIDER   = "HARNESS_TOOL_VIDEO_GEN_PROVIDER";
    /** 视频生成 API key */
    public static final String TOOL_VIDEO_GEN_API_KEY    = "HARNESS_TOOL_VIDEO_GEN_API_KEY";
    /** 视频生成 API base URL（含版本路径，如 https://api.kling.ai/v1） */
    public static final String TOOL_VIDEO_GEN_BASE_URL   = "HARNESS_TOOL_VIDEO_GEN_BASE_URL";
    /** 视频生成模型名 */
    public static final String TOOL_VIDEO_GEN_MODEL      = "HARNESS_TOOL_VIDEO_GEN_MODEL";
    /** 视频提交 endpoint 路径（拼接在 BASE_URL 后），默认 /submit */
    public static final String TOOL_VIDEO_GEN_SUBMIT_PATH = "HARNESS_TOOL_VIDEO_GEN_SUBMIT_PATH";
    /** 视频状态查询 endpoint 路径（拼接在 BASE_URL 后 + /{taskId}），默认 /status */
    public static final String TOOL_VIDEO_GEN_STATUS_PATH = "HARNESS_TOOL_VIDEO_GEN_STATUS_PATH";

    // ==================== Log Storage ====================
    /** 日志存储目录，默认 ./logs */
    public static final String LOG_STORAGE_DIR       = "HARNESS_LOG_STORAGE_DIR";
    /** 日志保留天数，默认 7 */
    public static final String LOG_RETENTION_DAYS    = "HARNESS_LOG_RETENTION_DAYS";

    // ==================== Auth ====================
    public static final String AUTH_MODE             = "HARNESS_AUTH_MODE";
    public static final String AUTH_TOKEN            = "HARNESS_AUTH_TOKEN";
    public static final String AUTH_JWT_SECRET       = "HARNESS_AUTH_JWT_SECRET";
    public static final String AUTH_JWT_ISSUER       = "HARNESS_AUTH_JWT_ISSUER";
    /** JWT 滑动窗口刷新：剩余有效期小于此阈值时刷新 token（分钟），默认 60 */
    public static final String AUTH_JWT_REFRESH_THRESHOLD_MINUTES = "HARNESS_AUTH_JWT_REFRESH_THRESHOLD_MINUTES";

    // ==================== RAG (通用 — PG/Milvus 共用) ====================
    /** 向量存储 provider：pgvector | milvus | none，默认 pgvector */
    public static final String RAG_PROVIDER          = "HARNESS_RAG_PROVIDER";
    /** 连接地址（PG: jdbc:postgresql://...; Milvus: http://...） */
    public static final String RAG_URL               = "HARNESS_RAG_URL";
    /** API Key（Milvus token 认证用，PG 不需要） */
    public static final String RAG_API_KEY           = "HARNESS_RAG_API_KEY";
    /** 集合/表名过滤，默认 default */
    public static final String RAG_COLLECTION        = "HARNESS_RAG_COLLECTION";
    /** Milvus 数据库名，默认 default（PG 不使用） */
    public static final String RAG_DATABASE          = "HARNESS_RAG_DATABASE";
    /** 检索返回最大文档数，默认 5 */
    public static final String RAG_TOP_K             = "HARNESS_RAG_TOP_K";
    /** 最低相似度阈值，默认 0.7 */
    public static final String RAG_SCORE_THRESHOLD   = "HARNESS_RAG_SCORE_THRESHOLD";
    /** 允许触发一次隐式查询改写的最低候选分，默认 0.3 */
    public static final String RAG_REWRITE_MIN_SCORE = "HARNESS_RAG_REWRITE_MIN_SCORE";
    /** 数据库用户名（PG 用，Milvus 不需要） */
    public static final String RAG_USER              = "HARNESS_RAG_USER";
    /** 数据库密码（PG 用，Milvus 不需要） */
    public static final String RAG_PASS              = "HARNESS_RAG_PASS";
    /** BM25/全文检索在混合检索中的权重（0.0-1.0），默认 0.3 */
    public static final String RAG_BM25_WEIGHT       = "HARNESS_RAG_BM25_WEIGHT";


    /** 全文检索语言配置，默认 english，中文场景用 simple */
    public static final String RAG_LANG              = "HARNESS_RAG_LANG";

    // ==================== RAG (PG 专用 — 已废弃，保留兼容) ====================
    /**
     * @deprecated Use {@link #RAG_URL} instead.
     * PG JDBC URL，如 jdbc:postgresql://localhost:5432/agent
     */
    @Deprecated public static final String RAG_PG_URL       = "HARNESS_RAG_PG_URL";
    /**
     * @deprecated Use {@link #RAG_USER} instead.
     * PG 数据库用户名
     */
    @Deprecated public static final String RAG_PG_USER      = "HARNESS_RAG_PG_USER";
    /**
     * @deprecated Use {@link #RAG_PASS} instead.
     * PG 数据库密码
     */
    @Deprecated public static final String RAG_PG_PASS      = "HARNESS_RAG_PG_PASS";
    /**
     * @deprecated Use {@link #RAG_COLLECTION} instead.
     * PG 表名，默认 knowledge_documents
     */
    @Deprecated public static final String RAG_PG_TABLE     = "HARNESS_RAG_PG_TABLE";
    /**
     * @deprecated Use {@link #MODEL_EMBEDDING_DIM} instead.
     * PG 向量维度
     */
    @Deprecated public static final String RAG_PG_EMBED_DIM = "HARNESS_RAG_PG_EMBED_DIM";

    // ==================== RAG (Milvus 专用) ====================
    /** Milvus 距离度量类型：COSINE | L2 | IP，默认 COSINE */
    public static final String RAG_MILVUS_METRIC_TYPE = "HARNESS_RAG_MILVUS_METRIC_TYPE";

    // ==================== RAG (语义回溯) ====================
    /** 截断 chunk 时最多向前查找的 chunk 数，默认 2 */
    public static final String RAG_CONTEXT_LOOKBACK_MAX    = "HARNESS_RAG_CONTEXT_LOOKBACK_MAX";

    // ==================== RAG (多路召回 — 已废弃，检索策略由 Provider 内聚) ====================
    /** @deprecated 多路召回已废弃，全文检索和混合检索现在由 VectorStore provider 内部处理 */
    @Deprecated public static final String RAG_MULTI_ROUTE             = "HARNESS_RAG_MULTI_ROUTE";
    /** @deprecated 全文检索已内聚到 PgVectorStore.searchKeyword() */
    @Deprecated public static final String RAG_FULLTEXT_ENABLED        = "HARNESS_RAG_FULLTEXT_ENABLED";
    /** @deprecated 全文检索语言配置已内聚到 PgVectorStore */
    @Deprecated public static final String RAG_FULLTEXT_LANG           = "HARNESS_RAG_FULLTEXT_LANG";
    /** @deprecated Use {@link #GRAPH_PROVIDER}; retained for migration diagnostics only. */
    @Deprecated public static final String RAG_KNOWLEDGE_GRAPH_ENABLED = "HARNESS_RAG_KNOWLEDGE_GRAPH_ENABLED";

    // ==================== Knowledge Graph ====================
    /** 图存储 Provider：none | neo4j，默认 none */
    public static final String GRAPH_PROVIDER = "HARNESS_GRAPH_PROVIDER";
    public static final String GRAPH_NEO4J_URI = "HARNESS_GRAPH_NEO4J_URI";
    public static final String GRAPH_NEO4J_USER = "HARNESS_GRAPH_NEO4J_USER";
    public static final String GRAPH_NEO4J_PASSWORD = "HARNESS_GRAPH_NEO4J_PASSWORD";
    public static final String GRAPH_NEO4J_DATABASE = "HARNESS_GRAPH_NEO4J_DATABASE";
    public static final String GRAPH_CONNECT_TIMEOUT_SECONDS = "HARNESS_GRAPH_CONNECT_TIMEOUT_SECONDS";
    public static final String GRAPH_QUERY_TIMEOUT_SECONDS = "HARNESS_GRAPH_QUERY_TIMEOUT_SECONDS";
    public static final String GRAPH_MAX_CONNECTION_POOL_SIZE = "HARNESS_GRAPH_MAX_CONNECTION_POOL_SIZE";
    public static final String GRAPH_QUERY_DEFAULT_LIMIT = "HARNESS_GRAPH_QUERY_DEFAULT_LIMIT";
    public static final String GRAPH_QUERY_MAX_LIMIT = "HARNESS_GRAPH_QUERY_MAX_LIMIT";
    public static final String GRAPH_QUERY_DEFAULT_MAX_DEPTH = "HARNESS_GRAPH_QUERY_DEFAULT_MAX_DEPTH";
    public static final String GRAPH_QUERY_MAX_DEPTH = "HARNESS_GRAPH_QUERY_MAX_DEPTH";
    public static final String GRAPH_CONTEXT_MAX_ITEMS = "HARNESS_GRAPH_CONTEXT_MAX_ITEMS";
    public static final String GRAPH_CONTEXT_MAX_CHARS = "HARNESS_GRAPH_CONTEXT_MAX_CHARS";
    public static final String GRAPH_SCHEMA_DIR = "HARNESS_GRAPH_SCHEMA_DIR";

    // ==================== MCP ====================
    public static final String MCP_CONNECT_TIMEOUT   = "HARNESS_MCP_CONNECT_TIMEOUT_MS";
    public static final String MCP_CALL_TIMEOUT      = "HARNESS_MCP_CALL_TIMEOUT_MS";
    public static final String MCP_CONFIG_FILE       = "HARNESS_MCP_CONFIG_FILE";

    // ==================== Built-in Tools ====================
    public static final String TOOL_WEB_SEARCH_ENABLED        = "HARNESS_TOOL_WEB_SEARCH_ENABLED";
    /** SearXNG 实例地址，默认 http://localhost:8888 */
    public static final String TOOL_WEB_SEARCH_SEARXNG_URL   = "HARNESS_TOOL_WEB_SEARCH_SEARXNG_URL";
    public static final String TOOL_URL_READER_ENABLED       = "HARNESS_TOOL_URL_READER_ENABLED";
    public static final String TOOL_URL_READER_MAX_BYTES     = "HARNESS_TOOL_URL_READER_MAX_BYTES";
    public static final String TOOL_URL_READER_PAGE_CHARS    = "HARNESS_TOOL_URL_READER_PAGE_CHARS";
    public static final String TOOL_URL_READER_MAX_PAGE_CHARS =
            "HARNESS_TOOL_URL_READER_MAX_PAGE_CHARS";
    public static final String TOOL_URL_READER_TIMEOUT_SECONDS =
            "HARNESS_TOOL_URL_READER_TIMEOUT_SECONDS";
    public static final String TOOL_URL_READER_ALLOW_PRIVATE_NETWORKS =
            "HARNESS_TOOL_URL_READER_ALLOW_PRIVATE_NETWORKS";
    public static final String TOOL_BROWSER_ENABLED          = "HARNESS_TOOL_BROWSER_ENABLED";
    public static final String TOOL_BROWSER_WORKER_URL       = "HARNESS_TOOL_BROWSER_WORKER_URL";
    public static final String TOOL_BROWSER_WORKER_TOKEN     = "HARNESS_TOOL_BROWSER_WORKER_TOKEN";
    public static final String TOOL_BROWSER_TIMEOUT_SECONDS  =
            "HARNESS_TOOL_BROWSER_TIMEOUT_SECONDS";
    public static final String TOOL_BROWSER_ALLOW_PRIVATE_NETWORKS =
            "HARNESS_TOOL_BROWSER_ALLOW_PRIVATE_NETWORKS";
    public static final String TOOL_BROWSER_MAX_SESSIONS     =
            "HARNESS_TOOL_BROWSER_MAX_SESSIONS";
    public static final String TOOL_BROWSER_SESSION_TTL_SECONDS =
            "HARNESS_TOOL_BROWSER_SESSION_TTL_SECONDS";
    public static final String TOOL_FFMPEG_ENABLED       = "HARNESS_TOOL_FFMPEG_ENABLED";
    public static final String TOOL_FFMPEG_PATH          = "HARNESS_TOOL_FFMPEG_PATH";
    /** 工具返回结果数量上限（code_glob/code_grep 等），默认 100 */
    public static final String TOOL_MAX_RESULTS         = "HARNESS_TOOL_MAX_RESULTS";

    // ==================== ReAct ====================
    /** ReAct 循环最大迭代次数，默认 10 */
    public static final String REACT_MAX_ITERATIONS      = "HARNESS_REACT_MAX_ITERATIONS";
    public static final String REACT_STRATEGY            = "HARNESS_REACT_STRATEGY";
    /** 自适应反思触发阈值（连续 N 次非 PASS 结果触发反思），默认 5 */
    public static final String REACT_REFLECTION_THRESHOLD = "HARNESS_REACT_REFLECTION_THRESHOLD";

    // ==================== Sub-Agent ====================
    /** 每个编排器最大并发子代理任务数，默认 3 */
    public static final String AGENT_MAX_SUBAGENTS       = "HARNESS_AGENT_MAX_SUBAGENTS";
    /** 每个运行最大任务数，默认 16 */
    public static final String AGENT_MAX_TASKS_PER_RUN   = "HARNESS_AGENT_MAX_TASKS_PER_RUN";
    /** Scope TTL（分钟），默认 30 */
    public static final String AGENT_SCOPE_TTL_MINUTES   = "HARNESS_AGENT_SCOPE_TTL_MINUTES";
    /** await_subagents 共享超时（秒），默认 120；超时后未完成任务转为 Session Resume */
    public static final String AGENT_AWAIT_TIMEOUT_SECONDS = "HARNESS_AGENT_AWAIT_TIMEOUT_SECONDS";

    // ==================== LLM API 并发保护 ====================
    /** LLM API 最大并发调用数（Semaphore 保护上游 RPM/TPM 限制），默认 10 */
    public static final String MODEL_API_MAX_CONCURRENT  = "HARNESS_MODEL_API_MAX_CONCURRENT";

    // ==================== Storage（统一存储配置，记忆 + Trace 共享） ====================
    /** 存储类型：mysql | sqlite | none（默认）。同时控制记忆和 Trace 存储后端 */
    public static final String AUDIT_STORE           = "HARNESS_AUDIT_STORE";
    public static final String AUDIT_DB_URL          = "HARNESS_AUDIT_DB_URL";
    public static final String AUDIT_DB_USER         = "HARNESS_AUDIT_DB_USER";
    public static final String AUDIT_DB_PASS         = "HARNESS_AUDIT_DB_PASS";
    /** 审计 trace 保留天数，超期自动清理，设为 0 禁用，默认 30 */
    public static final String AUDIT_RETENTION_DAYS  = "HARNESS_AUDIT_RETENTION_DAYS";

    // ==================== Risk ====================
    public static final String RISK_CONFIRM_TOOLS    = "HARNESS_RISK_CONFIRM_TOOLS";
    public static final String RISK_CONFIRMATION_TIMEOUT_SECONDS =
            "HARNESS_RISK_CONFIRMATION_TIMEOUT_SECONDS";
    public static final String RISK_MAX_FILE_SIZE    = "HARNESS_RISK_MAX_FILE_SIZE_MB";
    public static final String RISK_BLOCKED_DOMAINS  = "HARNESS_RISK_BLOCKED_DOMAINS";

    // ==================== Server ====================
    public static final String SERVER_ENABLED        = "HARNESS_SERVER_ENABLED";
    public static final String SERVER_HOST           = "HARNESS_SERVER_HOST";
    public static final String SERVER_PORT           = "HARNESS_SERVER_PORT";
    public static final String SERVER_IDLE_TIMEOUT   = "HARNESS_SERVER_IDLE_TIMEOUT";
    /** Jetty 线程池大小，默认 availableProcessors * 2（最少 8） */
    public static final String SERVER_WORKERS        = "HARNESS_SERVER_WORKERS";
    /** HTTP 请求体最大尺寸（MB），默认 20 */
    public static final String SERVER_MAX_REQUEST_SIZE_MB = "HARNESS_SERVER_MAX_REQUEST_SIZE_MB";

    // ==================== Multimodal ====================
    public static final String MULTIMODAL_IMAGE_ENABLED   = "HARNESS_MULTIMODAL_IMAGE_ENABLED";
    public static final String MULTIMODAL_VIDEO_ENABLED   = "HARNESS_MULTIMODAL_VIDEO_ENABLED";
    public static final String MULTIMODAL_FILE_MAX_SIZE   = "HARNESS_MULTIMODAL_FILE_MAX_SIZE_MB";
    /** 阻止 URL 附件指向私有/内网 IP（SSRF 防护），默认 true */
    public static final String MULTIMODAL_URL_BLOCK_PRIVATE_IPS = "HARNESS_MULTIMODAL_URL_BLOCK_PRIVATE_IPS";

    // ==================== Input (File Parsing) ====================
    /** 文件大小阈值 (KB)，超过此值触发大文件解析，默认 100 */
    public static final String INPUT_FILE_SIZE_THRESHOLD_KB     = "HARNESS_INPUT_FILE_SIZE_THRESHOLD_KB";
    /** 分块目标 token 数，默认 1024 */
    public static final String INPUT_CHUNK_TOKEN_SIZE           = "HARNESS_INPUT_CHUNK_TOKEN_SIZE";
    /** 模型上下文窗口大小 (token)，根据模型名称自动检测，仅在需要覆盖时设置 */
    public static final String MODEL_CHAT_CONTEXT_WINDOW       = "HARNESS_MODEL_CHAT_CONTEXT_WINDOW";
    /** 每个摘要块使用的上下文窗口比例，默认 0.4 */
    public static final String LARGE_FILE_CONTEXT_RATIO        = "HARNESS_LARGE_FILE_CONTEXT_RATIO";
    /** 大文件解析的最大并行摘要线程数，默认 3 */
    public static final String LARGE_FILE_SUMMARY_CONCURRENCY  = "HARNESS_LARGE_FILE_SUMMARY_CONCURRENCY";

    // ==================== AI Fallback ====================
    /** 手动声明模型多模态能力（覆盖自动检测），逗号分隔: text, image_input, audio_input */
    public static final String MODEL_CHAT_CAPABILITIES  = "HARNESS_MODEL_CHAT_CAPABILITIES";

    // ==================== Memory (会话记忆管理) ====================
    /** 会话超时时间（分钟），默认 30 */
    public static final String SESSION_TIMEOUT_MINUTES         = "HARNESS_SESSION_TIMEOUT_MINUTES";
    /** 触发长期记忆提炼的最少消息数，默认 5 */
    public static final String MEMORY_MIN_MESSAGES             = "HARNESS_MEMORY_MIN_MESSAGES";
    /** 触发长期记忆提炼的最少用户字符数，默认 100 */
    public static final String MEMORY_MIN_USER_CHARS           = "HARNESS_MEMORY_MIN_USER_CHARS";
    /** 长期记忆（用户偏好）注入 system prompt 的最大 token 数，默认 800 */
    public static final String MEMORY_LONGTERM_MAX_TOKENS      = "HARNESS_MEMORY_LONGTERM_MAX_TOKENS";
    /** 大压缩触发阈值（总上下文 > 此百分比时触发），默认 85 */
    public static final String CTX_COMPRESS_MAJOR              = "HARNESS_CTX_COMPRESS_MAJOR";
    /** 大压缩目标百分比，默认 30 */
    public static final String CTX_COMPRESS_MAJOR_TARGET        = "HARNESS_CTX_COMPRESS_MAJOR_TARGET";
    /** 基础系统提示词，留空则使用默认值 */
    public static final String SYSTEM_PROMPT                   = "HARNESS_SYSTEM_PROMPT";
    /** 触发长期记忆提炼的最低 session 质量分数（0-100），默认 30 */
    public static final String MEMORY_REFINEMENT_MIN_SCORE     = "HARNESS_MEMORY_REFINEMENT_MIN_SCORE";
    /** 会话清理扫描间隔（分钟），默认 60 */
    public static final String MEMORY_CLEANUP_INTERVAL_MINUTES = "HARNESS_MEMORY_CLEANUP_INTERVAL_MINUTES";
    /** refinement 卡住检测阈值（分钟），默认 10 */
    public static final String MEMORY_REFINEMENT_STUCK_MINUTES = "HARNESS_MEMORY_REFINEMENT_STUCK_MINUTES";

    // ==================== Cache (会话缓存管理) ====================
    /** 缓存 session 过期时间(小时)，空闲超时自动淘汰，默认 12 */
    public static final String CACHE_SESSION_TTL_HOURS        = "HARNESS_CACHE_SESSION_TTL_HOURS";
    /** 单用户最大会话数，超出淘汰该用户最旧会话，默认 10 */
    public static final String CACHE_MAX_SESSIONS_PER_USER    = "HARNESS_CACHE_MAX_SESSIONS_PER_USER";
    /** 单用户缓存内存上限(MB)，超出淘汰该用户最旧会话，默认 2 */
    public static final String CACHE_MAX_MB_PER_USER          = "HARNESS_CACHE_MAX_MB_PER_USER";
    /** 全局缓存内存上限(MB)，默认 4096 */
    public static final String CACHE_MAX_MB_GLOBAL            = "HARNESS_CACHE_MAX_MB_GLOBAL";
    /** 全局淘汰目标比例(1-100)，内存降到此百分比以下停止淘汰，默认 50 */
    public static final String CACHE_EVICTION_TARGET_RATIO    = "HARNESS_CACHE_EVICTION_TARGET_RATIO";

    // ==================== Cache (Redis 分布式缓存) ====================
    /** Redis 连接 URL，设置后启用 Redis 替换内存缓存 */
    public static final String MEMORY_REDIS_URL                = "HARNESS_MEMORY_REDIS_URL";
    public static final String MEMORY_REDIS_PASSWORD           = "HARNESS_MEMORY_REDIS_PASSWORD";
    /** Redis DB 编号，默认 0 */
    public static final String MEMORY_REDIS_DB                 = "HARNESS_MEMORY_REDIS_DB";
    /** Redis key 前缀，默认 harness */
    public static final String MEMORY_REDIS_KEY_PREFIX         = "HARNESS_MEMORY_REDIS_KEY_PREFIX";
    /** Redis TTL（分钟），默认 720 */
    public static final String MEMORY_REDIS_TTL_MINUTES        = "HARNESS_MEMORY_REDIS_TTL_MINUTES";

    // ==================== Knowledge Base ====================
    /** 知识库文件上传目录 */
    public static final String KNOWLEDGE_UPLOAD_DIR          = "HARNESS_KNOWLEDGE_UPLOAD_DIR";
    /** 上传文件最大大小（MB），默认 50 */
    public static final String KNOWLEDGE_MAX_FILE_SIZE_MB    = "HARNESS_KNOWLEDGE_MAX_FILE_SIZE_MB";
    /** 文本分块大小，默认 1000 */
    public static final String KNOWLEDGE_CHUNK_SIZE          = "HARNESS_KNOWLEDGE_CHUNK_SIZE";
    public static final String KNOWLEDGE_PDF_ENABLED         = "HARNESS_KNOWLEDGE_PDF_ENABLED";
    public static final String KNOWLEDGE_DOCX_ENABLED        = "HARNESS_KNOWLEDGE_DOCX_ENABLED";
    public static final String KNOWLEDGE_XLSX_ENABLED        = "HARNESS_KNOWLEDGE_XLSX_ENABLED";
    public static final String KNOWLEDGE_PPTX_ENABLED        = "HARNESS_KNOWLEDGE_PPTX_ENABLED";
    public static final String KNOWLEDGE_CORRUPTION_THRESHOLD = "HARNESS_KNOWLEDGE_CORRUPTION_THRESHOLD";
    public static final String KNOWLEDGE_VISUAL_RENDER_DPI   = "HARNESS_KNOWLEDGE_VISUAL_RENDER_DPI";
    public static final String KNOWLEDGE_VISUAL_PPT_SCALE    = "HARNESS_KNOWLEDGE_VISUAL_PPT_SCALE";
    public static final String KNOWLEDGE_VISUAL_REPAIR_CACHE_MAX_ENTRIES =
            "HARNESS_KNOWLEDGE_VISUAL_REPAIR_CACHE_MAX_ENTRIES";

    // ==================== Artifact (文件生成产物) ====================
    /** 产物存储目录，默认 ./artifacts */
    public static final String ARTIFACT_DIR              = "HARNESS_ARTIFACT_DIR";
    /** 单文件最大大小（MB），默认 100 */
    public static final String ARTIFACT_MAX_SIZE_MB      = "HARNESS_ARTIFACT_MAX_SIZE_MB";
    /** 沙盒 Docker 镜像名，默认 cyrene-sandbox */
    public static final String SANDBOX_DOCKER_IMAGE      = "HARNESS_SANDBOX_DOCKER_IMAGE";
    /** 沙盒执行超时（秒），默认 120 */
    public static final String SANDBOX_TIMEOUT_SECONDS   = "HARNESS_SANDBOX_TIMEOUT_SECONDS";
    /** 沙盒内存限制（MB），默认 512 */
    public static final String SANDBOX_MEMORY_MB         = "HARNESS_SANDBOX_MEMORY_MB";
    /** 同时运行的沙盒容器数上限（信号量），默认 3 */
    public static final String SANDBOX_MAX_CONCURRENT    = "HARNESS_SANDBOX_MAX_CONCURRENT";

    // ==================== Skill ====================
    /** Skill 文件目录，默认 ./skills */
    public static final String SKILL_DIR = "HARNESS_SKILL_DIR";

    // ==================== Project Discovery ====================
    /** 功能总开关（true 时 code_glob/code_grep/read_class_hierarchy 工具对正常会话可用），默认 true */
    public static final String PROJECT_DISCOVERY_ENABLED        = "HARNESS_PROJECT_DISCOVERY_ENABLED";
    /** 声明式配置文件路径，默认 ./project-apis.json */
    public static final String PROJECT_APIS_CONFIG_FILE         = "HARNESS_PROJECT_APIS_CONFIG_FILE";
}
