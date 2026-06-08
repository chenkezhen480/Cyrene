# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in the Cyrene Agent repository.

## Build & Run

```bash
# Build all modules (produces fat JARs for cli and server)
mvn clean package

# Build single module
mvn clean package -pl harness-core -am

# Compile only (no tests, no packaging)
mvn clean compile

# Run CLI (interactive REPL)
java -jar harness-cli/target/harness-cli-0.3.0.jar

# Run HTTP server (Javalin, default port 8080)
java -jar harness-server/target/harness-server-0.3.0.jar

# Run tests (currently no test files exist)
mvn test
mvn test -pl harness-core          # single module
mvn test -Dtest=ClassName          # single test class
```

Before running, copy `.env.example` to `.env` and configure at minimum:
- `HARNESS_MODEL_CHAT_PROVIDER` + `HARNESS_MODEL_CHAT_API_KEY`
- `.env` is auto-loaded by `EnvConfig` from the working directory; system env vars take precedence

## Architecture

5-layer pipeline orchestrated by `AgentOrchestrator`:

```
Input → Session Lifecycle → Preprocess → ReAct Loop (AI ↔ Tool ↔ Inspection) → Post-process → Audit
```

Memory integration points: session lifecycle (timeout + cache load), preprocess (major compression), ReAct (minor compression: tool block stripping), post-process (message save + cache sync).

