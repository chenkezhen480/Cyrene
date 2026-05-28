# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build all modules (produces fat JARs for cli and server)
mvn clean package

# Build single module
mvn clean package -pl harness-core -am

# Compile only (no tests, no packaging)
mvn clean compile

# Run CLI (interactive REPL)
java -jar harness-cli/target/harness-cli-0.1.0-SNAPSHOT.jar

# Run HTTP server (Javalin, default port 8080)
java -jar harness-server/target/harness-server-0.1.0-SNAPSHOT.jar

# Run tests (currently no test files exist)
mvn test
mvn test -pl harness-core          # single module
mvn test -Dtest=ClassName          # single test class
```

Before running, copy `.env.example` to `.env` and configure at minimum:
- `HARNESS_MODEL_CHAT_PROVIDER` + `HARNESS_MODEL_CHAT_API_KEY`

## Architecture

5-layer pipeline orchestrated by `AgentOrchestrator`:

```
Input → Session Lifecycle → Preprocess → ReAct Loop (AI ↔ Tool ↔ Inspection) → Post-process → Audit
```

Memory integration points: session lifecycle (timeout + cache load), preprocess (major compression), ReAct (minor compression: tool block stripping), post-process (message save + cache sync).

**Module dependency graph (bottom-up):**
```
harness-env           ← foundation, all HARNESS_* env var access via EnvConfig
harness-core          ← models (AgentMessage, AgentTrace, ReActStep, ToolSpec, ParsedContent, etc.)
    ├── harness-input        ← auth (Authenticator) + multimodal parsing (MultimodalParser) + text extraction (TextExtractorRegistry) + text chunking (TextChunker) + large file parsing (LargeFileParser)
    ├── harness-preprocess   ← RAG retrieval (PgVectorRagRetriever) + semantic context (SemanticContextRetriever) + rerank + context injection (ContextBuilder)
    ├── harness-tool         ← Tool interface, ToolRegistry, ToolExecutor, MCP adapter (McpToolAdapter), built-in tools
    ├── harness-audit        ← TraceCollector + TraceStore (sqlite/mysql/file/none via TraceStoreFactory)
    └── harness-ai           ← LangChain4j integration, 6 model type abstractions, ModelProviderFactory, ReActEngine, FallbackChatModel
harness-agent         ← Agent orchestration library (com.harness.agent.AgentOrchestrator)
harness-cli           ← CLI entry point (com.harness.cli.Main)
harness-server        ← HTTP API entry point (com.harness.server.Main, Javalin)
```

`harness-ai` depends on `harness-tool` (ReActEngine calls ToolExecutor).
`harness-input` and `harness-preprocess` depend on `harness-ai` (for model provider interfaces).
`harness-preprocess` depends on `harness-input` (for `TextChunker` and `TextExtractorRegistry`).
`harness-cli` and `harness-server` both depend on `harness-agent` (AgentOrchestrator).

## Key Conventions

- **All configuration via environment variables.** Every `HARNESS_*` key is defined in `EnvKey.java` (harness-env). Never hardcode config values — read from `EnvConfig.get()`.
- **6 model types, independently configurable.** Each has a provider interface in `com.harness.ai.model` and implementations in `com.harness.ai.model.impl`. NoOp implementations used when unconfigured.

  | # | Type | Interface | Providers |
  |---|------|-----------|-----------|
  | 1 | Chat (required) | `ChatModelProvider` | openai, anthropic, ollama |
  | 2 | Vision | `VisionModelProvider` | openai, anthropic |
  | 3 | Voice (ASR+TTS) | `VoiceModelProvider` | openai |
  | 4 | Embedding | `EmbeddingModelProvider` | openai, ollama |
  | 5 | Rerank | `RerankModelProvider` | openai |
  | 6 | Realtime | `RealtimeModelProvider` | (reserved) |

- **Tools implement `com.harness.tool.Tool` interface** — `spec()` returns ToolSpec, `execute(JsonNode)` returns String. Register in ToolRegistry. MCP tools adapted via McpToolAdapter.
- **ReActEngine** uses LangChain4j 1.15.0 `ChatModel.chat(ChatRequest)` with `ToolSpecification` (JsonObjectSchema). Wraps ChatModel in `FallbackChatModel` when vision/voice providers are available.
- **ReAct Inspection** — 每轮工具调用后检查结果状态，记录在 `ReActStep.InspectionResult` 中：

  | 状态 | 含义 | 实现状态 |
  |------|------|----------|
  | `PASS` | 工具执行正确，结果可用 | 已实现 |
  | `TOOL_ERROR` | 工具执行失败 | 已实现，可触发 `stopOnToolError` 停止循环 |
  | `WRONG_TOOL` | 选错了工具 | 仅定义，未实现 |
  | `INSUFFICIENT` | 结果不够完整 | 仅定义，未实现 |
  | `NEEDS_RETRY` | 应该换参数重试 | 仅定义，未实现 |

  `HARNESS_REACT_STOP_ON_TOOL_ERROR=true` 时遇到 `TOOL_ERROR` 立即停止循环；默认 `false` 继续下一轮让 LLM 自行决策。
- **Audit traces are cross-cutting.** TraceCollector created per agent run, persisted via TraceStore (sqlite/mysql/file/none). Key decision points (fallback triggers, rerank timing, lookback counts) recorded in `AgentTrace.metadata`.
- **Fat JARs via maven-shade-plugin.** CLI and server modules produce executable uber-JARs.

## Subsystems

### Large File Parsing (harness-input)
Files exceeding `HARNESS_INPUT_FILE_SIZE_THRESHOLD_KB` (default 512) are parsed via `LargeFileParser`: extract text via `TextExtractorRegistry` (supports PDF/DOCX/XLSX/text) → split into chunks via `TextChunker` (paragraph → line → fixed token) → summarize each chunk via ChatModelProvider → merge summaries (flat for ≤8 chunks, tree reduce for more). Output is `ParsedContent` with strategy `CHUNKED_REDUCE`.

### Multimodal Fallback (harness-ai)
`FallbackChatModel` wraps the main ChatModel. When a request contains ImageContent/AudioContent but the model lacks the capability (checked via `ModalCapabilityRegistry`), it transparently falls back to VisionModelProvider (image→text) or VoiceModelProvider (audio→text). Override model capabilities via `HARNESS_MODEL_CHAT_CAPABILITIES`.

### Semantic Context Retrieval (harness-preprocess)
`SemanticContextRetriever` checks retrieved chunks for semantic completeness. Truncated chunks trigger lookback to previous chunks via `prev_chunk_id` in pgvector. Max lookback controlled by `HARNESS_RAG_CONTEXT_LOOKBACK_MAX` (default 2).

### Rerank Integration (harness-ai + harness-preprocess)
`OpenAiRerankModelProvider` calls OpenAI-compatible `/rerank` endpoint. Integrated into `ContextBuilder` pipeline: RAG retrieval → semantic enhancement → rerank → format. Timing and doc counts recorded in metadata.

### Memory & Context Management (harness-preprocess + harness-cli)
Session-based conversation memory with short-term and long-term subsystems. Enabled via `HARNESS_MEMORY_STORE=mysql` (default `none`).

**Complete request flow (5-layer pipeline with memory integration):**
```
Layer 1: Input
  └─ InputProcessor: 解析用户输入，认证，提取 userId

