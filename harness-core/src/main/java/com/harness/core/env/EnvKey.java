package com.harness.core.env;

/**
 * All environment variable keys used by Harness Agent.
 * Convention: HARNESS_&lt;MODULE&gt;_&lt;PARAMETER&gt;
 *
 * <p>变量命名规范：
 * <ul>
 *   <li>通用变量：HARNESS_RAG_*</li>
 *   <li>Milvus 专用变量：HARNESS_RAG_MILVUS_*</li>
 * </ul>
 */
public final class EnvKey {

    private EnvKey() {}

    /** 独立模型配置文件路径；文件内容不参与环境变量覆盖。 */
    public static final String CONFIG_MODEL_FILE = "HARNESS_CONFIG_MODEL_FILE";

    // ==================== Gap Analysis / 动态路由 ====================
    /**
     * 功能总开关，默认 true。关闭后所有字段回退全局静态配置。
     * <p>显式覆盖优先，未指定字段由专用 JEV 路由模型判定；未配置路由模型时使用规则引擎。
     * 三个独立维度：thinkingLevel / needsKnowledgeBase / needsWebSearch。
     * 判定结果写入 trace metadata（gap_source = explicit/rule/jev/default）。
     */
    public static final String GAP_ANALYSIS_ENABLED = "HARNESS_GAP_ANALYSIS_ENABLED";

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

    // ==================== RAG (通用) ====================
    /** 向量存储 provider：milvus | none，默认 milvus */
    public static final String RAG_PROVIDER          = "HARNESS_RAG_PROVIDER";
    /** 连接地址（Milvus: http://...） */
    public static final String RAG_URL               = "HARNESS_RAG_URL";
    /** API Key（Milvus token 认证用） */
    public static final String RAG_API_KEY           = "HARNESS_RAG_API_KEY";
    /** 集合过滤，默认 default */
    public static final String RAG_COLLECTION        = "HARNESS_RAG_COLLECTION";
    /** Milvus 数据库名，默认 default */
    public static final String RAG_DATABASE          = "HARNESS_RAG_DATABASE";
    /** 检索返回最大文档数，默认 5 */
    public static final String RAG_TOP_K             = "HARNESS_RAG_TOP_K";
    /** 最低相似度阈值，默认 0.7 */
    public static final String RAG_SCORE_THRESHOLD   = "HARNESS_RAG_SCORE_THRESHOLD";
    /** BM25/全文检索在混合检索中的权重（0.0-1.0），默认 0.3 */
    public static final String RAG_BM25_WEIGHT       = "HARNESS_RAG_BM25_WEIGHT";


