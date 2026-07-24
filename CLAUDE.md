# CLAUDE.md

Guidance for Claude Code when working in the Cyrene Agent repository.

## Build & Run

```bash
mvn clean package                          # fat JAR for server
mvn clean package -pl harness-core -am     # single module
mvn clean compile                          # compile only
mvn test                                   # all tests
mvn test -pl harness-tool                  # single module
mvn test -Dtest=ClassName                  # single class
java -jar harness-server/target/harness-server-${revision}.jar   # run server (Javalin, port 8080)
```

Before running: copy `.env.example` → `.env`. Minimum required:
- `HARNESS_MODEL_CHAT_PROVIDER` + `HARNESS_MODEL_CHAT_API_KEY`
- Auto-loaded by `EnvConfig` from working directory; system env vars take precedence.

### Docker Compose Deployment

```bash
cp .env.example .env   # fill in API keys
cd docker && docker compose up -d   # one-click deploy
```

Services: `cyrene` (app), `mysql`, `milvus`, `redis`, `searxng`. All images auto-pulled. Sandbox (`docker/sandbox/`) is separate — spawned dynamically by `PythonSandboxTool` via `docker run`.

## Architecture

5-layer pipeline orchestrated by `AgentOrchestrator`:

```
Input → Session Lifecycle → Preprocess → ReAct Loop (AI ↔ Tool ↔ Inspection) → Post-process → Audit
```

**Module dependency graph (bottom-up):**
```
harness-env        ← env vars (EnvConfig) + connection pools (HikariCP)
harness-core       ← models (AgentMessage, AgentTrace, ReActStep, ToolSpec, etc.)
├── harness-input       ← auth, multimodal parsing, text extraction, chunking, large file parsing
├── harness-preprocess  ← RAG (VectorStore), semantic context, rerank, context injection, memory
├── harness-tool        ← Tool interface, ToolRegistry, ToolExecutor, MCP adapter, built-in tools
├── harness-audit       ← TraceCollector + TraceStore (sqlite/mysql/file/none)
└── harness-ai          ← LangChain4j, 7 model types, ModelProviderFactory, ReActEngine
harness-agent      ← AgentOrchestrator, SubAgentManager, ProjectDiscoveryService
harness-server     ← HTTP API (Javalin) + Web UI (static resources)
```

Cross-module deps: `harness-ai` → `harness-tool`; `harness-input`/`harness-preprocess` → `harness-ai`; `harness-preprocess` → `harness-input`; `harness-server` → `harness-agent`.

## Key Conventions

- **All config via env vars.** Every `HARNESS_*` key in `EnvKey.java`. Never hardcode — use `EnvConfig.get()`.
- **7 model types**, independently configurable. Each has a provider interface in `com.harness.ai.model` + impls in `com.harness.ai.model.impl`. NoOp when unconfigured.

  | # | Type | Interface | Providers |
  |---|------|-----------|-----------|
  | 1 | Chat (required) | `ChatModelProvider` | openai, anthropic, ollama |
  | 2 | Vision | `VisionModelProvider` | openai, anthropic |
  | 3 | Voice (ASR+TTS) | `VoiceModelProvider` | openai |
  | 4 | Embedding | `EmbeddingModelProvider` | openai, ollama |
  | 5 | Rerank | `RerankModelProvider` | openai |
  | 6 | Realtime | `RealtimeModelProvider` | (reserved) |
  | 7 | Classifier | `ClassifierModelProvider` | openai (GapAnalyzer Tier 2) |