Layer 1.5: Session Lifecycle
  ├─ SessionLifecycleManager: 超时检测 + 关闭旧 session + 创建/复用 session
  ├─ Timed-out sessions → quality check → PreferenceRefinementWorker (async)
  └─ 加载短期记忆（SessionMessageCache 缓存优先，miss 时从 MessageStore 加载）

Layer 2: Preprocess
  ├─ RAG 知识库检索 (ContextBuilder → PgVectorRagRetriever → SemanticContextRetriever → Rerank)
  ├─ System 消息组装 + 长期记忆注入 (PreferenceStore.loadByUser)
  ├─ 大压缩检查（旧消息 + 估算新用户消息 + RAG + system + 长期记忆 > 85%）
  │   └─ 触发: MemoryCompressor 压缩旧消息 → 重新加载含摘要的短期记忆 → invalidate 缓存
  └─ 保存用户消息（MessageWriteWorker 异步入队 + 缓存 append）

Layer 3+4: ReAct Loop
  ├─ AI 决策 (ChatModel + history messages injected between system and user)
  ├─ 工具执行 (ToolExecutor)
  ├─ 小压缩: ReAct 循环内部去除工具代码块 (stripToolMessages，iteration > 2 时触发)
  └─ Inspection: 每轮工具调用后检查结果状态

Post-processing
  ├─ 保存 AI 回复（MessageWriteWorker 异步入队）
  ├─ 缓存同步 append（同步，内存操作）
  └─ Session 活跃时间更新

Layer 5: Audit
  └─ TraceCollector → TraceStore 入库