**Module dependency graph (bottom-up):**
```
harness-env           ← foundation, all HARNESS_* env var access via EnvConfig + shared HikariCP connection pool (MysqlConnectionPool)
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
- **ReActEngine** uses LangChain4j 1.15.0 `ChatModel.chat(ChatRequest)` with `ToolSpecification` (JsonObjectSchema). Wraps ChatModel in `FallbackChatModel` when vision/voice providers are available. Supports streaming via `StreamingChatModel` (`streamExecute()` method). Tool calls retry up to 3 times on error; success with empty data passes to LLM without retry.
- **Thinking Mode Control** — `HARNESS_MODEL_CHAT_THINKING` env var (default `true`) controls whether to enable thinking/reasoning mode for the chat model. `ChatModelProvider.chatModelNoThinking()` returns a model with thinking disabled (used by `LargeFileParser` for cost savings). Per-request override via `AgentContext.enableThinking()` (set in `context.enableThinking` JSON field). Providers pass `enable_thinking` via `customParameters(Map)` to OpenAI-compatible APIs (DashScope etc.). `ReActEngine.selectModel(Boolean)` picks thinking/non-thinking model; streaming mode falls back to blocking when thinking is disabled.
- **File Content Injection** — File attachments are extracted and injected into the LLM context via `enhancedText` in `AgentOrchestrator`. Small files (< `HARNESS_INPUT_FILE_SIZE_THRESHOLD_KB`, default 100KB) use `TextExtractorRegistry.extract()` directly; large files use `LargeFileParser` merge-then-summarize approach: semantic splitting → greedy merge to `MODEL_CHAT_CONTEXT_WINDOW × LARGE_FILE_CONTEXT_RATIO` (default 40%) → parallel summarization → final merge. Works independently of RAG knowledge base configuration.
- **ReAct Inspection** — 每轮工具调用后由 `Inspector` 启发式检查结果状态，记录在 `ReActStep.InspectionResult` 中：

  | 状态 | 含义 | 触发条件 |
  |------|------|----------|
  | `PASS` | 工具执行正确，结果可用 | 默认 |
  | `TOOL_ERROR` | 工具执行失败 | `!result.success()` |
  | `WRONG_TOOL` | 选错了工具 | 输出为 null/空白 |
  | `INSUFFICIENT` | 结果不够完整 | 输出过短 (<50字符) 或含 "no results" 等短语 |
  | `NEEDS_RETRY` | 应该换参数重试 | 输出含异常堆栈痕迹 |

  非 PASS 状态会注入 `Inspector.buildInspectionHint()` 作为下一轮的提示。`HARNESS_REACT_STOP_ON_TOOL_ERROR=true` 时遇到 `TOOL_ERROR` 立即停止循环；默认 `false` 继续下一轮让 LLM 自行决策。
- **ReActListener** — SSE 流式回调接口，`onStep(ReActStep)` 在每轮迭代完成后调用。扩展方法：`onToken(String)` 每个 token 块回调，`onToolCallStart(String toolName, String arguments)` 工具调用开始回调。
- **Streaming Output** — `AgentOrchestrator.streamRun()` 提供流式输出模式，通过 `StreamCallback` 实时推送 `StreamEvent`（START/TOKEN/STEP/DONE/ERROR）。`AgentContext` record 包装 `Map<String, Object>` context 参数，`isStreaming()` 判断输出模式。流式模式下后处理（trace.finish、sessionStore.updateLastActive）异步化。
- **CancellationToken** — 线程安全的取消令牌，支持轮询（`isCancelled()`）和线程中断（`Thread.interrupt()`）双重取消机制。`cancel()` 会中断所有已注册的工作线程（包括子代理线程）。ReAct 循环在每轮迭代间、LLM 调用后、工具执行前均检查取消状态；`DELETE /api/chat/{sessionId}` 触发取消。
- **ReplyAuditor** — 异步启发式回复质量审计（无模型调用），检查回复长度、错误指标、工具残留。结果写入 trace metadata。
- **Sub-Agent** — `SubAgentOrchestrator` 管理子代理生命周期，支持依赖解析和并行执行。LLM 通过 `spawn_subagent` 工具派生子任务，每个子代理拥有独立的 ReActEngine 实例但共享工具注册表。`MultiAgentOrchestrator` 提供编程式子代理访问。`HARNESS_AGENT_MAX_SUBAGENTS`（默认 3）控制并发数。
- **Web Search fallback** — `WebSearchTool` 支持多引擎回退链：Tavily → SerpAPI → DuckDuckGo。优先级通过 `HARNESS_TOOL_WEB_SEARCH_PRIORITY` 配置，无 API key 的引擎自动跳过，DuckDuckGo 始终可用。
- **MCP Tool Discovery** — `McpToolDiscovery` 通过 JSON-RPC `tools/list` 自动发现 MCP 服务器工具，结果缓存避免重复发现。MCP 服务器配置支持两种方式：`HARNESS_MCP_CONFIG_FILE`（JSON 文件，推荐）或 `HARNESS_MCP_SERVERS`（逗号分隔的 `name=url` 环境变量）。JSON 文件中每个服务器可独立设置 `connectTimeoutMs` / `callTimeoutMs`。
- **Retry & Robustness** — 六层容错机制：
  - **Tool 调用重试:** `ReActEngine.executeWithRetry()` — 工具调用失败时最多重试 3 次，成功（即使输出为空）不重试，错误结果传递给 LLM 由其决策
  - **LLM API 重试:** `RetryingChatModel` 装饰器包装所有 ChatModelProvider，指数退避（1s→2s→4s），最多 3 次，仅重试 429/503/timeout 等可恢复错误
  - **MCP 断线重连:** `McpToolAdapter` 捕获 IOException 后重建 OkHttpClient 并重试 1 次
  - **消息写入重试:** `MessageWriteWorker` 三层降级：重试 3 次 → 单条同步写 → 死信队列（`getDeadLetterQueue()` API）
  - **Refinement 卡住恢复:** `SessionCleanupScheduler.resetStuckRefinements()` 每轮清理前检测 `in_progress` 超过阈值的记录，CAS 重置为 `pending`
  - **知识库批量回滚:** `PgVectorRagRetriever.insertBatchWithLinks()` 显式事务回滚 + `KnowledgeIngestService` 清理孤立文件
- **JWT 滑动窗口刷新** — `ChatHandler` 在 JWT 剩余有效期 < `HARNESS_AUTH_JWT_REFRESH_THRESHOLD_MINUTES`（默认 60 分钟）时自动刷新，新 token 通过 `X-New-Token` 响应头返回。
- **Audit traces are cross-cutting.** TraceCollector created per agent run, persisted via TraceStore (sqlite/mysql/file/none). Key decision points (fallback triggers, rerank timing, lookback counts) recorded in `AgentTrace.metadata`.
- **Fat JARs via maven-shade-plugin.** CLI and server modules produce executable uber-JARs.
- **Skill Loading** — 可复用能力包机制，三层架构：System prompt（身份）→ Skill（操作规范）→ MCP/builtin tools（实现）。Skill 文件为 Markdown + YAML frontmatter 格式，启动时扫描 `HARNESS_SKILL_DIR`（默认 `./skills`）目录建立轻量索引（name + description），注入 system prompt。LLM 通过 `load_skill` 工具按需加载全文或搜索片段（`name` + 可选 `query` 正则搜索，返回最多 5 个匹配及 ±3 行上下文）。用户上传的 `.md` skill 文件注册为临时 skill（session 级别）。Skill 内容在小压缩（`stripToolMessages`）中保留不删除，大压缩后从 `loadedSkills` 缓存重新注入。Skill session 数据跟随会话 TTL 过期（`CACHE_SESSION_TTL_HOURS`，默认 12h 空闲），不随请求结束清除。关键类：`SkillLoader`（YAML 解析）、`SkillRegistry`（索引 + session 级缓存 + TTL 淘汰）、`LoadSkillTool`（统一工具，支持全文加载和搜索模式）。

## Subsystems

### Large File Parsing (harness-input)
Files exceeding `HARNESS_INPUT_FILE_SIZE_THRESHOLD_KB` (default 100) are parsed via `LargeFileParser` using a **merge-then-summarize** approach:

```
TextExtractorRegistry.extract() → TextChunker.split() (semantic boundaries)
  → greedy merge consecutive chunks until reaching contextWindow × LARGE_FILE_CONTEXT_RATIO (default 40%)
  → parallel summarize each merged block (noThinkingModel, bounded by LARGE_FILE_SUMMARY_CONCURRENCY, default 3)
  → final merge all block summaries