- **Tools** implement `com.harness.tool.Tool` — `spec()` → ToolSpec, `execute(JsonNode)` → String. Register in ToolRegistry. MCP tools via McpToolAdapter.
- **ReActEngine** — LangChain4j 1.15.0 `ChatModel.chat(ChatRequest)` with `ToolSpecification`. Wraps in `FallbackChatModel` (multimodal fallback) + `SemaphoreChatModel` (concurrency, `HARNESS_MODEL_API_MAX_CONCURRENT` default 10). Streaming via `StreamingChatModel.streamExecute()`. No intra-iteration retry — errors returned to LLM. `HARNESS_REACT_MAX_TOOL_RETRIES` (default 3) stops loop after consecutive TOOL_ERRORs. Timeout: `HARNESS_MODEL_CHAT_TIMEOUT_SECONDS` (default 300s).
- **Thinking Mode** — `HARNESS_MODEL_CHAT_THINKING` (default `true`). `chatModelNoThinking()` for cost-sensitive calls (LargeFileParser, query rewriting). Per-request override via `AgentContext.enableThinking()`.
- **File Injection** — Small files (< `HARNESS_INPUT_FILE_SIZE_THRESHOLD_KB`, default 100KB) extracted directly; large files via `LargeFileParser` merge-then-summarize.
- **ReAct Inspection** — Each tool call inspected by `Inspector` (no LLM). Two-layer detection: (1) explicit `ToolResult.ResultStatus` (EMPTY/LOW_RELEVANCE/SUCCESS) set by internal tools via ThreadLocal — authoritative, zero guessing; (2) heuristic length/phrase fallback for external MCP tools. States: PASS / TOOL_ERROR / WRONG_TOOL / INSUFFICIENT / LOOP_DETECTED. Non-PASS states inject hints for next iteration.
- **KnowledgeBaseTool** — RAG retrieval as a ReAct tool (not pre-fetched). Model only sees `query` parameter; rewrite decision is invisible. Internal logic: fast path (single query) on first call → auto-upgrade to combined rewrite (5 queries: 3 multi-query + 1 step-back + 1 hyde) when Inspector reports INSUFFICIENT. Uses `ReActStep.ThreadLocal` to read inspection history. `ToolResult.ResultStatus` declares EMPTY (0 results), LOW_RELEVANCE (topScore < threshold), or SUCCESS.
- **ReAct Reflection** — Periodic reflection prompts every `HARNESS_REACT_REFLECTION_INTERVAL` (default 3) iterations. Loop detection at `HARNESS_REACT_LOOP_DETECTION_THRESHOLD` (default 3) consecutive identical calls.
- **GapAnalyzer** — 3-tier routing funnel for `needsThinking`/`needsKnowledgeBase`/`needsWebSearch`. Tier 0: explicit (AgentContext) → Tier 1: rule engine (regex/keywords, <1ms) → Tier 2: LLM classification (ClassifierModelProvider). `HARNESS_GAP_ANALYSIS_ENABLED` (default `true`). Tier 2 failure degrades to env defaults. `needsWebSearch=true` injects a system prompt constraint forcing the LLM to call `web_search` before answering. `needsKnowledgeBase=true` injects a constraint forcing the LLM to call `knowledge_base_search` first.
- **Streaming** — `AgentOrchestrator.streamRun()` via `StreamCallback` pushing `StreamEvent` (START/TOKEN/STEP/TOOL_CALL_START/COMPRESS/ARTIFACT/DONE/CANCELLED/ERROR). `CancellationToken` supports polling + thread interrupt.
- **Sub-Agent** — `SubAgentManager` manages lifecycle with per-run isolation (`SubAgentRunScope`). LLM spawns via `spawn_subagent` tool (persona, system_prompt, context, optional tools whitelist). Results delivered inline via `await_subagents` (shared 120s deadline) or auto-resume session on timeout. CAS-based delivery state prevents duplicate delivery. `HARNESS_AGENT_MAX_SUBAGENTS` (default 3), `HARNESS_AGENT_AWAIT_TIMEOUT_SECONDS` (default 120).
- **Web Search** — `WebSearchTool` backed by self-hosted SearXNG (aggregates 70+ search engines, no API key). Endpoint via `HARNESS_TOOL_WEB_SEARCH_SEARXNG_URL`. `needsWebSearch=true` from GapAnalyzer forces the LLM to call `web_search` before answering (system prompt injection).
- **MCP Discovery** — `McpToolDiscovery` via JSON-RPC `tools/list`. Config: `HARNESS_MCP_CONFIG_FILE` (JSON). No config file → MCP disabled with info log.
- **Retry & Robustness** — 6 layers: Tool (single attempt, error→LLM adjusts), LLM API (`RetryingChatModel`, exponential backoff 1s→2s→4s, max 3), MCP reconnect (1 retry), message write (3 retries → sync → dead letter queue), refinement stuck recovery (CAS reset), knowledge batch rollback (pgvector transaction, Milvus batch insert).
- **JWT refresh** — Auto-refresh when remaining < `HARNESS_AUTH_JWT_REFRESH_THRESHOLD_MINUTES` (default 60). New token via `X-New-Token` header.
- **Audit** — TraceCollector per run → TraceStore (sqlite/mysql/file/none). Cleanup: `HARNESS_AUDIT_RETENTION_DAYS` (default 30). `HARNESS_SERVER_WORKERS` (default `availableProcessors * 2`).
- **Skill Loading** — 3-layer: System prompt → Skill (Markdown + YAML frontmatter) → MCP/builtin tools. Scan `HARNESS_SKILL_DIR` (default `./skills`) at startup. LLM loads via `load_skill` tool (full text or regex search). Minor compression preserves skill content; major compression re-injects from cache. Key classes: `SkillLoader`, `SkillRegistry`, `LoadSkillTool`.
- **ReplyAuditor** — Async heuristic quality audit (no LLM). Checks reply length, error indicators, tool residue. Results in trace metadata.

