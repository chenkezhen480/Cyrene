# Cyrene Agent

A Java-based AI Agent application framework built on the **Harness architecture pattern**. Pluggable model providers, built-in RAG knowledge base, session memory, and a 5-layer pipeline architecture — designed as a scaffolding to bootstrap and customize agent applications for your specific business needs.

## Why Cyrene Agent?

Building an AI agent for your product shouldn't mean starting from scratch. Cyrene Agent provides a production-ready foundation — model abstraction, RAG, memory, tools, audit — so you focus on your business logic, not plumbing. Add custom tools, configure your models, and ship.

### Harness Architecture

Cyrene Agent adopts the **Harness architecture** — a design pattern where a general-purpose orchestration framework (the "harness") wraps around domain-specific components. The harness handles cross-cutting concerns (model routing, memory, audit, tool execution) while your custom logic plugs in at defined extension points. This separation means you can swap models, add tools, or change memory strategies without touching the pipeline core.

## Highlights

### 5-Layer Pipeline Architecture

```
Input → Session Lifecycle → Preprocess → ReAct Loop (AI ↔ Tool) → Post-process → Audit
```

Every request flows through a structured pipeline with full observability — each layer is independently logged, traced, and configurable.

### 6 Independent Model Types

Each model type is independently configurable with its own provider, API key, and endpoint:

| Type | Purpose | Providers |
|------|---------|-----------|
| Chat | Conversation + tool calling | OpenAI, Anthropic, Ollama, DashScope |
| Vision | Image/video understanding | OpenAI, Anthropic |
| Voice | Speech recognition + synthesis | OpenAI |
| Embedding | Multimodal vectorization | OpenAI, Ollama, DashScope |
| Rerank | Retrieval result reranking | OpenAI-compatible |
| Realtime | Realtime multimodal (reserved) | — |

Mix and match providers freely — use DashScope for chat, OpenAI for embedding, and a local Ollama for rerank.

### Built-in RAG Knowledge Base

Upload documents (PDF, DOCX, XLSX, TXT, Markdown) via API. Automatic text extraction, chunking, embedding, and storage in PostgreSQL pgvector. Semantic context retrieval with lookback for truncated chunks, plus optional rerank for precision.

### Session Memory with Intelligent Compression

- **Short-term memory**: LRU-cached conversation history per session
- **Long-term memory**: AI-extracted user preferences from completed sessions
- **Minor compression**: Strips tool call blocks in ReAct loops (code-based, zero cost)
- **Major compression**: AI-driven intelligent message extraction when context window fills up (time-decay weighted)

### Multimodal Fallback

When the chat model lacks vision/audio capabilities, `FallbackChatModel` transparently routes to the appropriate Vision or Voice provider — no code changes needed.

### ReAct Engine with Inspection

Tool-calling loop with per-step inspection (PASS / TOOL_ERROR), configurable stop-on-error behavior, and automatic context trimming for long tool interactions.

### MCP Remote Tool Support

Register external tools via MCP (Model Context Protocol) over HTTP. Configure servers in environment variables, call remote tools as if they were local.

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+
- PostgreSQL with pgvector extension (for RAG)
- MySQL 8+ (for audit + memory, optional)

### Build

```bash
# Build all modules (produces fat JARs for CLI and server)
mvn clean package -DskipTests
```

### Configure

```bash
cp .env.example .env
# Edit .env — set at minimum:
#   HARNESS_MODEL_CHAT_PROVIDER + HARNESS_MODEL_CHAT_API_KEY
```

### Run

```bash
# Start HTTP server (default port 8080)
export $(cat .env | xargs)
java -jar harness-server/target/harness-server-0.1.0-SNAPSHOT.jar
```

> **Note**: CLI mode (`harness-cli`) is currently not available. Please use the HTTP server mode.

### Test

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{"text":"Hello, what can you do?"}'
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat` | Send a message, get agent response |
| `POST` | `/api/knowledge/upload` | Upload document for RAG knowledge base (multipart) |
| `POST` | `/api/auth/token` | Get JWT token (when auth mode = jwt) |
| `GET` | `/api/health` | Health check |

### Chat Request

```json
{
  "text": "What is the asset management system?",
  "attachments": []
}
```

### Chat Response

```json
{
  "output": "...",
  "riskLevel": "LOW",
  "traceId": "uuid",
  "steps": 1,
  "sessionId": "abc123"
}
```

### Knowledge Upload

```bash
curl -X POST http://localhost:8080/api/knowledge/upload \
  -F "file=@document.pdf" \
  -F "collection=default"
```

## Architecture

```
harness-env           ← Foundation: all HARNESS_* env var access via EnvConfig
harness-core          ← Models: AgentMessage, AgentTrace, ReActStep, ToolSpec, etc.
    ├── harness-input        ← Auth (JWT) + multimodal parsing + text extraction + chunking
    ├── harness-preprocess   ← RAG retrieval + semantic context + rerank + context injection
    ├── harness-tool         ← Tool interface, registry, executor, MCP adapter
    ├── harness-audit        ← TraceCollector + TraceStore (MySQL/SQLite/file)
    └── harness-ai           ← LangChain4j integration, 6 model providers, ReActEngine
harness-agent         ← AgentOrchestrator (wires all layers together)
harness-server        ← HTTP API server (Javalin)
harness-cli           ← CLI entry point (currently unavailable)
```

## Configuration

All configuration is via environment variables prefixed with `HARNESS_`. See [.env.example](.env.example) for the full list with descriptions.

Key configuration groups:

| Group | Variables | Description |
|-------|-----------|-------------|
| Model | `HARNESS_MODEL_CHAT_*` | Chat model provider, API key, endpoint |
| Server | `HARNESS_SERVER_*` | Host, port, workers |
| Auth | `HARNESS_AUTH_MODE` | `none` or `jwt` |
| RAG | `HARNESS_RAG_PG_*` | PostgreSQL pgvector connection |
| Memory | `HARNESS_MEMORY_STORE` | `mysql`, `sqlite`, or `none` |
| Tools | `HARNESS_TOOL_*` | Enable/disable built-in tools |

## Current Limitations

- **CLI mode not available** — The `harness-cli` module is under development. Use the HTTP server instead.
- **Web search tool is a placeholder** — `WebSearchTool` returns stub responses. Real API integration (DuckDuckGo, SerpAPI, Tavily) is pending.
- **MCP tool registration incomplete** — Server config parsing works, but tool discovery via MCP protocol is not yet implemented.
- **Trace query API not wired** — `GET /api/trace/{id}` and `GET /api/traces` return placeholder responses.

See the issue tracker for the full list of planned features.

## Tech Stack

- **Java 17** + Maven multi-module
- **LangChain4j 1.15.0** — LLM integration, tool specifications, chat models
- **Javalin 6.1.3** — HTTP server
- **PostgreSQL + pgvector** — RAG vector storage
- **MySQL** — Audit traces + session memory
- **Jackson** — JSON serialization
- **OkHttp** — HTTP client for API calls
- **Apache POI + PDFBox** — Document text extraction (PDF, DOCX, XLSX, DOC)
- **SLF4J + Logback** — Logging
- **jjwt** — JWT authentication

## License

Apache License 2.0