```

Context window is **auto-detected** from model name via `ChatModelProvider.contextWindow()` (known mappings: Claude→200K, GPT-4→128K, Gemini→1M, Qwen→128K, DeepSeek→64K, o3→200K). Override via `HARNESS_MODEL_CHAT_CONTEXT_WINDOW` env var if needed.

Key env vars:
- `HARNESS_MODEL_CHAT_CONTEXT_WINDOW` (optional, auto-detected) — override model context window size
- `HARNESS_LARGE_FILE_CONTEXT_RATIO` (default 0.4) — fraction of context window used per summarization block
- `HARNESS_LARGE_FILE_SUMMARY_CONCURRENCY` (default 3) — max parallel summarization threads
- `HARNESS_INPUT_CHUNK_TOKEN_SIZE` (default 1024) — per-semantic-chunk token size

LLM call count: for a 1MB file (~333K tokens), this produces ~326 chunks → merged into ~4-7 blocks → 4-7 summary calls + 1 merge call ≈ **5-8 total LLM calls** (vs ~375 in the old per-chunk approach). Output is `ParsedContent` with strategy `CHUNKED_REDUCE`. Files below the threshold use direct text extraction (`TextExtractorRegistry.extract()`) with `ParseStrategy.DIRECT`.

### Multimodal Fallback (harness-ai)
`FallbackChatModel` wraps the main ChatModel. When a request contains ImageContent/AudioContent but the model lacks the capability (checked via `ModalCapabilityRegistry`), it transparently falls back to VisionModelProvider (image→text) or VoiceModelProvider (audio→text). Override model capabilities via `HARNESS_MODEL_CHAT_CAPABILITIES`.

### Semantic Context Retrieval (harness-preprocess)
`SemanticContextRetriever` checks retrieved chunks for semantic completeness using pure heuristic analysis (no LLM calls). Truncated chunks trigger lookback to previous chunks via `prev_chunk_id` in pgvector. Completeness detection: terminal punctuation (`。！？…""''` etc.) → structural endings (`]}`) → opening continuation word detection (35 Chinese continuations like `而且/但是/因此`, 27 English like `and/but/then`). Max lookback controlled by `HARNESS_RAG_CONTEXT_LOOKBACK_MAX` (default 2).

### Rerank Integration (harness-ai + harness-preprocess)
`OpenAiRerankModelProvider` calls OpenAI-compatible `/rerank` endpoint. Integrated into `ContextBuilder` pipeline: RAG retrieval → semantic enhancement → rerank → format. Timing and doc counts recorded in metadata.

### Query Rewriting (harness-preprocess)
Pluggable pre-retrieval query transformation. `QueryRewriter` interface with `List<String> rewrite(String)` — returns one or more rewritten queries. Controlled by `HARNESS_RAG_QUERY_REWRITE` (default `none`).

| Strategy | Env Value | Behavior |
|----------|-----------|----------|
| NoOp | `none` | Pass-through, no LLM call |
| HyDE | `hyde` | LLM generates hypothetical answer, uses it as retrieval query |
| Multi-Query | `multi-query` | LLM generates N alternative formulations (`HARNESS_RAG_QUERY_REWRITE_COUNT`, default 3), retrieves for each, merges by ID |
| Step-Back | `step-back` | LLM generates a more general/abstract version of the query |

All strategies use `chatModelNoThinking()` for cost savings. LLM failure falls back to original query. Multi-query results are merged by document ID (keep highest score) before passing to downstream pipeline.

Key classes: `QueryRewriter` (interface), `QueryRewriterFactory`, `NoOpQueryRewriter`, `HydeQueryRewriter`, `MultiQueryRewriter`, `StepBackQueryRewriter`.

### Multi-Route Retrieval (harness-preprocess)
Parallel fan-out to multiple retrieval backends, merge and deduplicate results. Enabled via `HARNESS_RAG_MULTI_ROUTE` (default `false`). When disabled, uses the existing single-provider `RagRetriever` path.

**RetrievalRoute interface:** `List<RagDocument> retrieve(String query)`, `routeName()`, `isAvailable()`.

| Route | Env Control | Backend |
|-------|-------------|---------|
| PgVectorRoute | `HARNESS_RAG_PROVIDER=pgvector` | pgvector cosine similarity (existing) |
| FulltextRoute | `HARNESS_RAG_FULLTEXT_ENABLED=true` | PostgreSQL `tsvector`/`tsquery` keyword search |
| KnowledgeGraphRoute | `HARNESS_RAG_KNOWLEDGE_GRAPH_ENABLED=true` | Stub (not yet implemented) |

`MultiRouteRetriever` fans out via `CompletableFuture.allOf()`, merges by document ID (keep highest score). Individual route failures are caught and logged — graceful degradation.

Fulltext route requires `content_tsv` tsvector column + GIN index on `knowledge_documents` table (see `sql/schema-pgvector.sql`). Language config via `HARNESS_RAG_FULLTEXT_LANG` (default `english`, use `simple` for Chinese).

Key classes: `RetrievalRoute` (interface), `RetrievalRouteFactory`, `PgVectorRoute`, `FulltextRoute`, `KnowledgeGraphRoute`, `MultiRouteRetriever`.

### Memory & Context Management (harness-preprocess + harness-cli)
Session-based conversation memory with short-term and long-term subsystems. Enabled via `HARNESS_MEMORY_STORE=mysql` (default `none`).

**Context allocation strategy:** Short-term memory and RAG content are included as-is (no dynamic budget redistribution between them). When total context exceeds `HARNESS_CTX_COMPRESS_MAJOR` (default 85%), only short-term messages are compressed. Long-term memory is injected into the system prompt, capped at `HARNESS_MEMORY_LONGTERM_MAX_TOKENS` (default 800). Base system prompt is configurable via `HARNESS_SYSTEM_PROMPT` env var.

**Complete request flow (5-layer pipeline with memory integration):**
```
Layer 1: Input
  └─ InputProcessor: 解析用户输入，认证，提取 userId