## Subsystems

### Large File Parsing (harness-input)
Files > `HARNESS_INPUT_FILE_SIZE_THRESHOLD_KB` (default 100KB) parsed via merge-then-summarize:

```
TextExtractorRegistry.extract() → TextChunker.split() (semantic boundaries)
  → greedy merge until contextWindow × LARGE_FILE_CONTEXT_RATIO (default 40%)
  → parallel summarize (noThinkingModel, concurrency = LARGE_FILE_SUMMARY_CONCURRENCY, default 3)
  → final merge
```

Context window auto-detected from model name (Claude→200K, GPT-4→128K, Gemini→1M, Qwen→128K, DeepSeek→64K, o3→200K). Override via `HARNESS_MODEL_CHAT_CONTEXT_WINDOW`.

Key env vars: `HARNESS_MODEL_CHAT_CONTEXT_WINDOW`, `HARNESS_LARGE_FILE_CONTEXT_RATIO` (0.4), `HARNESS_LARGE_FILE_SUMMARY_CONCURRENCY` (3), `HARNESS_INPUT_CHUNK_TOKEN_SIZE` (1024).

### Multimodal Fallback (harness-ai)
`FallbackChatModel` wraps ChatModel. ImageContent/AudioContent → transparent fallback to VisionModelProvider/VoiceModelProvider when model lacks capability (checked via `ModalCapabilityRegistry`). Override via `HARNESS_MODEL_CHAT_CAPABILITIES`.

### Semantic Context Retrieval (harness-preprocess)
`SemanticContextRetriever` checks chunk completeness via heuristics (no LLM). Truncated chunks trigger lookback via `prev_chunk_id`. Detection: terminal punctuation → structural endings → continuation words (35 Chinese, 27 English). Max lookback: `HARNESS_RAG_CONTEXT_LOOKBACK_MAX` (default 2).

### Rerank (harness-ai + harness-preprocess)
`OpenAiRerankModelProvider` → OpenAI-compatible `/rerank`. Pipeline: RAG → semantic enhancement → rerank → format.

- `HARNESS_RERANK_ENABLED` (default `false`)
- `HARNESS_RERANK_TOP_N` (default `3`)

### Query Rewriting (harness-preprocess)
`QueryRewriter` interface. `HARNESS_RAG_QUERY_REWRITE` (default `none`).

| Strategy | Value | Behavior |
|----------|-------|----------|
| NoOp | `none` | Pass-through |
| HyDE | `hyde` | Hypothetical answer as query |
| Multi-Query | `multi-query` | N alternatives (`HARNESS_RAG_QUERY_REWRITE_COUNT`, default 3), merge by ID |
| Step-Back | `step-back` | More general version |

All use `chatModelNoThinking()`. LLM failure → original query.

### Unified Vector Store (harness-preprocess)
`VectorStore` interface: `retrieve(query, topK)`, `insertBatch(docs)`, `deleteByFile(fileId)`.

| Provider | Env | Backend |
|----------|-----|---------|
| MilvusVectorStore | `HARNESS_RAG_PROVIDER=milvus` **(default)** | Milvus BM25 sparse + dense hybrid |
| PgVectorStore | `HARNESS_RAG_PROVIDER=pgvector` | pgvector cosine + optional fulltext |

