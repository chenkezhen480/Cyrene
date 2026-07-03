package com.harness.env;

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

    // ==================== 1. Chat Model (通用对话+工具调用) ====================
    public static final String MODEL_CHAT_PROVIDER    = "HARNESS_MODEL_CHAT_PROVIDER";
    public static final String MODEL_CHAT_API_KEY     = "HARNESS_MODEL_CHAT_API_KEY";
    public static final String MODEL_CHAT_BASE_URL    = "HARNESS_MODEL_CHAT_BASE_URL";
    public static final String MODEL_CHAT_MODEL       = "HARNESS_MODEL_CHAT_MODEL";
    public static final String MODEL_CHAT_MAX_TOKENS  = "HARNESS_MODEL_CHAT_MAX_TOKENS";
    public static final String MODEL_CHAT_TEMPERATURE = "HARNESS_MODEL_CHAT_TEMPERATURE";
    /** 是否开启思考/推理模式（影响 DashScope 等支持思考模式的 API），默认 true */
    public static final String MODEL_CHAT_THINKING   = "HARNESS_MODEL_CHAT_THINKING";

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

    // ==================== 4. Embedding Model (多模态向量化) ====================
    public static final String MODEL_EMBEDDING_PROVIDER = "HARNESS_MODEL_EMBEDDING_PROVIDER";
    public static final String MODEL_EMBEDDING_API_KEY  = "HARNESS_MODEL_EMBEDDING_API_KEY";
    public static final String MODEL_EMBEDDING_BASE_URL = "HARNESS_MODEL_EMBEDDING_BASE_URL";
    public static final String MODEL_EMBEDDING_MODEL    = "HARNESS_MODEL_EMBEDDING_MODEL";
    public static final String MODEL_EMBEDDING_DIM      = "HARNESS_MODEL_EMBEDDING_DIM";

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
    /** 数据库用户名（PG 用，Milvus 不需要） */
    public static final String RAG_USER              = "HARNESS_RAG_USER";
    /** 数据库密码（PG 用，Milvus 不需要） */
    public static final String RAG_PASS              = "HARNESS_RAG_PASS";
    /** 向量维度，默认 1536 */
    public static final String RAG_EMBED_DIM         = "HARNESS_RAG_EMBED_DIM";
    /** 向量维度默认值 */
    public static final int RAG_EMBED_DIM_DEFAULT    = 1024;
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
     * @deprecated Use {@link #RAG_EMBED_DIM} instead.
     * PG 向量维度
     */
    @Deprecated public static final String RAG_PG_EMBED_DIM = "HARNESS_RAG_PG_EMBED_DIM";

    // ==================== RAG (Milvus 专用) ====================
    /** Milvus 距离度量类型：COSINE | L2 | IP，默认 COSINE */
    public static final String RAG_MILVUS_METRIC_TYPE = "HARNESS_RAG_MILVUS_METRIC_TYPE";

    // ==================== RAG (语义回溯 + 查询改写) ====================
    /** 截断 chunk 时最多向前查找的 chunk 数，默认 2 */
    public static final String RAG_CONTEXT_LOOKBACK_MAX    = "HARNESS_RAG_CONTEXT_LOOKBACK_MAX";
    /** 查询改写策略：none | hyde | multi-query | step-back，默认 none */
    public static final String RAG_QUERY_REWRITE           = "HARNESS_RAG_QUERY_REWRITE";
    /** multi-query 策略的备选查询数（不含原始查询），默认 3 */
    public static final String RAG_QUERY_REWRITE_COUNT     = "HARNESS_RAG_QUERY_REWRITE_COUNT";

    // ==================== RAG (多路召回 — 已废弃，检索策略由 Provider 内聚) ====================
    /** @deprecated 多路召回已废弃，全文检索和混合检索现在由 VectorStore provider 内部处理 */
    @Deprecated public static final String RAG_MULTI_ROUTE             = "HARNESS_RAG_MULTI_ROUTE";
    /** @deprecated 全文检索已内聚到 PgVectorStore.searchKeyword() */
    @Deprecated public static final String RAG_FULLTEXT_ENABLED        = "HARNESS_RAG_FULLTEXT_ENABLED";
    /** @deprecated 全文检索语言配置已内聚到 PgVectorStore */
    @Deprecated public static final String RAG_FULLTEXT_LANG           = "HARNESS_RAG_FULLTEXT_LANG";
    /** 知识图谱检索（预留，暂未实现），默认 false */
    public static final String RAG_KNOWLEDGE_GRAPH_ENABLED = "HARNESS_RAG_KNOWLEDGE_GRAPH_ENABLED";

    // ==================== MCP ====================
    public static final String MCP_SERVERS           = "HARNESS_MCP_SERVERS";
    public static final String MCP_CONNECT_TIMEOUT   = "HARNESS_MCP_CONNECT_TIMEOUT_MS";
    public static final String MCP_CALL_TIMEOUT      = "HARNESS_MCP_CALL_TIMEOUT_MS";
    public static final String MCP_CONFIG_FILE       = "HARNESS_MCP_CONFIG_FILE";

    // ==================== Built-in Tools ====================
    public static final String TOOL_WEB_SEARCH_ENABLED        = "HARNESS_TOOL_WEB_SEARCH_ENABLED";
    public static final String TOOL_WEB_SEARCH_API_KEY        = "HARNESS_TOOL_WEB_SEARCH_API_KEY";
    public static final String TOOL_WEB_SEARCH_ENGINE         = "HARNESS_TOOL_WEB_SEARCH_ENGINE";
    /** 搜索引擎回退链优先级，逗号分隔，默认 tavily,serpapi,duckduckgo */
    public static final String TOOL_WEB_SEARCH_PRIORITY       = "HARNESS_TOOL_WEB_SEARCH_PRIORITY";
    public static final String TOOL_WEB_SEARCH_TAVILY_API_KEY = "HARNESS_TOOL_WEB_SEARCH_TAVILY_API_KEY";
    public static final String TOOL_WEB_SEARCH_SERPAPI_API_KEY = "HARNESS_TOOL_WEB_SEARCH_SERPAPI_API_KEY";
    public static final String TOOL_FFMPEG_ENABLED       = "HARNESS_TOOL_FFMPEG_ENABLED";
    public static final String TOOL_FFMPEG_PATH          = "HARNESS_TOOL_FFMPEG_PATH";

    // ==================== ReAct ====================
    /** ReAct 循环最大迭代次数，默认 10 */
    public static final String REACT_MAX_ITERATIONS      = "HARNESS_REACT_MAX_ITERATIONS";
    public static final String REACT_STRATEGY            = "HARNESS_REACT_STRATEGY";
    /** 工具调用出错时是否立即停止循环，默认 false（让 LLM 自行决策） */
    public static final String REACT_STOP_ON_TOOL_ERROR  = "HARNESS_REACT_STOP_ON_TOOL_ERROR";
    /** 反思间隔轮数（每隔 N 步注入反思消息），默认 3（0=禁用） */
    public static final String REACT_REFLECTION_INTERVAL = "HARNESS_REACT_REFLECTION_INTERVAL";
    /** 循环检测阈值（连续 N 次相同工具调用判定为循环），默认 3（0=禁用） */
    public static final String REACT_LOOP_DETECTION_THRESHOLD = "HARNESS_REACT_LOOP_DETECTION_THRESHOLD";

    // ==================== Sub-Agent ====================
    /** 每个编排器最大并发子代理任务数，默认 3 */
    public static final String AGENT_MAX_SUBAGENTS       = "HARNESS_AGENT_MAX_SUBAGENTS";

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
    public static final String RISK_AUTO_CONFIRM     = "HARNESS_RISK_AUTO_CONFIRM";
    public static final String RISK_CONFIRM_TOOLS    = "HARNESS_RISK_CONFIRM_TOOLS";
    public static final String RISK_MAX_FILE_SIZE    = "HARNESS_RISK_MAX_FILE_SIZE_MB";
    public static final String RISK_BLOCKED_DOMAINS  = "HARNESS_RISK_BLOCKED_DOMAINS";

    // ==================== Server ====================
    public static final String SERVER_ENABLED        = "HARNESS_SERVER_ENABLED";
    public static final String SERVER_HOST           = "HARNESS_SERVER_HOST";
    public static final String SERVER_PORT           = "HARNESS_SERVER_PORT";
    public static final String SERVER_IDLE_TIMEOUT   = "HARNESS_SERVER_IDLE_TIMEOUT";
    /** Jetty 线程池大小，默认 availableProcessors * 2（最少 8） */
    public static final String SERVER_WORKERS        = "HARNESS_SERVER_WORKERS";

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

    // ==================== Skill ====================
    /** Skill 文件目录，默认 ./skills */
    public static final String SKILL_DIR = "HARNESS_SKILL_DIR";

    // ==================== Project Discovery ====================
    /** 功能总开关，默认 true */
    public static final String PROJECT_DISCOVERY_ENABLED        = "HARNESS_PROJECT_DISCOVERY_ENABLED";
    /** 声明式配置文件路径，默认 ./project-apis.json */
    public static final String PROJECT_APIS_CONFIG_FILE         = "HARNESS_PROJECT_APIS_CONFIG_FILE";
    /** 单次发现任务最大工具调用次数，默认 60 */
    public static final String PROJECT_DISCOVERY_MAX_TOOL_CALLS = "HARNESS_PROJECT_DISCOVERY_MAX_TOOL_CALLS";
    /** 单次发现任务超时（分钟），默认 10 */
    public static final String PROJECT_DISCOVERY_TIMEOUT_MINUTES = "HARNESS_PROJECT_DISCOVERY_TIMEOUT_MINUTES";
    /** 追加的敏感文件排除 glob，逗号分隔 */
    public static final String PROJECT_DISCOVERY_EXCLUDE_PATTERNS = "HARNESS_PROJECT_DISCOVERY_EXCLUDE_PATTERNS";
}