Layer 1.5: Session Lifecycle
  ├─ SessionLifecycleManager: 超时检测 + 关闭旧 session + 创建/复用 session + 自动设置标题
  ├─ Timed-out sessions → async quality check (fire-and-forget) → PreferenceRefinementWorker
  └─ 并行加载: 短期记忆 + 长期偏好 + RAG 检索 (CompletableFuture.allOf)

Layer 2: Preprocess
  ├─ RAG 知识库检索 (ContextBuilder → QueryRewriter → [MultiRouteRetriever | RagRetriever] → SemanticContextRetriever → Rerank)
  ├─ 加载长期记忆 (PreferenceStore.loadByUser)
  ├─ System 消息组装: HARNESS_SYSTEM_PROMPT 基础提示词 + 长期记忆（受 MEMORY_LONGTERM_MAX_TOKENS 限制）+ RAG 上下文
  ├─ 短期记忆 + RAG 有多少放多少，总上下文 > HARNESS_CTX_COMPRESS_MAJOR（默认 85%）时触发大压缩
  │   └─ 触发: MemoryCompressor 压缩旧消息 → 重新加载含摘要的短期记忆 → invalidate 缓存
  └─ 保存用户消息（MessageWriteWorker 异步入队 + 缓存 append）

Layer 3+4: ReAct Loop
  ├─ AI 决策 (ChatModel + history messages injected between system and user)
  ├─ 工具执行 (ToolExecutor)
  ├─ 小压缩: ReAct 循环内部去除工具代码块 (stripToolMessages，iteration > HARNESS_CTX_COMPRESS_MINOR 时触发 + 循环结束后收尾清理)
  └─ Inspection: 每轮工具调用后检查结果状态

