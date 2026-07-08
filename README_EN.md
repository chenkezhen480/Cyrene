<p align="right"><a href="./README.md">中文</a></p>

# Cyrene Agent

A Java AI Agent application development framework built on the **Harness orchestration architecture**. Provides pluggable model providers, built-in RAG knowledge base, session memory, and a 5-layer pipeline orchestration — use it as a scaffold to rapidly build and customize business-oriented Agent applications.    ---------1768576157@qq.com

## First Launch

To integrate with an AI application, we inevitably need to connect to existing systems — that's exactly what initialization is for. Cyrene Agent has built-in project API discovery capabilities that automatically scan your existing projects. On top of basic Glob and Grep, I've also added ClassHierarchy for recursive parent-class lookup of discovered classes, retrieving complete parameter structures. It identifies REST API endpoints and generates structured parameter schemas, giving the Agent the ability to interact with the host system.

**One-click project API discovery:**

> On first launch via the Web UI, simply specify the project directory to automatically scan all Controller endpoints and generate a `project-apis.json` configuration file. Supports mainstream frameworks like Spring Boot, Express, Flask, etc., with automatic DTO/VO class inheritance resolution.

![Initial Interface](docs/assets/init-scan.png)

![Scan Results](docs/assets/scan-result.png)

![API Details](docs/assets/api-detail.png)

![Callback Demo](docs/assets/call-back.png)

## Quickly Build an AI Agent for Your System

Building an AI Agent for your product doesn't have to start from scratch. Cyrene Agent provides production-ready foundational capabilities — tool configuration initialization, model abstraction, RAG, memory, tools, audit — so you can focus on business logic rather than plumbing. Configure models, register tools, and go live.

### Orchestration Architecture

Cyrene Agent uses the **Harness orchestration pattern**: a generic orchestration framework wraps domain components, with the framework handling cross-cutting concerns (model routing, memory, audit, tool execution) and business logic plugging in through predefined extension points. This means you can swap models, add tools, or adjust memory strategies without touching the pipeline core.

## Core Features

### 5-Layer Pipeline Architecture

```
Input → Session Lifecycle → Preprocess → ReAct Loop (AI ↔ Tool ↔ Inspection) → Post-process → Audit
```

Each request flows through a structured pipeline where each layer is independently observable, configurable, and traceable.

### 6 Independent Model Types

Each model type can be independently configured with its own provider, API key, and endpoint:

| Type | Purpose | Supported Providers |
|------|---------|---------------------|
| Chat | Conversation + tool calling | OpenAI, Anthropic, Ollama, DashScope, etc. |
| Vision | Image/video understanding | OpenAI, Anthropic |
| Voice | Speech recognition + synthesis | OpenAI |
| Embedding | Multimodal vectorization | OpenAI, Ollama |
| Rerank | Search result reranking | OpenAI-compatible APIs |
| Realtime | Real-time multimodal (reserved) | — |

Mix and match freely — e.g., Chat with DashScope, Embedding with OpenAI, Rerank with local Ollama.

### Built-in RAG Knowledge Base

Upload documents via API (PDF, DOCX, XLSX, TXT, Markdown, etc.) — automatic text extraction, semantic chunking, embedding, and storage in PostgreSQL pgvector.

**Complete RAG Pipeline:**

```
User Query
  │
  ▼
Query Rewriting (optional, pluggable via env vars)
  │  none       → Pass-through original query (default)
  │  hyde       → LLM generates hypothetical document as retrieval query (improves precision)
  │  multi-query → LLM generates multiple rephrased queries, retrieves for each, merges results (improves recall)
  │  step-back  → LLM generates a more general abstract query (for overly specific questions)
  │
  ▼
Multi-Route Retrieval (optional, pluggable via env vars)
  │  Semantic vector retrieval  → pgvector cosine similarity (default)
  │  Keyword full-text retrieval → PostgreSQL tsvector/tsquery (complementary to semantic)
  │  Knowledge graph retrieval  → Reserved for extension
  │  Multi-route results deduplicated by document ID (CompletableFuture parallel)
  │
  ▼
Semantic Context Enhancement
  │  Heuristic detection of truncated chunks (punctuation/structure/continuation words)
  │  Automatic prev_chunk lookback for context completion (max 2 rounds)
  │
  ▼
Rerank (optional)
  │  Cross-encoder fine-ranking, or sort by similarity score
  │
  ▼
Inject into System Prompt → LLM generates response using knowledge base context + conversation history
```

**Query Rewriting Strategy Comparison:**