Key env vars: `HARNESS_RAG_PROVIDER` (milvus), `HARNESS_RAG_URL` (Milvus: `http://host:19530`, PG: `jdbc:postgresql://...`), `HARNESS_RAG_DATABASE`, `HARNESS_RAG_USER`, `HARNESS_RAG_PASS`, `HARNESS_RAG_API_KEY`, `HARNESS_RAG_COLLECTION`, `HARNESS_MODEL_EMBEDDING_DIM` (1024), `HARNESS_RAG_TOP_K` (5), `HARNESS_RAG_SCORE_THRESHOLD` (0.7, also used as LOW_RELEVANCE threshold), `HARNESS_RAG_BM25_WEIGHT` (0.3, Milvus), `HARNESS_RAG_LANG` (english), `HARNESS_RAG_MILVUS_METRIC_TYPE` (COSINE).

> Deprecated: `HARNESS_RAG_PG_*`, `HARNESS_RAG_MULTI_ROUTE`, `HARNESS_RAG_FULLTEXT_ENABLED`.

Fulltext requires `content_tsv` tsvector + GIN index (see `sql/schema-pgvector.sql`).

### Memory & Context Management (harness-preprocess + harness-server)
Session-based memory with short-term and long-term subsystems. `HARNESS_AUDIT_STORE=mysql` (default `none`).

**Context allocation:** Short-term + RAG included as-is. When total > `HARNESS_CTX_COMPRESS_MAJOR` (default 85%), only short-term messages compressed. Long-term in system prompt, capped at `HARNESS_MEMORY_LONGTERM_MAX_TOKENS` (default 800). Base prompt: `HARNESS_SYSTEM_PROMPT`.

**Request flow:**
```
Input → Session Lifecycle (timeout + resolution + title) → parallel load (short-term + long-term + RAG)
  → Preprocess (system msg assembly, major compression if >85%, save user msg)
  → ReAct Loop (AI ↔ Tool, minor compression after iteration > HARNESS_CTX_COMPRESS_MINOR)
    → RAG retrieval now happens inside ReAct via KnowledgeBaseTool (not pre-fetched)
  → Post-process (ReplyAuditor, save reply, cache sync)
  → Audit (TraceCollector → TraceStore)
```

**Key classes:** `SessionStore`/`MessageStore`/`PreferenceStore` (interfaces), `Mysql*Store` (impls, shared `MysqlConnectionPool`), `NoOp*Store`, `MemoryStoreFactory`, `SessionLifecycleManager`, `SessionMessageCache`, `MemoryCompressor`, `PreferenceRefinementWorker`, `SessionCleanupScheduler`, `MessageWriteWorker`.

**Compression:**
- **Minor (code-based, ReAct layer):** `stripToolMessages()` — removes ToolExecutionResultMessage + tool AiMessage when iteration > `HARNESS_CTX_COMPRESS_MINOR` (default 2). `load_skill` results preserved. Toggle: `HARNESS_CTX_COMPRESS_MINOR_ENABLED`.
- **Major (AI-based, preprocess layer):** `compressIfNeeded()` when context > `HARNESS_CTX_COMPRESS_MAJOR` (default 85%). Time-decay weighting (RECENT/MIDDLE/OLD). Target: `HARNESS_CTX_COMPRESS_MAJOR_TARGET` (default 30%). Original messages never deleted, only `is_summary=true` rows added.

**Cache (per-user + global LRU eviction):**
- Read: cache hit → return; miss → MessageStore → fill cache
- Write: MessageWriteWorker async (DB) + cache.append sync (memory)
- Eviction (4 layers): per-user session count (default 10) → per-user memory (default 2MB) → global memory (default 4GB, evict to 50%) → session TTL (default 12h idle)
- Cross-cache: onEvict triggers `skillRegistry.clearSession()`
- Redis: `HARNESS_MEMORY_REDIS_URL` enables distributed cache (Jedis pool, auto-fallback to cache-miss). Keys: `{prefix}:msg:{sessionId}`, `{prefix}:meta:{sessionId}`, `{prefix}:user_sessions:{userId}`, `{prefix}:access` (ZSET), counters.