Post-processing
  ├─ ReplyAuditor 异步回复质量审计（启发式，无模型调用）
  ├─ 保存 AI 回复（MessageWriteWorker 异步入队）
  ├─ 缓存同步 append（同步，内存操作）
  └─ Session 活跃时间更新

Layer 5: Audit
  └─ TraceCollector → TraceStore 入库
```

**Key classes (com.harness.preprocess.memory):**
- `SessionStore` / `MessageStore` / `PreferenceStore` — persistence interfaces
- `MysqlSessionStore` / `MysqlMessageStore` / `MysqlPreferenceStore` — MySQL implementations, all use shared `MysqlConnectionPool` (HikariCP, in harness-env)
- `NoOp*Store` — no-op implementations when `HARNESS_MEMORY_STORE=none`
- `MemoryStoreFactory` — creates stores based on `HARNESS_MEMORY_STORE` env var
- `SessionLifecycleManager` — passive timeout detection + session resolution + refinement quality scoring (4 signals: conversation turns, tool usage, avg reply length, user questions). Uses consolidated `MessageStore.loadSessionStats()` (single GROUP BY query, replaces 7-8 individual queries).
- `SessionMessageCache` — LRU cache for active session messages with three-layer eviction: per-session message cap (default 10), total memory cap (default 20MB, evict coldest 50%), session TTL (default 12h idle expiry)
- `MemoryCompressor` — major compression only: AI-based intelligent extraction with time-decay weighting
- `PreferenceRefinementWorker` — async background worker for preference extraction (output constrained by `HARNESS_MEMORY_LONGTERM_MAX_TOKENS`)
- `SessionCleanupScheduler` — periodic scan for timed-out sessions
- `MessageWriteWorker` — async batch writer for user/assistant messages (BlockingQueue, batch 20 or 500ms flush)

**Models (com.harness.core.model):** `Session`, `MemoryMessage`, `Preference`

**Compression (two types, different layers):**
- **小压缩 (Minor, code-based, ReAct 层):** `ReActEngine.stripToolMessages()` — ReAct 循环中当迭代轮次 > `HARNESS_CTX_COMPRESS_MINOR`（默认 2）时触发，去除 `ToolExecutionResultMessage` 和含 `toolExecutionRequests` 的 `AiMessage`。`load_skill` 结果被显式保留（skill 内容不随其他工具结果删除）。可通过 `HARNESS_CTX_COMPRESS_MINOR_ENABLED=true/false` 开关。循环结束后自动执行一轮收尾清理。不涉及 AI 调用，纯代码操作。
- **大压缩 (Major, AI-based, 预处理层):** `MemoryCompressor.compressIfNeeded()` — 整体上下文 > `HARNESS_CTX_COMPRESS_MAJOR`（默认 85%）时触发。使用时间衰减标签（RECENT/MIDDLE/OLD）智能提炼旧消息，压缩至 `HARNESS_CTX_COMPRESS_MAJOR_TARGET`（默认 30%）。仅压缩旧消息，当前用户消息在压缩之后保存，不会被压缩。
- 原始消息永不删除，只新增 `is_summary=true` 的摘要行。

**Performance optimizations (v0.2.7):**
- **HikariCP connection pool** — `MysqlConnectionPool` singleton in `harness-env`, shared by all MySQL stores (Session/Message/Preference/Trace). One pool per JVM (maxPool=10, minIdle=2). Eliminates per-operation TCP+MySQL handshake overhead.
- **Memory/RAG parallel loading** — Short-term memory (`messageStore.loadForContext`), long-term prefs (`preferenceStore.loadByUser`), and RAG retrieval (`contextBuilder.build`) run concurrently via `CompletableFuture.allOf()`. RAG starts immediately before session lifecycle resolves.
- **Refinement SQL consolidation** — `MessageStore.loadSessionStats()` replaces 7-8 individual queries with a single GROUP BY. `SessionLifecycleManager.isWorthyOfRefinement()` uses consolidated stats. Refinement check is fire-and-forget async (`CompletableFuture.runAsync`), non-blocking.
- **Session title** — `Session` record includes `title` field. Auto-set from user's first message (truncated to 100 chars). DB column: `sessions.title VARCHAR(256)`.
- **URL file download** — `RawAttachment` supports `url` field. `UrlDownloader` downloads to `HARNESS_KNOWLEDGE_UPLOAD_DIR/downloads/`, with SSRF protection (block private/loopback IPs) and MIME detection. Same text extraction pipeline as local files.

**Cache strategy (three-layer eviction LRU):**
- **读取:** cache hit → 直接返回; miss → MessageStore.loadForContext → 填充缓存（trimToLimit 保留最新 N 条）
- **写入:** MessageWriteWorker 异步入队（DB 落盘） + cache.append（同步，内存操作）
- **压缩后:** 从 DB 重建缓存（put），压缩摘要同步写 DB（MemoryCompressor 内部）
- **淘汰 (三层):**
  1. Per-session message cap: oldest messages evicted when count exceeds `HARNESS_CACHE_MAX_MESSAGES_PER_SESSION` (default 10)
  2. Total memory cap: coldest 50% sessions evicted when estimated memory exceeds `HARNESS_CACHE_MAX_MB` (default 20MB)
  3. Session TTL: idle sessions expired after `HARNESS_CACHE_SESSION_TTL_HOURS` (default 12h)
- **最大并发:** `HARNESS_MEMORY_CACHE_MAX_SESSIONS` (default 10) — LinkedHashMap access-ordered LRU 自动淘汰
- **扩展:** `HARNESS_MEMORY_REDIS_*` 环境变量已预留（TODO: 暂未实现）

### Knowledge Base Upload & Management (harness-input + harness-preprocess + harness-server)
Independent knowledge base ingestion pipeline via `POST /api/knowledge/upload` (multipart form). Management via `GET/DELETE /api/knowledge/{collection}` and `DELETE /api/knowledge/{collection}/{documentId}`. Flow:

```
File (multipart) → TextExtractorRegistry → TextChunker.split
  → EmbeddingModelProvider.embedAll → FileStorageService (disk) + PgVectorRagRetriever.insertBatchWithLinks (pgvector)