    // ==================== RAG (显式上下文窗口) ====================
    /** readContext 的 before/after 单侧最大 chunk 数，默认 10 */
    public static final String RAG_CONTEXT_WINDOW_MAX      = "HARNESS_RAG_CONTEXT_WINDOW_MAX";

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
    public static final String SEARCH_ENABLED                 = "HARNESS_SEARCH_ENABLED";
    public static final String SEARCH_STRATEGY                = "HARNESS_SEARCH_STRATEGY";
    public static final String SEARCH_PROVIDERS               = "HARNESS_SEARCH_PROVIDERS";
    public static final String SEARCH_SEARXNG_URL             = "HARNESS_SEARCH_SEARXNG_URL";
    public static final String SEARCH_BRAVE_URL               = "HARNESS_SEARCH_BRAVE_URL";
    public static final String SEARCH_BRAVE_API_KEY           = "HARNESS_SEARCH_BRAVE_API_KEY";
    public static final String SEARCH_EXA_URL                 = "HARNESS_SEARCH_EXA_URL";
    public static final String SEARCH_EXA_API_KEY             = "HARNESS_SEARCH_EXA_API_KEY";
    public static final String SEARCH_TIMEOUT_SECONDS         = "HARNESS_SEARCH_TIMEOUT_SECONDS";
    public static final String SEARCH_RESULT_LIMIT            = "HARNESS_SEARCH_RESULT_LIMIT";
    public static final String SEARCH_BLOCKED_DOMAINS         = "HARNESS_SEARCH_BLOCKED_DOMAINS";
    /** Legacy web-search keys remain supported for existing deployments. */
    public static final String TOOL_WEB_SEARCH_ENABLED        = "HARNESS_TOOL_WEB_SEARCH_ENABLED";
    /** SearXNG 实例地址，默认 http://localhost:8888 */
    public static final String TOOL_WEB_SEARCH_SEARXNG_URL   = "HARNESS_TOOL_WEB_SEARCH_SEARXNG_URL";
    /** SearXNG 引擎清单；web_search 会随每次请求传给服务端 */
    public static final String TOOL_WEB_SEARCH_ENGINES       = "HARNESS_TOOL_WEB_SEARCH_ENGINES";
    /** web_search 最多返回的去重结果数，默认 8 */
    public static final String TOOL_WEB_SEARCH_RESULT_LIMIT  = "HARNESS_TOOL_WEB_SEARCH_RESULT_LIMIT";
    /** web_search 默认搜索语言（如 zh-CN / en），随请求发给 SearXNG 并作为 Accept-Language 头；留空/all/auto = 不限语言（中英混合）。工具参数 language 优先于该默认值 */
    public static final String TOOL_WEB_SEARCH_LANGUAGE       = "HARNESS_TOOL_WEB_SEARCH_LANGUAGE";
    /** web_search 域名黑名单（逗号分隔主域名后缀，如 pinterest.com,quora.com），命中的结果直接丢弃；留空不过滤 */
    public static final String TOOL_WEB_SEARCH_BLOCKED_DOMAINS = "HARNESS_TOOL_WEB_SEARCH_BLOCKED_DOMAINS";
    /** 结果至少被 N 个引擎同时命中（多引擎背书）才保留，默认 1 = 不过滤 */
    public static final String TOOL_WEB_SEARCH_MIN_ENGINES    = "HARNESS_TOOL_WEB_SEARCH_MIN_ENGINES";
    /** 结果最低 SearXNG score 阈值，默认 0 = 不过滤 */
    public static final String TOOL_WEB_SEARCH_MIN_SCORE      = "HARNESS_TOOL_WEB_SEARCH_MIN_SCORE";
    public static final String TOOL_URL_READER_ENABLED       = "HARNESS_TOOL_URL_READER_ENABLED";
    public static final String TOOL_URL_READER_MAX_BYTES     = "HARNESS_TOOL_URL_READER_MAX_BYTES";
    public static final String TOOL_URL_READER_PAGE_CHARS    = "HARNESS_TOOL_URL_READER_PAGE_CHARS";
    public static final String TOOL_URL_READER_MAX_PAGE_CHARS =
            "HARNESS_TOOL_URL_READER_MAX_PAGE_CHARS";
    public static final String TOOL_URL_READER_TIMEOUT_SECONDS =
            "HARNESS_TOOL_URL_READER_TIMEOUT_SECONDS";
    public static final String TOOL_URL_READER_ALLOW_PRIVATE_NETWORKS =
            "HARNESS_TOOL_URL_READER_ALLOW_PRIVATE_NETWORKS";
    /**
     * 默认 UA 里的 Chrome 主版本号；Chrome 稳定版更新后改这里即可，不必动代码。
     */
    public static final String TOOL_URL_READER_CHROME_VERSION =
            "HARNESS_TOOL_URL_READER_CHROME_VERSION";
    /**
     * 整体覆盖 User-Agent；设置后 Chrome 版本号不再生效。
     * 用于标明机器人身份（合规要求或希望被站点识别）。
     */
    public static final String TOOL_URL_READER_USER_AGENT =
            "HARNESS_TOOL_URL_READER_USER_AGENT";
    public static final String TOOL_BROWSER_ENABLED          = "HARNESS_TOOL_BROWSER_ENABLED";
    public static final String TOOL_BROWSER_WORKER_URL       = "HARNESS_TOOL_BROWSER_WORKER_URL";
    public static final String TOOL_BROWSER_WORKER_TOKEN     = "HARNESS_TOOL_BROWSER_WORKER_TOKEN";
    public static final String TOOL_BROWSER_TIMEOUT_SECONDS  =
            "HARNESS_TOOL_BROWSER_TIMEOUT_SECONDS";
    public static final String TOOL_BROWSER_ALLOW_PRIVATE_NETWORKS =
            "HARNESS_TOOL_BROWSER_ALLOW_PRIVATE_NETWORKS";
    public static final String TOOL_FFMPEG_ENABLED       = "HARNESS_TOOL_FFMPEG_ENABLED";
    public static final String TOOL_FFMPEG_PATH          = "HARNESS_TOOL_FFMPEG_PATH";
    /** 工具返回结果数量上限（glob/grep 等），默认 100 */
    public static final String TOOL_MAX_RESULTS         = "HARNESS_TOOL_MAX_RESULTS";