| Strategy | Principle | Use Case |
|----------|-----------|----------|
| HyDE | LLM generates a "fake answer", uses it for vector retrieval | Short/abstract queries where direct embedding performs poorly |
| Multi-Query | LLM generates N rephrased queries, retrieves and merges for each | Same question with multiple expressions, single query has incomplete recall |
| Step-Back | LLM generates a more general version, first retrieves background knowledge | Overly specific questions with low direct retrieval hit rate |

**Large File Processing:** Files exceeding 100KB use a "semantic splitting → merge to 40% of model context → parallel summarization" strategy, compressing hundreds of LLM calls to single digits (~5-8 calls for a 1MB file). Context window size is auto-detected from model name.

### Session Memory & Intelligent Compression

- **Short-term memory**: Per-session LRU cache for conversation history, with distributed Redis cache support
- **Long-term memory**: AI-extracted user preferences from completed sessions, automatically injected into System Prompt
- **Minor compression**: Strip tool call blocks in ReAct loop (pure code, zero cost)
- **Major compression**: AI-powered intelligent distillation of old messages when context window approaches limit (time-decay weighted: RECENT / MIDDLE / OLD)

**Compression flow:** Compress old messages → rebuild cache from DB → then save current user message. The current user message is never compressed.

### Multimodal Fallback

When the Chat model lacks vision/audio capabilities, `FallbackChatModel` transparently routes to Vision or Voice providers — no business code changes needed.

### ReAct Engine & Inspection

Tool calling loop with per-step heuristic result checking (PASS / TOOL_ERROR / WRONG_TOOL / INSUFFICIENT / NEEDS_RETRY). Configurable error-stop behavior, with automatic context trimming during long tool interactions.

### Sub-Agent

LLM spawns sub-tasks via the `spawn_subagent` tool, supporting dependency resolution and parallel execution. Each sub-agent has its own independent ReActEngine instance.

### MCP Remote Tools

Register external tools via MCP (Model Context Protocol) HTTP. Supports JSON config files or environment variables, with automatic `tools/list` discovery and caching.

### Web Search

`WebSearchTool` supports a multi-engine fallback chain: Tavily → SerpAPI → DuckDuckGo. Engines without API keys are automatically skipped; DuckDuckGo is always available.

### Project API Discovery