```

**Supported formats:** TXT, Markdown, CSV, JSON, XML (PlainTextExtractor), PDF (PdfTextExtractor via Apache PDFBox), DOCX/XLSX (OfficeTextExtractor via Apache POI). Falls back to UTF-8 for unmatched types.

**Key classes:**
- `TextExtractor` interface + `TextExtractorRegistry` (harness-input) — format-specific text extraction
- `TextChunker` (harness-input) — semantic boundary text chunking utility (paragraph → sentence → line → fixed token fallback), shared by both LargeFileParser and KnowledgeIngestService. Each semantic unit becomes its own chunk without merging across boundaries.
- `KnowledgeIngestService` (harness-preprocess) — orchestrator: extract → chunk → embed → store
- `FileStorageService` (harness-preprocess) — persists files to `{HARNESS_KNOWLEDGE_UPLOAD_DIR}/{collection}/`
- `PgVectorRagRetriever.insertBatchWithLinks()` — inserts chunks with `chunk_index`, `prev_chunk_id`, `next_chunk_id` linking
- `KnowledgeUploadHandler` (harness-server) — Javalin handler for the upload endpoint
- `KnowledgeManagementHandler` (harness-server) — Javalin handler for list/delete endpoints
- `MediaProcessor` interface (harness-input) — reserved for future video/voice processing

**Chunk linking:** Chunks are inserted in a single transaction with `RETURNING id`, then batch-updated with prev/next links to enable `SemanticContextRetriever` lookback.

### Sub-Agent Orchestrator (harness-agent)
Multi-agent architecture supporting parallel sub-task execution. LLM spawns sub-agents via the `spawn_subagent` tool; each sub-agent runs an independent ReActEngine instance sharing the same ToolRegistry.

**Key classes (com.harness.agent):**
- `SubAgentOrchestrator` — task submission, dependency resolution, parallel execution via thread pool
- `SubAgentTask` — task descriptor with id, description, context, dependencies
- `SubAgentResult` — task result with output, steps, duration
- `SpawnSubAgentTool` — built-in tool exposing sub-agent spawning to the LLM
- `MultiAgentOrchestrator` — programmatic wrapper for external sub-agent access

**Dependency resolution:** Tasks declare dependencies by task ID. Dependent tasks wait for all dependencies to complete; if any dependency fails, the dependent task is skipped with a failure result. Independent tasks run in parallel.

### Skill Loading (harness-tool + harness-agent)
Reusable capability packages defining operational procedures for the LLM. Three-layer architecture: System prompt (identity) → Skill (procedures) → MCP/builtin tools (implementations).

**Skill file format (Markdown + YAML frontmatter):**
```markdown
---
name: code-review
description: 当用户要求审查代码质量、安全性或可维护性时使用
version: "1.0.0"
tools:
  - web_search
  - spawn_subagent