    // ==================== ReAct ====================
    /** ReAct 循环最大迭代次数，默认 10 */
    public static final String REACT_MAX_ITERATIONS      = "HARNESS_REACT_MAX_ITERATIONS";
    /**
     * 单工具允许的反思次数：每次失败注入一次反思提示，次数用尽后再失败一次即硬停整个 run。
     * 成功一次清零重计。例：3 = 失败 1~3 次各反思一次，第 4 次失败硬停。默认 5。
     */
    public static final String REACT_REFLECTION_THRESHOLD = "HARNESS_REACT_REFLECTION_THRESHOLD";

    // ==================== Structured Output ====================
    public static final String STRUCTURED_SCHEMA_MAX_BYTES =
            "HARNESS_STRUCTURED_SCHEMA_MAX_BYTES";
    public static final String STRUCTURED_SCHEMA_MAX_DEPTH =
            "HARNESS_STRUCTURED_SCHEMA_MAX_DEPTH";
    public static final String STRUCTURED_SCHEMA_MAX_PROPERTIES =
            "HARNESS_STRUCTURED_SCHEMA_MAX_PROPERTIES";
    public static final String STRUCTURED_SCHEMA_MAX_ENUM_VALUES =
            "HARNESS_STRUCTURED_SCHEMA_MAX_ENUM_VALUES";

    // ==================== Sub-Agent ====================
    /** 每个编排器最大并发子代理任务数，默认 3 */
    public static final String AGENT_MAX_SUBAGENTS       = "HARNESS_AGENT_MAX_SUBAGENTS";
    /** 每个运行最大任务数，默认 16 */
    public static final String AGENT_MAX_TASKS_PER_RUN   = "HARNESS_AGENT_MAX_TASKS_PER_RUN";
    /** Scope TTL（分钟），默认 30 */
    public static final String AGENT_SCOPE_TTL_MINUTES   = "HARNESS_AGENT_SCOPE_TTL_MINUTES";
    /** await_subagents 共享超时（秒），默认 120；超时后未完成任务转为 Session Resume */
    public static final String AGENT_AWAIT_TIMEOUT_SECONDS = "HARNESS_AGENT_AWAIT_TIMEOUT_SECONDS";

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
    public static final String RISK_BLOCKED_DOMAINS  = "HARNESS_RISK_BLOCKED_DOMAINS";