**Performance:** HikariCP pools (MySQL, PgVector, Milvus), parallel memory/RAG loading (`CompletableFuture.allOf`), consolidated `loadSessionStats()` (single GROUP BY).

### Rate Limiting (harness-ai)
`SemaphoreChatModel`/`SemaphoreStreamingChatModel` — Java Semaphore concurrency control. `HARNESS_MODEL_API_MAX_CONCURRENT` (default 10).

### Log Storage (harness-server)
`LogBufferAppender` (Logback) buffers WARN/ERROR events in memory. `LogStorageService` flushes to local files every 1 hour + on shutdown. Auto-cleans files older than retention period.

- `HARNESS_LOG_STORAGE_DIR` (default `./logs`)
- `HARNESS_LOG_RETENTION_DAYS` (default `7`)
- File format: `warn-errors-{date}.log`

Key classes: `LogBufferAppender`, `LogStorageService`.

### Knowledge Base (harness-input + harness-preprocess + harness-server)
Upload via `POST /api/knowledge/upload`. Flow:

```
File → TextExtractorRegistry → TextChunker.split → EmbeddingModelProvider.embedAll → FileStorageService + VectorStore.insertBatch
```

Formats: TXT, MD, CSV, JSON, XML (PlainTextExtractor), PDF (PDFBox), DOCX/XLSX (POI). Chunks linked with `prev_chunk_id`/`next_chunk_id`.

Key classes: `TextExtractor` + `TextExtractorRegistry`, `TextChunker`, `KnowledgeIngestService`, `FileStorageService`, `KnowledgeUploadHandler`, `KnowledgeManagementHandler`.

### Sub-Agent (harness-agent)
`SubAgentManager` — per-run scoped task management. LLM spawns via `spawn_subagent` with persona, system_prompt, context (compressed history), and optional tools whitelist. Each sub-agent: independent ReActEngine + filtered ToolRegistry (`FilteredToolRegistry.forTask()` or `.forNoTools()`). Results delivered inline via `await_subagents` or auto-resume session via `SessionInbox` + `SessionResumeDispatcher` on timeout.

**Key design:**
- Per-run scope isolation (`SubAgentRunScope` keyed by runId, not sessionId)
- CAS-based state transitions (`SubAgentStatus`, `ResultDeliveryState`)
- 120s shared await deadline (not per-task)
- `SubAgentToolHelper` — shared parsing/validation for all sub-agent tools

Key classes: `SubAgentManager`, `SubAgentRunScope`, `SubAgentTaskRecord`, `SubAgentTask`, `SubAgentResult`, `SpawnSubAgentTool`, `AwaitSubAgentsTool`, `GetSubAgentsTool`, `CancelSubAgentsTool`, `SubAgentToolHelper`, `FilteredToolRegistry`, `SessionInbox`, `SessionResumeDispatcher`.

### Skill Loading (harness-tool + harness-agent)
Markdown + YAML frontmatter. Two-phase: startup scan (name+description index) → on-demand full load via `load_skill` tool.

Key classes: `SkillLoader`, `SkillRegistry`, `LoadSkillTool`.

### Artifact System (harness-core + harness-tool + harness-preprocess + harness-server)
`ArtifactProducingTool` marker interface. Lifecycle: tool produces bytes → `ArtifactStorer.store()` → markdown link in response → `ReActEngine.parseArtifacts()` → SSE artifact event → frontend inline render.

Key classes: `Artifact`, `ArtifactProducingTool`, `CancellableTool`, `ArtifactStorageService`, `FilesystemArtifactStore`, `ArtifactHandler`. Built-in: `ImageGenerationTool`, `VideoGenerationTool`, `PythonSandboxTool`.

### File Upload & Image-to-Image
Upload: `POST /api/files/upload` → `{HARNESS_KNOWLEDGE_UPLOAD_DIR}/input/`. Flow: frontend uploads → URL in `context.File` → injected into `enhancedText` → LLM uses `reference_image` in `image_generation` tool.

Env vars: `HARNESS_TOOL_IMAGE_GEN_*`, `HARNESS_TOOL_VIDEO_GEN_*`.