parameters:
  focus: security
---

# 代码审查规范
## 审查步骤
1. 理解代码变更的背景和目的
2. 检查安全漏洞...
```

**Two-phase loading:**
- Phase 1 (startup): `SkillLoader.scanIndex(dir)` parses only frontmatter `name` + `description` → lightweight `SkillIndex`
- Phase 2 (on-demand): LLM calls `load_skill(name)` or `load_skill(name, query)` → returns full content or regex-matched snippets

**Key classes (com.harness.tool.skill):**
- `SkillLoader` — YAML frontmatter parser (`scanIndex`, `loadFull`, `loadFromContent`, `isSkillFile`)
- `SkillRegistry` — session-scoped storage with TTL expiration. Lookup order: `loadedSkills` cache → `temporarySkills` → persistent disk. `evictExpired()` for periodic cleanup.
- `LoadSkillTool` — unified tool: `name` only → full load; `name` + `query` → regex search (case-insensitive, ±3 lines context, max 5 matches)

**Lifecycle:**
1. Startup: scan `HARNESS_SKILL_DIR` (default `./skills`) → persistent index
2. System prompt injection: skill names + descriptions listed for LLM awareness
3. On-demand: `load_skill` → full content loaded, cached in `loadedSkills`
4. Minor compression: `load_skill` results preserved (not stripped with other tool results)
5. Major compression: all messages compressed → loaded skills re-injected from `loadedSkills` cache
6. Expiration: session skill data expires with `CACHE_SESSION_TTL_HOURS` (default 12h idle)

**Upload flow:** User-uploaded `.md` files with valid YAML frontmatter are registered as temporary skills (session-scoped) before sessionId resolution, then associated with the session.

**Env vars:** `HARNESS_SKILL_DIR` (default `./skills`), `HARNESS_CACHE_SESSION_TTL_HOURS` (default 12)

**Models (com.harness.core.model):** `Skill`, `SkillIndex`

## Adding a New LLM Provider

1. Create `YourProviderChatModelProvider implements ChatModelProvider` in `com.harness.ai.model.impl`
2. Add case in `ModelProviderFactory.createChat()`
3. Add env keys in `EnvKey.java` (follow `HARNESS_MODEL_CHAT_*` pattern)

Same pattern for Vision/Voice/Embedding/Rerank providers.

## Adding a New Tool

1. Implement `com.harness.tool.Tool` (define `spec()` + `execute()`)
2. Register in `AgentOrchestrator.registerBuiltinTools()` (for built-in tools) or use `McpToolDiscovery` (for MCP tools)

## Database Schemas

- MySQL (总表，不含 users): `sql/schema-mysql.sql` (database: `agent`, tables: `agent_traces`, `sessions`, `messages`, `user_preferences`)
- MySQL users: `sql/schema-users-mysql.sql` (database: `agent`, table: `users` with SHA-256 password hash)
- PostgreSQL pgvector RAG: `sql/schema-pgvector.sql` (extension: vector, table: `knowledge_documents` with `chunk_index`, `prev_chunk_id`, `next_chunk_id` columns for semantic lookback)
- Schema comments: `sql/add-comments.sql` — ALTER TABLE statements to add Chinese COMMENT annotations to all existing tables

## HTTP Server Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/token` | Get JWT token (userId/username + password, mode=jwt only) |
| POST | `/api/chat` | Send message, get agent response (SSE stream). Supports `systemPrompt` and `context` (JSON: `outputMode`=blocking/streaming, `userId`, `enableThinking`) in body, `X-Session-Id` header |
| DELETE | `/api/chat/{sessionId}` | Cancel an in-progress chat request |
| POST | `/api/sessions` | Create a new session (body: `userId`, optional `title`) |
| GET | `/api/sessions` | List sessions with cursor pagination. Query: `userId`, `status` (active/ended/timeout), `limit`, `cursor` (ISO-8601) |
| GET | `/api/sessions/{sessionId}` | Get session detail |
| GET | `/api/sessions/{sessionId}/messages` | Message history with cursor pagination. Query: `limit`, `cursor` (message ID), `direction` (asc/desc) |
| GET | `/api/sessions/{sessionId}/stats` | Session statistics (message counts, turns, avg reply length, duration, etc.) |
| DELETE | `/api/sessions/{sessionId}` | Close a session (messages preserved in DB) |
| POST | `/api/knowledge/upload` | Upload file for knowledge base ingestion (multipart: `file`, `collection`) |
| GET | `/api/knowledge/{collection}` | List documents in a collection |
| DELETE | `/api/knowledge/{collection}` | Delete all documents in a collection |
| DELETE | `/api/knowledge/{collection}/{documentId}` | Delete a specific document |
| GET | `/api/trace/{id}` | Get trace by ID |
| GET | `/api/traces` | List recent traces (query param: `limit`) |
| GET | `/api/health` | Health check |

**Chat SSE events:** Blocking mode: `event: done` (full result JSON), `event: error` (error JSON). Streaming mode (`context.outputMode=streaming`): `event: start` (sessionId), `event: token` (partial text), `event: step` (ReAct step info), `event: done` (final result), `event: error`. JWT mode returns refreshed token in `X-New-Token` header when remaining lifetime < threshold.

## Dependencies

- LangChain4j 1.15.0 (BOM-managed)
- Jackson 2.16.1 (JSON)
- OkHttp 4.12.0 (HTTP client for voice/API calls/rerank/web search)
- Javalin 6.1.3 (HTTP server)
- JJWT 0.12.5 (JWT creation/verification, harness-input)
- dotenv-java 2.3.2 (.env file auto-loading, harness-env)
- Apache PDFBox 2.0.32 (PDF text extraction, harness-input)
- Apache POI 5.2.5 (DOCX/XLSX parsing, harness-input)
- SQLite/MySQL/PostgreSQL JDBC drivers
- SLF4J 2.0.16 + Logback 1.5.18