    // ==================== Server ====================
    public static final String SERVER_ENABLED        = "HARNESS_SERVER_ENABLED";
    public static final String SERVER_HOST           = "HARNESS_SERVER_HOST";
    public static final String SERVER_PORT           = "HARNESS_SERVER_PORT";
    public static final String SERVER_IDLE_TIMEOUT   = "HARNESS_SERVER_IDLE_TIMEOUT";
    public static final String REALTIME_SESSION_TIMEOUT_SECONDS =
            "HARNESS_REALTIME_SESSION_TIMEOUT_SECONDS";
    /** SSE 心跳间隔（秒）。仅用于向客户端证明连接存活，与模型/工具的执行业务超时无关，默认 15 */
    public static final String SSE_KEEPALIVE_SECONDS = "HARNESS_SSE_KEEPALIVE_SECONDS";
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
    /** 分块目标 token 数，默认 1024 */
    public static final String INPUT_CHUNK_TOKEN_SIZE           = "HARNESS_INPUT_CHUNK_TOKEN_SIZE";
    /** MarkItDown document parser internal service URL. */
    public static final String DOCUMENT_PARSER_URL              = "HARNESS_DOCUMENT_PARSER_URL";
    /** Optional bearer token shared with the document parser service. */
    public static final String DOCUMENT_PARSER_TOKEN            = "HARNESS_DOCUMENT_PARSER_TOKEN";
    /** Document conversion request timeout in seconds. */
    public static final String DOCUMENT_PARSER_TIMEOUT_SECONDS  = "HARNESS_DOCUMENT_PARSER_TIMEOUT_SECONDS";
    /** Java-to-worker HTTP timeout; keep above the worker conversion timeout. */
    public static final String DOCUMENT_PARSER_REQUEST_TIMEOUT_SECONDS =
            "HARNESS_DOCUMENT_PARSER_REQUEST_TIMEOUT_SECONDS";
    /** Timeout for one vision-model request made by the parser worker. */
    public static final String DOCUMENT_PARSER_VISION_TIMEOUT_SECONDS =
            "HARNESS_DOCUMENT_PARSER_VISION_TIMEOUT_SECONDS";
    /** Maximum document size accepted by the parser service, in MB. */
    public static final String DOCUMENT_PARSER_MAX_FILE_SIZE_MB = "HARNESS_DOCUMENT_PARSER_MAX_FILE_SIZE_MB";
    /** Maximum concurrent document conversions inside the parser worker. */
    public static final String DOCUMENT_PARSER_MAX_CONCURRENT   = "HARNESS_DOCUMENT_PARSER_MAX_CONCURRENT";
    /** Internal listener port used by the document parser container. */
    public static final String DOCUMENT_PARSER_PORT             = "HARNESS_DOCUMENT_PARSER_PORT";
    /** Internal bind host used by the document parser container. */
    public static final String DOCUMENT_PARSER_HOST             = "HARNESS_DOCUMENT_PARSER_HOST";
    /** 独立文件摘要请求使用的主模型上下文比例，默认 0.6 */
    public static final String LARGE_FILE_CONTEXT_RATIO        = "HARNESS_LARGE_FILE_CONTEXT_RATIO";
    /** 大文件解析的最大并行摘要线程数，默认 3 */
    public static final String LARGE_FILE_SUMMARY_CONCURRENCY  = "HARNESS_LARGE_FILE_SUMMARY_CONCURRENCY";

    // ==================== Memory (会话记忆管理) ====================
    /** 长期知识权威存储：mysql | none；与 Trace 存储开关解耦。 */
    public static final String MEMORY_STORE                       = "HARNESS_MEMORY_STORE";
    /** 会话超时时间（分钟），默认 30 */
    public static final String SESSION_TIMEOUT_MINUTES         = "HARNESS_SESSION_TIMEOUT_MINUTES";
    /** 大压缩触发阈值（总上下文 > 此百分比时触发），默认 85 */
    public static final String CTX_COMPRESS_MAJOR              = "HARNESS_CTX_COMPRESS_MAJOR";
    /** 大压缩目标百分比，默认 30 */
    public static final String CTX_COMPRESS_MAJOR_TARGET        = "HARNESS_CTX_COMPRESS_MAJOR_TARGET";
    /** 压缩时保留原文的最近完整 Turn 数，默认 1 */
    public static final String CTX_COMPRESS_KEEP_RECENT_TURNS   = "HARNESS_CTX_COMPRESS_KEEP_RECENT_TURNS";
    /** 基础系统提示词，留空则使用默认值 */
    public static final String SYSTEM_PROMPT                   = "HARNESS_SYSTEM_PROMPT";
    /** 会话清理扫描间隔（分钟），默认 60 */
    public static final String MEMORY_CLEANUP_INTERVAL_MINUTES = "HARNESS_MEMORY_CLEANUP_INTERVAL_MINUTES";
    /** 文档入库 Worker 的分页大小，默认 100。 */
    public static final String KNOWLEDGE_COMPILER_BATCH_SIZE =
            "HARNESS_KNOWLEDGE_COMPILER_BATCH_SIZE";
    /** Knowledge Catalog 的独立物理 Collection。 */
    public static final String KNOWLEDGE_CATALOG_COLLECTION =
            "HARNESS_KNOWLEDGE_CATALOG_COLLECTION";
    /** User Episode 当前 Revision 的独立物理 Collection。 */
    public static final String MEMORY_USER_KNOWLEDGE_COLLECTION =
            "HARNESS_MEMORY_USER_KNOWLEDGE_COLLECTION";
    /** Operation Playbook 当前 Revision 的独立物理 Collection。 */
    public static final String MEMORY_OPERATION_KNOWLEDGE_COLLECTION =
            "HARNESS_MEMORY_OPERATION_KNOWLEDGE_COLLECTION";
    public static final String MEMORY_LONGTERM_BUDGET_RATIO =
            "HARNESS_MEMORY_LONGTERM_BUDGET_RATIO";