### Project API Discovery (harness-agent + harness-tool)
Auto-scan project for REST APIs. `HARNESS_PROJECT_DISCOVERY_ENABLED` (default `true`).

Tools (also available in normal chat): `code_glob`, `code_grep`, `read_class_hierarchy` (Java/C#/C++/Python/JS/TS/PHP/Rust/Go), `update_project_api`.

LLM-guided flow: glob Controllers → grep route annotations → read DTO/VO hierarchy → structured API definitions → `project-apis.json`.

Key classes: `ProjectDiscoveryService`, `CodeGlobTool`, `CodeGrepTool`, `ReadClassHierarchyTool`, `ClassHierarchyReader`, `OpenApiSpecParser`.

## Extension Guide

### Adding a New LLM Provider
1. Create `YourProvider implements ChatModelProvider` in `com.harness.ai.model.impl`
2. Add case in `ModelProviderFactory.createChat()`
3. Add env keys in `EnvKey.java` (`HARNESS_MODEL_CHAT_*` pattern)

Same pattern for Vision/Voice/Embedding/Rerank/Classifier.

### Adding a New Tool
1. Implement `com.harness.tool.Tool` (`spec()` + `execute()`)
2. Register in `AgentOrchestrator.registerBuiltinTools()` or use `McpToolDiscovery`

## HTTP Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/token` | JWT token (userId + password) |
| POST | `/api/chat` | Send message (SSE stream). Body: `systemPrompt`, `context` (JSON: `outputMode`, `userId`, `enableThinking`, `File`). Header: `X-Session-Id` |
| DELETE | `/api/chat/{sessionId}` | Cancel in-progress chat |
| POST | `/api/sessions` | Create session (body: `userId`, optional `title`) |
| GET | `/api/sessions` | List sessions (cursor pagination: `userId`, `status`, `limit`, `cursor`) |
| GET | `/api/sessions/{sessionId}` | Session detail |
| GET | `/api/sessions/{sessionId}/messages` | Message history (cursor: `limit`, `cursor`, `direction`) |
| GET | `/api/sessions/{sessionId}/stats` | Session statistics |
| DELETE | `/api/sessions/{sessionId}` | Delete session + messages |
| POST | `/api/files/upload` | Upload file (multipart: `file`). Returns `{url, name, size}` |
| POST | `/api/knowledge/upload` | Knowledge base ingest (multipart: `file`, `collection`) |
| GET | `/api/knowledge/{collection}` | List collection documents |
| DELETE | `/api/knowledge/{collection}` | Delete collection |
| DELETE | `/api/knowledge/{collection}/{documentId}` | Delete document |
| GET | `/api/trace/{id}` | Get trace |
| GET | `/api/traces` | List traces (`limit`) |
| GET | `/api/traces/stats` | Trace count + retention |
| DELETE | `/api/traces/cleanup` | Manual cleanup |
| DELETE | `/api/traces/{traceId}` | Delete trace |
| POST | `/api/project-discovery/scan` | Trigger API discovery |
| GET | `/api/project-discovery/config` | Get project-apis.json |
| PUT | `/api/project-discovery/config` | Update project-apis.json |
| POST | `/api/project-discovery/reload` | Hot-reload APIs |
| GET | `/api/health` | Health check |
| GET | `/api/artifacts/{id}` | Download artifact |
| GET | `/api/artifacts/{id}/preview` | Inline preview |

**SSE events (streaming):** `start` (sessionId), `token` (partial text), `step` (ReAct step), `tool_call_created` (tool queued, name + args), `tool_call_start` (tool executing), `tool_call_done` (tool result), `artifact` (media), `done` (final), `cancelled` (user cancel, keeps partial), `error`.

**Cancellation:** `CancellableHttpClient` (ServiceLoader) wraps JDK HttpClient for SSE cancel. `CancellationToken` registered in `ChatHandler`. Tools with `CancellableTool` interface auto-register/unregister during execution.

## Web UI (harness-server/src/main/resources/public)

Vue 3 SPA with i18n (zh/en). Features: session sidebar, streaming output with cancel, tool call blocks with loading animation, inline artifact rendering, markdown rendering (`marked`), knowledge management, project API discovery.

Key files: `js/app.js`, `js/api.js`, `js/i18n.js`, `css/style.css`.