Automatically scans existing projects to identify REST API endpoints and generate structured configurations. Discovery tools (`code_glob`, `code_grep`, `read_class_hierarchy`) are registered into the main toolset when `HARNESS_PROJECT_DISCOVERY_ENABLED=true` and can be used in regular conversations. `read_class_hierarchy` supports multi-language class structure parsing (Java/C#/C++/Python/JS/TS, etc.) with automatic `.git` detection for cross-module parent class lookup.

### Streaming Output & Cancellation

- **SSE streaming output**: With `context.outputMode=streaming`, real-time push of tokens, ReAct steps, and completion events
- **Request cancellation**: `DELETE /api/chat/{sessionId}` triggers `CancellationToken`, interrupting LLM calls, tool execution, and sub-agent threads
- **JWT sliding window refresh**: Auto-refreshes when token remaining lifetime falls below threshold; new token returned via `X-New-Token` response header

### Six-Layer Fault Tolerance

| Layer | Mechanism | Strategy |
|-------|-----------|----------|
| Tool call retry | Tool execution failure | Up to 3 retries, error results passed to LLM for decision |
| LLM API retry | 429/503/timeout | Exponential backoff (1s→2s→4s), up to 3 times |
| MCP disconnect recovery | IOException | Rebuild OkHttpClient and retry once |
| Message write retry | DB write failure | Retry 3 times → single sync write → dead letter queue |
| Stuck refinement recovery | Quality assessment stuck | Periodic scan, CAS reset to pending |
| Knowledge base batch rollback | Batch insert failure | Explicit transaction rollback + orphan file cleanup |

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.8+
- PostgreSQL + pgvector extension (RAG, optional)
- MySQL 8+ (Audit + session memory, optional)
- Redis (Distributed cache, optional)

### Build

```bash
# Build all modules (produces fat JARs for cli and server)
mvn clean package -DskipTests

# Run tests
mvn test

# Run integration tests (requires database)
mvn test -Pintegration
```

### Configuration

```bash
cp .env.example .env
# Edit .env, configure at minimum:
#   HARNESS_MODEL_CHAT_API_KEY
#   HARNESS_MODEL_CHAT_PROVIDER
#   HARNESS_MODEL_CHAT_BASE_URL
#   HARNESS_MODEL_CHAT_MODEL
```

`.env` is auto-loaded by `EnvConfig` from the working directory; system environment variables take precedence.

#### Environment Variable Categories

| Level | Variable | Description |
|-------|----------|-------------|
| **Required** | `HARNESS_MODEL_CHAT_API_KEY` | Chat model API key, required by all provider constructors |
| **Required** | `HARNESS_MODEL_CHAT_BASE_URL` | API endpoint, required for non-OpenAI providers (e.g., DashScope) |
| **Required** | `HARNESS_MODEL_CHAT_MODEL` | Model name, defaults to gpt-4o if not set |
| Feature-required | `HARNESS_SERVER_ENABLED` / `HARNESS_CLI_ENABLED` | At least one must be enabled |
| Feature-required | `HARNESS_AUTH_TOKEN` | Required when auth_mode=token |
| Feature-required | `HARNESS_RAG_PG_*` | Required for RAG knowledge base |
| Feature-required | `HARNESS_AUDIT_DB_*` | Required for audit persistence |
| Feature-required | `HARNESS_MODEL_EMBEDDING_*` | Required for knowledge base upload/retrieval |
| Optional | All other variables | Have reasonable defaults or can be disabled |

### Run

```bash
# Start HTTP server (default port 8080)
java -jar harness-server/target/harness-server-${revision}.jar
```

Windows users can place `.env` in the project root and run the `java -jar` command directly without manually exporting environment variables.

### Test

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{"text":"Hello, what can you do?"}'
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/auth/token` | Get JWT token (when auth mode is jwt) |
| `POST` | `/api/chat` | Send message, get Agent response (SSE stream) |
| `DELETE` | `/api/chat/{sessionId}` | Cancel an in-progress chat request |
| `POST` | `/api/sessions` | Create a new session |
| `GET` | `/api/sessions` | List sessions (cursor pagination, filterable by userId/status) |
| `GET` | `/api/sessions/{sessionId}` | Get session details |
| `GET` | `/api/sessions/{sessionId}/messages` | Get session message history |
| `GET` | `/api/sessions/{sessionId}/stats` | Get session statistics |
| `DELETE` | `/api/sessions/{sessionId}` | Close session (messages retained in DB) |
| `POST` | `/api/knowledge/upload` | Upload document to knowledge base (multipart) |
| `GET` | `/api/knowledge/{collection}` | List documents in collection |
| `DELETE` | `/api/knowledge/{collection}` | Delete all documents in collection |
| `DELETE` | `/api/knowledge/{collection}/{documentId}` | Delete specific document |
| `GET` | `/api/trace/{id}` | Get trace by ID |
| `GET` | `/api/traces` | List recent traces |
| `GET` | `/api/traces/stats` | Trace statistics and retention config |
| `DELETE` | `/api/traces/cleanup` | Manually cleanup expired traces |
| `DELETE` | `/api/traces/{traceId}` | Delete specific trace |
| `POST` | `/api/project-discovery/scan` | Trigger project API scan |
| `GET` | `/api/project-discovery/config` | Get API configuration |
| `PUT` | `/api/project-discovery/config` | Update API configuration |
| `POST` | `/api/project-discovery/reload` | Hot-reload API configuration |
| `GET` | `/api/health` | Health check |

### Chat Request Example

```json
{
  "text": "What is the asset management system?",
  "attachments": [],
  "context": {
    "outputMode": "streaming",
    "userId": "user-001",
    "enableThinking": true
  }
}
```

Request headers can include `X-Session-Id` to reuse a session. In JWT mode, when token remaining lifetime falls below threshold, a new token is returned via the `X-New-Token` response header.

### Chat Response Example (Blocking Mode)

```json
{
  "output": "...",
  "riskLevel": "LOW",
  "traceId": "uuid",
  "steps": 1,
  "sessionId": "abc123"
}
```

### SSE Events

- **Blocking mode**: `event: done` (full result JSON), `event: error` (error JSON)
- **Streaming mode** (`context.outputMode=streaming`): `event: start`, `event: token`, `event: step`, `event: done`, `event: error`

### Knowledge Base Upload

```bash
curl -X POST http://localhost:8080/api/knowledge/upload \
  -F "file=@document.pdf" \
  -F "collection=default"
```

## Module Architecture

```
harness-env           ← Foundation: all HARNESS_* env vars + HikariCP connection pools + Redis pool
harness-core          ← Core models: AgentMessage, AgentTrace, ReActStep, ToolSpec, etc.
    ├── harness-input        ← Authentication (JWT) + multimodal parsing + large file merge-summarize + text extraction + chunking
    ├── harness-preprocess   ← RAG query rewriting + multi-route retrieval + semantic context + Rerank + memory management
    ├── harness-tool         ← Tool interface, registry, executor, MCP adapter, Skill loading, code discovery tools
    ├── harness-audit        ← TraceCollector + TraceStore
    └── harness-ai           ← LangChain4j integration, 6 model types, ReActEngine, retry & fault tolerance
harness-agent         ← AgentOrchestrator (wires all layers) + sub-agent orchestration + project API discovery
harness-server        ← HTTP API entry point (Javalin, SSE streaming, Web UI)
```

## Configuration

All configuration is managed via `HARNESS_` prefixed environment variables. See [.env.example](.env.example) for the complete list.

| Config Group | Variables | Description |
|--------------|-----------|-------------|
| Models | `HARNESS_MODEL_CHAT_*`, etc. | 6 model types: provider, key, endpoint, timeout (default 300s), context window auto-detected |
| Server | `HARNESS_SERVER_*` | Host, port, worker threads |
| Auth | `HARNESS_AUTH_MODE` | `none` or `jwt` |
| RAG Core | `HARNESS_RAG_*` | Vector store backend (pgvector/milvus), connection, collection, TopK, similarity threshold |
| RAG Query Rewrite | `HARNESS_RAG_QUERY_REWRITE` | `none` / `hyde` / `multi-query` / `step-back` |
| Storage (memory+trace) | `HARNESS_AUDIT_STORE` | `mysql` / `sqlite` / `none` (default) |
| Cache | `HARNESS_MEMORY_REDIS_URL` | Set to enable Redis distributed cache (multi-instance deployment) |
| Compression | `HARNESS_CTX_COMPRESS_*` | Minor compression (ReAct layer) + major compression (AI layer) thresholds |
| Tools | `HARNESS_TOOL_*` | Built-in tool toggles and web search engine priority |
| MCP | `HARNESS_MCP_CONFIG_FILE` | MCP server JSON config file |
| API Discovery | `HARNESS_PROJECT_DISCOVERY_ENABLED` | Auto-scan project REST APIs; discovery tools usable in regular conversations |

## Database Schemas

- MySQL (excluding users): [`sql/schema-mysql.sql`](sql/schema-mysql.sql)
- MySQL users table: [`sql/schema-users-mysql.sql`](sql/schema-users-mysql.sql)
- PostgreSQL pgvector RAG: [`sql/schema-pgvector.sql`](sql/schema-pgvector.sql)
- Table comments: [`sql/add-comments.sql`](sql/add-comments.sql)

## Testing

```bash
# Unit tests (pure logic, no external dependencies)
mvn test

# Integration tests (requires MySQL + PostgreSQL + Redis)
mvn test -Pintegration

# Coverage report
mvn jacoco:report
```

Test framework: JUnit 5 + Mockito + AssertJ. Integration tests use the `@Tag("integration")` annotation and run via the `-Pintegration` profile.

## Extension Guide

### Adding a New LLM Provider

1. Implement the corresponding provider interface in `com.harness.ai.model.impl`
2. Register in `ModelProviderFactory`
3. Add environment variable keys in `EnvKey.java` (follow `HARNESS_MODEL_*` naming convention)

### Adding a New Tool

1. Implement `com.harness.tool.Tool` (`spec()` + `execute()`)
2. Register in `AgentOrchestrator.registerBuiltinTools()`, or use MCP auto-discovery

### Adding a New Query Rewriting Strategy

1. Implement `com.harness.preprocess.rag.rewrite.QueryRewriter` (`rewrite()` + `strategyName()`)
2. Register the new strategy name in `QueryRewriterFactory.create()`
3. Set `HARNESS_RAG_QUERY_REWRITE=your-strategy`

### Adding a New Retrieval Route

1. Implement `com.harness.preprocess.rag.route.RetrievalRoute` (`retrieve()` + `routeName()` + `isAvailable()`)
2. Add route creation logic in `RetrievalRouteFactory.createEnabledRoutes()`
3. Set `HARNESS_RAG_MULTI_ROUTE=true` to enable multi-route parallel retrieval

## Known Limitations

- **Realtime model**: Interface reserved, no provider implementation yet
- **Knowledge graph retrieval**: `RetrievalRoute` interface reserved, no backend implementation yet
- **API discovery**: Supports LLM-based scanning for projects without OpenAPI specs, but complex nested type resolution still has room for improvement

## Tech Stack

- **Java 21** + Maven multi-module
- **LangChain4j 1.15.0** — LLM integration, tool specifications, Chat Model
- **Javalin 6.1.3** — HTTP server (SSE streaming)
- **PostgreSQL + pgvector** — RAG vector storage + full-text search
- **MySQL + HikariCP** — Audit traces + session memory
- **Redis + Jedis** — Distributed session cache
- **Jackson** — JSON serialization
- **OkHttp** — HTTP client (voice/API/rerank/web search)
- **Apache POI + PDFBox** — Document text extraction (PDF, DOCX, XLSX)
- **dotenv-java** — `.env` file auto-loading
- **SLF4J + Logback** — Logging
- **JJWT** — JWT authentication

## License

Apache License 2.0