    public static final String MEMORY_OKF_EXPORT_ENABLED =
            "HARNESS_MEMORY_OKF_EXPORT_ENABLED";
    public static final String KNOWLEDGE_CATALOG_RETRIEVAL_LANE_TOP_K =
            "HARNESS_KNOWLEDGE_CATALOG_RETRIEVAL_LANE_TOP_K";
    public static final String KNOWLEDGE_CATALOG_RETRIEVAL_FUSED_TOP_K =
            "HARNESS_KNOWLEDGE_CATALOG_RETRIEVAL_FUSED_TOP_K";
    public static final String KNOWLEDGE_CATALOG_RETRIEVAL_DENSE_THRESHOLD =
            "HARNESS_KNOWLEDGE_CATALOG_RETRIEVAL_DENSE_THRESHOLD";
    public static final String KNOWLEDGE_CATALOG_RETRIEVAL_SPARSE_THRESHOLD =
            "HARNESS_KNOWLEDGE_CATALOG_RETRIEVAL_SPARSE_THRESHOLD";
    public static final String KNOWLEDGE_CATALOG_RETRIEVAL_RRF_K =
            "HARNESS_KNOWLEDGE_CATALOG_RETRIEVAL_RRF_K";
    /** 索引 Outbox 每次领取的任务上限，默认 100。 */
    public static final String MEMORY_INDEX_OUTBOX_BATCH_SIZE =
            "HARNESS_MEMORY_INDEX_OUTBOX_BATCH_SIZE";
    /** 索引 Outbox 同时执行的最大 Worker 数，默认 2。 */
    public static final String MEMORY_INDEX_OUTBOX_CONCURRENCY =
            "HARNESS_MEMORY_INDEX_OUTBOX_CONCURRENCY";
    /** 索引 Outbox 固定轮询间隔秒数，默认 5。 */
    public static final String MEMORY_INDEX_OUTBOX_POLL_SECONDS =
            "HARNESS_MEMORY_INDEX_OUTBOX_POLL_SECONDS";
    /** 索引 Outbox 领取超时分钟数，默认 30。 */
    public static final String MEMORY_INDEX_OUTBOX_STUCK_MINUTES =
            "HARNESS_MEMORY_INDEX_OUTBOX_STUCK_MINUTES";
    /** 索引 Outbox 最大尝试次数，默认 5。 */
    public static final String MEMORY_INDEX_OUTBOX_MAX_ATTEMPTS =
            "HARNESS_MEMORY_INDEX_OUTBOX_MAX_ATTEMPTS";

    // ==================== Cache (会话缓存管理) ====================
    /** 缓存 session 空闲过期时间(小时)，滑动刷新，默认 12 */
    public static final String CACHE_SESSION_TTL_HOURS        = "HARNESS_CACHE_SESSION_TTL_HOURS";

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
    /** 单个 Source Document Revision 最大 Chunk 数，默认 10000。 */
    public static final String KNOWLEDGE_SOURCE_REVISION_MAX_CHUNKS =
            "HARNESS_KNOWLEDGE_SOURCE_REVISION_MAX_CHUNKS";
    public static final String KNOWLEDGE_PDF_ENABLED         = "HARNESS_KNOWLEDGE_PDF_ENABLED";
    public static final String KNOWLEDGE_DOCX_ENABLED        = "HARNESS_KNOWLEDGE_DOCX_ENABLED";
    public static final String KNOWLEDGE_XLSX_ENABLED        = "HARNESS_KNOWLEDGE_XLSX_ENABLED";
    public static final String KNOWLEDGE_PPTX_ENABLED        = "HARNESS_KNOWLEDGE_PPTX_ENABLED";
    /** 文档 Ingest Job 领取超时分钟数，默认 30。 */
    public static final String KNOWLEDGE_INGEST_STUCK_MINUTES =
            "HARNESS_KNOWLEDGE_INGEST_STUCK_MINUTES";
    /** 文档 Ingest Job 最大尝试次数，默认 5。 */
    public static final String KNOWLEDGE_INGEST_MAX_ATTEMPTS =
            "HARNESS_KNOWLEDGE_INGEST_MAX_ATTEMPTS";

    public static final String GRAPH_MUTATION_POLL_SECONDS =
            "HARNESS_GRAPH_MUTATION_POLL_SECONDS";

