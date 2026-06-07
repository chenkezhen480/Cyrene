package com.harness.env;

/**
 * All environment variable keys used by Harness Agent.
 * Convention: HARNESS_<MODULE>_<PARAMETER>
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

    // ==================== 6. Realtime Model (实时多模态-预留) ====================
    public static final String MODEL_REALTIME_PROVIDER = "HARNESS_MODEL_REALTIME_PROVIDER";
    public static final String MODEL_REALTIME_API_KEY  = "HARNESS_MODEL_REALTIME_API_KEY";
    public static final String MODEL_REALTIME_BASE_URL = "HARNESS_MODEL_REALTIME_BASE_URL";

    // ==================== Auth ====================
    public static final String AUTH_MODE             = "HARNESS_AUTH_MODE";
    public static final String AUTH_TOKEN            = "HARNESS_AUTH_TOKEN";
    public static final String AUTH_JWT_SECRET       = "HARNESS_AUTH_JWT_SECRET";
    public static final String AUTH_JWT_ISSUER       = "HARNESS_AUTH_JWT_ISSUER";
    public static final String AUTH_JWT_REFRESH_THRESHOLD_MINUTES = "HARNESS_AUTH_JWT_REFRESH_THRESHOLD_MINUTES";

    // ==================== RAG ====================
    public static final String RAG_PROVIDER          = "HARNESS_RAG_PROVIDER";
    public static final String RAG_URL               = "HARNESS_RAG_URL";
    public static final String RAG_API_KEY           = "HARNESS_RAG_API_KEY";
    public static final String RAG_COLLECTION        = "HARNESS_RAG_COLLECTION";
    public static final String RAG_TOP_K             = "HARNESS_RAG_TOP_K";
    public static final String RAG_SCORE_THRESHOLD   = "HARNESS_RAG_SCORE_THRESHOLD";
    public static final String RAG_PG_URL            = "HARNESS_RAG_PG_URL";
    public static final String RAG_PG_USER           = "HARNESS_RAG_PG_USER";
    public static final String RAG_PG_PASS           = "HARNESS_RAG_PG_PASS";
    public static final String RAG_PG_TABLE          = "HARNESS_RAG_PG_TABLE";
    public static final String RAG_PG_EMBED_DIM      = "HARNESS_RAG_PG_EMBED_DIM";

    // ==================== Rerank ====================
    public static final String RERANK_ENABLED        = "HARNESS_RERANK_ENABLED";
    public static final String RERANK_PROVIDER       = "HARNESS_RERANK_PROVIDER";
    public static final String RERANK_MODEL          = "HARNESS_RERANK_MODEL";
    public static final String RERANK_TOP_N          = "HARNESS_RERANK_TOP_N";

    // ==================== MCP ====================
    public static final String MCP_SERVERS           = "HARNESS_MCP_SERVERS";
    public static final String MCP_CONNECT_TIMEOUT   = "HARNESS_MCP_CONNECT_TIMEOUT_MS";
    public static final String MCP_CALL_TIMEOUT      = "HARNESS_MCP_CALL_TIMEOUT_MS";
    public static final String MCP_CONFIG_FILE       = "HARNESS_MCP_CONFIG_FILE";

    // ==================== Built-in Tools ====================
    public static final String TOOL_WEB_SEARCH_ENABLED        = "HARNESS_TOOL_WEB_SEARCH_ENABLED";
    public static final String TOOL_WEB_SEARCH_API_KEY        = "HARNESS_TOOL_WEB_SEARCH_API_KEY";
    public static final String TOOL_WEB_SEARCH_ENGINE         = "HARNESS_TOOL_WEB_SEARCH_ENGINE";
    public static final String TOOL_WEB_SEARCH_PRIORITY       = "HARNESS_TOOL_WEB_SEARCH_PRIORITY";
    public static final String TOOL_WEB_SEARCH_TAVILY_API_KEY = "HARNESS_TOOL_WEB_SEARCH_TAVILY_API_KEY";
    public static final String TOOL_WEB_SEARCH_SERPAPI_API_KEY = "HARNESS_TOOL_WEB_SEARCH_SERPAPI_API_KEY";
    public static final String TOOL_FFMPEG_ENABLED       = "HARNESS_TOOL_FFMPEG_ENABLED";
    public static final String TOOL_FFMPEG_PATH          = "HARNESS_TOOL_FFMPEG_PATH";
    public static final String TOOL_CODE_EXEC_ENABLED    = "HARNESS_TOOL_CODE_EXEC_ENABLED";
    public static final String TOOL_CODE_EXEC_SANDBOX    = "HARNESS_TOOL_CODE_EXEC_SANDBOX";

    // ==================== ReAct ====================
    public static final String REACT_MAX_ITERATIONS      = "HARNESS_REACT_MAX_ITERATIONS";
    public static final String REACT_STRATEGY            = "HARNESS_REACT_STRATEGY";
    public static final String REACT_STOP_ON_TOOL_ERROR  = "HARNESS_REACT_STOP_ON_TOOL_ERROR";
    public static final String REACT_ENABLE_REFLECTION   = "HARNESS_REACT_ENABLE_REFLECTION";

    // ==================== Sub-Agent ====================
    public static final String AGENT_MAX_SUBAGENTS       = "HARNESS_AGENT_MAX_SUBAGENTS";

    // ==================== Audit ====================
    public static final String AUDIT_STORE           = "HARNESS_AUDIT_STORE";
    public static final String AUDIT_DB_URL          = "HARNESS_AUDIT_DB_URL";
    public static final String AUDIT_DB_USER         = "HARNESS_AUDIT_DB_USER";
    public static final String AUDIT_DB_PASS         = "HARNESS_AUDIT_DB_PASS";
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
    public static final String SERVER_WORKERS        = "HARNESS_SERVER_WORKERS";

    // ==================== CLI ====================
    public static final String CLI_ENABLED           = "HARNESS_CLI_ENABLED";

    // ==================== Multimodal ====================
    public static final String MULTIMODAL_IMAGE_ENABLED   = "HARNESS_MULTIMODAL_IMAGE_ENABLED";
    public static final String MULTIMODAL_VIDEO_ENABLED   = "HARNESS_MULTIMODAL_VIDEO_ENABLED";
    public static final String MULTIMODAL_FILE_MAX_SIZE   = "HARNESS_MULTIMODAL_FILE_MAX_SIZE_MB";
    public static final String MULTIMODAL_URL_BLOCK_PRIVATE_IPS = "HARNESS_MULTIMODAL_URL_BLOCK_PRIVATE_IPS";

    // ==================== Input (File Parsing) ====================
    public static final String INPUT_FILE_SIZE_THRESHOLD_KB     = "HARNESS_INPUT_FILE_SIZE_THRESHOLD_KB";
    public static final String INPUT_CHUNK_TOKEN_SIZE           = "HARNESS_INPUT_CHUNK_TOKEN_SIZE";
    public static final String MODEL_CHAT_CONTEXT_WINDOW       = "HARNESS_MODEL_CHAT_CONTEXT_WINDOW";
    public static final String LARGE_FILE_CONTEXT_RATIO        = "HARNESS_LARGE_FILE_CONTEXT_RATIO";
    public static final String LARGE_FILE_SUMMARY_CONCURRENCY  = "HARNESS_LARGE_FILE_SUMMARY_CONCURRENCY";

    // ==================== Semantic RAG ====================
    public static final String RAG_CONTEXT_LOOKBACK_MAX    = "HARNESS_RAG_CONTEXT_LOOKBACK_MAX";

    // ==================== Query Rewriting ====================
    public static final String RAG_QUERY_REWRITE           = "HARNESS_RAG_QUERY_REWRITE";
    public static final String RAG_QUERY_REWRITE_COUNT     = "HARNESS_RAG_QUERY_REWRITE_COUNT";

    // ==================== Multi-Route Retrieval ====================
    public static final String RAG_MULTI_ROUTE             = "HARNESS_RAG_MULTI_ROUTE";
    public static final String RAG_FULLTEXT_ENABLED        = "HARNESS_RAG_FULLTEXT_ENABLED";
    public static final String RAG_FULLTEXT_LANG           = "HARNESS_RAG_FULLTEXT_LANG";
    public static final String RAG_KNOWLEDGE_GRAPH_ENABLED = "HARNESS_RAG_KNOWLEDGE_GRAPH_ENABLED";

    // ==================== AI Fallback ====================
    public static final String MODEL_CHAT_CAPABILITIES  = "HARNESS_MODEL_CHAT_CAPABILITIES";

    // ==================== Memory (会话记忆管理) ====================
    public static final String MEMORY_STORE                    = "HARNESS_MEMORY_STORE";
    public static final String SESSION_TIMEOUT_MINUTES         = "HARNESS_SESSION_TIMEOUT_MINUTES";
    public static final String MEMORY_MIN_MESSAGES             = "HARNESS_MEMORY_MIN_MESSAGES";
    public static final String MEMORY_MIN_USER_CHARS           = "HARNESS_MEMORY_MIN_USER_CHARS";
    public static final String MEMORY_LONGTERM_MAX_TOKENS      = "HARNESS_MEMORY_LONGTERM_MAX_TOKENS";
    public static final String CTX_COMPRESS_MINOR              = "HARNESS_CTX_COMPRESS_MINOR";
    public static final String CTX_COMPRESS_MINOR_ENABLED      = "HARNESS_CTX_COMPRESS_MINOR_ENABLED";
    public static final String CTX_COMPRESS_MAJOR              = "HARNESS_CTX_COMPRESS_MAJOR";
    public static final String CTX_COMPRESS_MAJOR_TARGET        = "HARNESS_CTX_COMPRESS_MAJOR_TARGET";
    public static final String CTX_COMPRESS_MINOR_TARGET        = "HARNESS_CTX_COMPRESS_MINOR_TARGET";
    public static final String SYSTEM_PROMPT                   = "HARNESS_SYSTEM_PROMPT";
    public static final String MEMORY_REFINEMENT_MIN_SCORE     = "HARNESS_MEMORY_REFINEMENT_MIN_SCORE";
    public static final String MEMORY_CLEANUP_INTERVAL_MINUTES = "HARNESS_MEMORY_CLEANUP_INTERVAL_MINUTES";
    public static final String MEMORY_REFINEMENT_STUCK_MINUTES = "HARNESS_MEMORY_REFINEMENT_STUCK_MINUTES";

    // ==================== Cache (会话缓存管理) ====================
    /** Max messages cached per session; oldest evicted when exceeded. 单 session 缓存消息上限，超出淘汰最早的 */
    public static final String CACHE_MAX_MESSAGES_PER_SESSION = "HARNESS_CACHE_MAX_MESSAGES_PER_SESSION";
    /** Max total cache memory in MB; LRU evict coldest 50% sessions when exceeded. 缓存总内存上限(MB)，超出按 LRU 淘汰最冷 50% */
    public static final String CACHE_MAX_MB                   = "HARNESS_CACHE_MAX_MB";
    /** Cache session TTL in hours; idle sessions expired by background cleanup. 缓存 session 过期时间(小时)，空闲超时自动淘汰 */
    public static final String CACHE_SESSION_TTL_HOURS        = "HARNESS_CACHE_SESSION_TTL_HOURS";
    /** Max concurrent sessions in cache. 缓存最大 session 并发数 */
    public static final String CACHE_MAX_SESSIONS             = "HARNESS_MEMORY_CACHE_MAX_SESSIONS";

    // TODO: Redis cache — 暂未实现，预留扩展
    public static final String MEMORY_REDIS_URL                = "HARNESS_MEMORY_REDIS_URL";
    public static final String MEMORY_REDIS_PASSWORD           = "HARNESS_MEMORY_REDIS_PASSWORD";
    public static final String MEMORY_REDIS_DB                 = "HARNESS_MEMORY_REDIS_DB";
    public static final String MEMORY_REDIS_KEY_PREFIX         = "HARNESS_MEMORY_REDIS_KEY_PREFIX";
    public static final String MEMORY_REDIS_TTL_MINUTES        = "HARNESS_MEMORY_REDIS_TTL_MINUTES";

    // ==================== Knowledge Base ====================
    public static final String KNOWLEDGE_UPLOAD_DIR          = "HARNESS_KNOWLEDGE_UPLOAD_DIR";
    public static final String KNOWLEDGE_DEFAULT_COLLECTION  = "HARNESS_KNOWLEDGE_DEFAULT_COLLECTION";
    public static final String KNOWLEDGE_MAX_FILE_SIZE_MB    = "HARNESS_KNOWLEDGE_MAX_FILE_SIZE_MB";
    public static final String KNOWLEDGE_CHUNK_SIZE          = "HARNESS_KNOWLEDGE_CHUNK_SIZE";
    public static final String KNOWLEDGE_PDF_ENABLED         = "HARNESS_KNOWLEDGE_PDF_ENABLED";
    public static final String KNOWLEDGE_DOCX_ENABLED        = "HARNESS_KNOWLEDGE_DOCX_ENABLED";
    public static final String KNOWLEDGE_XLSX_ENABLED        = "HARNESS_KNOWLEDGE_XLSX_ENABLED";
}