```

**Key classes (com.harness.preprocess.memory):**
- `SessionStore` / `MessageStore` / `PreferenceStore` — persistence interfaces
- `MysqlSessionStore` / `MysqlMessageStore` / `MysqlPreferenceStore` — MySQL implementations (reuse AUDIT_DB_URL)
- `NoOp*Store` — no-op implementations when `HARNESS_MEMORY_STORE=none`
- `MemoryStoreFactory` — creates stores based on `HARNESS_MEMORY_STORE` env var
- `SessionLifecycleManager` — passive timeout detection + session resolution
- `SessionMessageCache` — LRU cache for active session messages (HashMap, access-ordered, max 100 sessions)
- `TokenBudgetAllocator` — dynamic token budget allocation (system/longterm/shortterm/rag/input)
- `MemoryCompressor` — major compression only: AI-based intelligent extraction with time-decay weighting
- `PreferenceRefinementWorker` — async background worker for preference extraction
- `SessionCleanupScheduler` — periodic scan for timed-out sessions
- `MessageWriteWorker` — async batch writer for user/assistant messages (BlockingQueue, batch 20 or 500ms flush)

**Models (com.harness.core.model):** `Session`, `MemoryMessage`, `Preference`

**Compression (two types, different layers):**
- **小压缩 (Minor, code-based, ReAct 层):** `ReActEngine.stripToolMessages()` — iteration > 2 时去除 `ToolExecutionResultMessage` 和含 `toolExecutionRequests` 的 `AiMessage`，释放上下文空间。不涉及 AI 调用，纯代码操作。
- **大压缩 (Major, AI-based, 预处理层):** `MemoryCompressor.compressIfNeeded()` — 整体上下文 > `HARNESS_CTX_COMPRESS_MAJOR`（默认 85%）时触发。使用时间衰减标签（RECENT/MIDDLE/OLD）智能提炼旧消息，压缩至 `HARNESS_CTX_COMPRESS_MAJOR_TARGET`（默认 30%）。仅压缩旧消息，当前用户消息在压缩之后保存，不会被压缩。
- 原始消息永不删除，只新增 `is_summary=true` 的摘要行。

**Cache strategy (async write-through LRU):**
- **读取:** cache hit → 直接返回; miss → MessageStore.loadForContext → 填充缓存
- **写入:** MessageWriteWorker 异步入队（DB 落盘） + cache.append（同步，内存操作）
- **压缩后:** 从 DB 重建缓存（put），压缩摘要同步写 DB（MemoryCompressor 内部）
- **淘汰:** LRU 自动淘汰冷 session（默认上限 `HARNESS_MEMORY_CACHE_MAX_SESSIONS=100`）
- **扩展:** `HARNESS_MEMORY_REDIS_*` 环境变量已预留（TODO: 暂未实现）

### Knowledge Base Upload (harness-input + harness-preprocess + harness-server)
Independent knowledge base ingestion pipeline via `POST /api/knowledge/upload` (multipart form). Flow:

```
File (multipart) → TextExtractorRegistry → TextChunker.split
  → EmbeddingModelProvider.embedAll → FileStorageService (disk) + PgVectorRagRetriever.insertBatchWithLinks (pgvector)
```

**Supported formats:** TXT, Markdown, CSV, JSON, XML (PlainTextExtractor), PDF (PdfTextExtractor via Apache PDFBox), DOCX/XLSX (OfficeTextExtractor via Apache POI). Falls back to UTF-8 for unmatched types.

**Key classes:**
- `TextExtractor` interface + `TextExtractorRegistry` (harness-input) — format-specific text extraction
- `TextChunker` (harness-input) — static text chunking utility (paragraph → line → fixed token), shared by both LargeFileParser and KnowledgeIngestService
- `KnowledgeIngestService` (harness-preprocess) — orchestrator: extract → chunk → embed → store
- `FileStorageService` (harness-preprocess) — persists files to `{HARNESS_KNOWLEDGE_UPLOAD_DIR}/{collection}/`
- `PgVectorRagRetriever.insertBatchWithLinks()` — inserts chunks with `chunk_index`, `prev_chunk_id`, `next_chunk_id` linking
- `KnowledgeUploadHandler` (harness-server) — Javalin handler for the upload endpoint
- `MediaProcessor` interface (harness-input) — reserved for future video/voice processing

**Chunk linking:** Chunks are inserted in a single transaction with `RETURNING id`, then batch-updated with prev/next links to enable `SemanticContextRetriever` lookback.

## Adding a New LLM Provider

1. Create `YourProviderChatModelProvider implements ChatModelProvider` in `com.harness.ai.model.impl`
2. Add case in `ModelProviderFactory.createChat()`
3. Add env keys in `EnvKey.java` (follow `HARNESS_MODEL_CHAT_*` pattern)

Same pattern for Vision/Voice/Embedding/Rerank providers.

## Adding a New Tool

1. Implement `com.harness.tool.Tool` (define `spec()` + `execute()`)
2. Register in `AgentOrchestrator.registerBuiltinTools()`

## Database Schemas

- MySQL audit: `sql/schema-mysql.sql` (database: `agent`, table: `agent_traces`)
- MySQL memory: `sql/schema-memory-mysql.sql` (database: `agent`, tables: `sessions`, `messages`, `user_preferences`)
- PostgreSQL pgvector RAG: `sql/schema-pgvector.sql` (extension: vector, table: `knowledge_documents` with `chunk_index`, `prev_chunk_id`, `next_chunk_id` columns for semantic lookback)

## HTTP Server Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/chat` | Send message, get agent response |
| POST | `/api/knowledge/upload` | Upload file for knowledge base ingestion (multipart: `file`, `collection`) |
| GET | `/api/trace/{id}` | Get trace by ID (TODO) |
| GET | `/api/traces` | List recent traces (TODO) |
| GET | `/api/health` | Health check |

## Dependencies

- LangChain4j 1.15.0 (BOM-managed)
- Jackson 2.16.1 (JSON)
- OkHttp 4.12.0 (HTTP client for voice/API calls/rerank)
- Javalin 6.1.3 (HTTP server)
- Apache PDFBox 2.0.32 (PDF text extraction, harness-input)
- Apache POI 5.2.5 (DOCX/XLSX parsing, harness-input)
- SQLite/MySQL/PostgreSQL JDBC drivers
- SLF4J 2.0.11 + Logback 1.4.14