    public static final String GRAPH_MUTATION_STUCK_MINUTES =
            "HARNESS_GRAPH_MUTATION_STUCK_MINUTES";

    public static final String GRAPH_MUTATION_MAX_ATTEMPTS =
            "HARNESS_GRAPH_MUTATION_MAX_ATTEMPTS";
    /** 未登记 Artifact 报告前的保留小时数，默认 24。 */
    public static final String KNOWLEDGE_ARTIFACT_ORPHAN_RETENTION_HOURS =
            "HARNESS_KNOWLEDGE_ARTIFACT_ORPHAN_RETENTION_HOURS";
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
    /** 功能总开关（true 时 read_class_hierarchy 工具对正常会话可用），默认 true */
    public static final String PROJECT_DISCOVERY_ENABLED        = "HARNESS_PROJECT_DISCOVERY_ENABLED";
    /** 声明式配置文件路径，默认 ./project-apis.json */
    public static final String PROJECT_APIS_CONFIG_FILE         = "HARNESS_PROJECT_APIS_CONFIG_FILE";

    // ==================== Code Tools (代码读写) ====================
    /** 功能总开关（true 时 read/glob/grep/edit/write 工具对正常会话可用），默认 true */
    public static final String CODE_TOOLS_ENABLED        = "HARNESS_CODE_TOOLS_ENABLED";
    /** 读取范围：host = 本机任意可读路径；workspace = 仅 agentRoot + backendRoots，默认 host */
    public static final String CODE_READ_SCOPE           = "HARNESS_CODE_READ_SCOPE";
    /** Agent 自身源码目录，同时是可写根之一；缺省为进程工作目录 */
    public static final String CODE_AGENT_ROOT           = "HARNESS_CODE_AGENT_ROOT";
    /** 对接后端源码目录，逗号分隔；只来自服务端配置，缺省为空 */
    public static final String CODE_BACKEND_ROOTS        = "HARNESS_CODE_BACKEND_ROOTS";
    /** 可选加速后端：ripgrep 可执行文件路径。留空（默认）时使用内置 Java NIO 实现 */
    public static final String CODE_RG_PATH              = "HARNESS_CODE_RG_PATH";
    /** 单次代码工具的返回字节上限，默认 262144 */
    public static final String CODE_MAX_OUTPUT_BYTES     = "HARNESS_CODE_MAX_OUTPUT_BYTES";
    /** read 工具单次返回的最大行数，默认 2000 */
    public static final String CODE_READ_MAX_LINES       = "HARNESS_CODE_READ_MAX_LINES";
    /** ripgrep 单次执行超时（秒），默认 30 */
    public static final String CODE_RG_TIMEOUT_SECONDS   = "HARNESS_CODE_RG_TIMEOUT_SECONDS";
    /** edit 工具可处理的单文件上限（MB），默认 10 */
    public static final String CODE_EDIT_MAX_FILE_MB     = "HARNESS_CODE_EDIT_MAX_FILE_MB";

    // ==================== Shell (环境诊断命令执行) ====================
    /** 功能总开关，默认 true；注意默认只放行只读诊断命令，表外一律拒绝 */
    public static final String SHELL_ENABLED           = "HARNESS_SHELL_ENABLED";
    /** 追加到内置 allow 表的规则，逗号分隔，每条是空格分隔的 argv 前缀，如 "docker top,git blame" */
    public static final String SHELL_ALLOW             = "HARNESS_SHELL_ALLOW";
    /** 追加到内置 confirm 表的规则，格式同 SHELL_ALLOW */
    public static final String SHELL_CONFIRM           = "HARNESS_SHELL_CONFIRM";
    /** 单次执行超时（秒），默认 60 */
    public static final String SHELL_TIMEOUT_SECONDS   = "HARNESS_SHELL_TIMEOUT_SECONDS";
    /** 单次返回字节上限（stdout+stderr），默认 32768 (32KB) */
    public static final String SHELL_MAX_OUTPUT_BYTES  = "HARNESS_SHELL_MAX_OUTPUT_BYTES";
    /** docker logs 未指定 --tail 时自动追加的行数，默认 500 */
    public static final String SHELL_LOG_TAIL_LINES    = "HARNESS_SHELL_LOG_TAIL_LINES";
}
