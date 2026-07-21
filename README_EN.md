<p align="right"><a href="./README.md">中文</a></p>

# Cyrene Agent — Out-of-the-Box AI Application Framework

> **In one sentence: Point it at your project directory, and Cyrene Agent automatically scans REST APIs, generates tool schemas, and plugs them into the conversation — your AI Agent can now interact with your system, no hand-written integration code needed.**

A Java AI Agent framework built on the **Harness orchestration architecture**. No manual API wiring required — includes automatic project API discovery, 7 model types, RAG knowledge base, session memory, and autonomous decision routing, so developers focus on business logic rather than plumbing.

## Key Feature: One-Click Project API Integration

Traditional AI Agent integration with existing systems requires manually writing API call code for each endpoint. Cyrene Agent automates this entirely:

```
Specify project directory → Auto-scan Controllers → Parse DTO/VO inheritance → Generate project-apis.json → Tools auto-registered to Agent
```

**Result:** Start the service → specify the project directory in Web UI → scan completes → Agent can now call your project's APIs. Zero integration code written.

```
User: "Help me query all in-use devices from the asset management system"
Agent: [auto-calls the discovered GET /api/assets?status=in_use endpoint]
Agent: "Found 156 devices currently in use, here's the list..."
```

## Autonomous Decision Routing

Each request automatically determines which capabilities are needed, avoiding unnecessary costs:

| Scenario | Thinking | Knowledge Base | Web Search | Query Rewrite |
|----------|----------|----------------|------------|---------------|
| "Hello" | ✗ | ✗ | ✗ | ✗ |
| "What's our reimbursement policy?" | ✗ | ✓ | ✗ | ✓ (multi-query) |
| "What's the weather in Shanghai today?" | ✗ | ✗ | ✓ (SearXNG) | ✗ |
| "Analyze the feasibility of this requirement" | ✓ | ✓ | ✗ | ✗ |

**Three-tier funnel (priority descending):**

| Tier | Mechanism | Latency | Description |
|------|-----------|---------|-------------|
| Tier 0 | Explicit override | 0ms | Parameters specified in request context |
| Tier 1 | Rule engine | <1ms | Regex/keyword matching (greeting intercept, time-sensitive web search, etc.) |
| Tier 2 | LLM classification | ~200ms | Lightweight Classifier model analyzes remaining fields |

Enabled via `HARNESS_GAP_ANALYSIS_ENABLED=true`.

## Built-In Capabilities

### 7 Independent Model Types

Each model can be independently configured with its own provider, API key, and endpoint — mix and match freely:

| Type | Purpose | Providers |
|------|---------|-----------|
| Chat | Conversation + tool calling | OpenAI, Anthropic, Ollama, DashScope, etc. |
| Vision | Image/video understanding | OpenAI, Anthropic |
| Voice | Speech recognition + synthesis | OpenAI |
| Embedding | Vectorization | OpenAI, Ollama |
| Rerank | Search result reranking | OpenAI-compatible APIs |
| Classifier | Intent classification | OpenAI-compatible APIs |
| Realtime | Real-time multimodal (reserved) | — |

### RAG Knowledge Base

Upload documents (PDF, DOCX, XLSX, TXT, Markdown, etc.) → automatic extraction, chunking, embedding, and storage. Defaults to Milvus vector database; also supports PostgreSQL pgvector, switchable via `HARNESS_RAG_PROVIDER`.

Built-in query rewriting (HyDE / Multi-Query / Step-Back), semantic context enhancement, optional Rerank. Large files automatically use a "split → merge → parallel summarize" strategy — a 1MB file requires only 5-8 LLM calls.

### Session Memory

- **Short-term**: Per-session LRU cache with optional Redis distributed cache
- **Long-term**: AI-extracted user preferences from completed sessions, auto-injected into System Prompt
- **Smart compression**: Minor compression strips tool call blocks (zero cost); major compression AI-distills old messages with time-decay weighting

### 5-Layer Pipeline

```
Input → Session Lifecycle → Preprocess → ReAct Loop (AI ↔ Tool ↔ Inspection) → Post-process → Audit
```

Each layer is independently observable, configurable, and traceable. ReAct engine includes heuristic inspection (PASS / TOOL_ERROR / WRONG_TOOL / INSUFFICIENT), automatic retry on tool failure, and loop detection.

### Six-Layer Fault Tolerance

| Layer | Strategy |
|-------|----------|
| Tool call | Up to 3 retries, errors passed to LLM for decision |
| LLM API | Exponential backoff (1s→2s→4s), up to 3 times |
| MCP disconnect | Rebuild connection and retry once |
| Message write | Retry 3 times → sync write → dead letter queue |
| Stuck refinement | Periodic scan, CAS reset |
| Knowledge base batch | Transaction rollback + orphan file cleanup |

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.8+
- Docker + Docker Compose (recommended, one-click deploy all dependencies)
- Or manual install: Milvus 2.5+ (vector database, default), MySQL 8+ (session/memory/audit), Redis 7+ (distributed cache), SearXNG (web search)

### Docker Compose One-Click Deploy (Recommended)

```bash
cp .env.example .env   # edit .env, fill in LLM API key
cd docker && docker compose up -d
```

Auto-pulls and starts 5 services: `cyrene` (app), `mysql`, `milvus`, `redis`, `searxng`. MySQL auto-runs schema scripts on first start; Milvus collections are auto-created by the application.

### Manual Build & Run

```bash
# Build
mvn clean package -DskipTests

# Configure
cp .env.example .env
# Edit .env, configure at minimum:
#   HARNESS_MODEL_CHAT_API_KEY
#   HARNESS_MODEL_CHAT_PROVIDER
#   HARNESS_MODEL_CHAT_BASE_URL
#   HARNESS_MODEL_CHAT_MODEL

# Start (default port 8080)
java -jar harness-server/target/harness-server-${revision}.jar
```

After startup, visit the Web UI — it will guide you through the project API scan on first launch.

### Environment Variable Tiers

| Level | Variable | Description |
|-------|----------|-------------|
| **Required** | `HARNESS_MODEL_CHAT_API_KEY` | Chat model API key |
| **Required** | `HARNESS_MODEL_CHAT_BASE_URL` | API endpoint (required for non-OpenAI providers) |
| **Required** | `HARNESS_MODEL_CHAT_MODEL` | Model name (defaults to gpt-4o) |
| Feature-required | `HARNESS_SERVER_ENABLED` / `HARNESS_CLI_ENABLED` | At least one must be enabled |
| Feature-required | `HARNESS_AUTH_TOKEN` | Required when auth_mode=token |
| Feature-required | `HARNESS_RAG_*` | Required for RAG knowledge base |
| Feature-required | `HARNESS_AUDIT_DB_*` | Required for audit persistence |
| Feature-required | `HARNESS_MODEL_EMBEDDING_*` | Required for knowledge base upload/retrieval |
| Optional | All other variables | Have reasonable defaults or can be disabled |

See [.env.example](.env.example) for the complete list.

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat` | Send message (SSE stream) |
| `DELETE` | `/api/chat/{sessionId}` | Cancel in-progress request |
| `POST` | `/api/sessions` | Create session |
| `GET` | `/api/sessions` | List sessions (cursor pagination) |
| `GET` | `/api/sessions/{sessionId}/messages` | Message history |
| `POST` | `/api/knowledge/upload` | Upload document to knowledge base |
| `POST` | `/api/project-discovery/scan` | Trigger project API scan |
| `GET` | `/api/project-discovery/config` | Get API configuration |
| `PUT` | `/api/project-discovery/config` | Update API configuration |
| `POST` | `/api/project-discovery/reload` | Hot-reload API configuration |
| `GET` | `/api/health` | Health check |

### Chat Request Example

```json
{
  "text": "Help me query all in-use devices",
  "context": {
    "outputMode": "streaming",
    "userId": "user-001",
    "enableThinking": true
  }
}
```

## Module Architecture

```
harness-env        ← Environment variables + connection pools
harness-core       ← Core models (AgentMessage, AgentTrace, ReActStep, ToolSpec, etc.)
├── harness-input       ← Auth + multimodal parsing + large file processing
├── harness-preprocess  ← RAG + query rewriting + semantic context + memory management
├── harness-tool        ← Tool interface + MCP adapter + Skill loading + code discovery tools
├── harness-audit       ← Trace collection and storage
└── harness-ai          ← LangChain4j + 7 model types + ReAct engine
harness-agent      ← Orchestrator + sub-agents + project API discovery
harness-server     ← HTTP API + Web UI
```

## Extension Guide

### Adding an LLM Provider

1. Implement the corresponding provider interface in `com.harness.ai.model.impl`
2. Register in `ModelProviderFactory`
3. Add environment variable keys in `EnvKey.java`

### Adding a Tool

1. Implement `com.harness.tool.Tool` (`spec()` + `execute()`)
2. Register in `AgentOrchestrator.registerBuiltinTools()`, or use MCP auto-discovery

### Adding a Vector Store Backend

1. Implement `VectorStore` (`retrieve()` + `insertBatch()` + `deleteByFile()`)
2. Register in `VectorStoreFactory`
3. Set `HARNESS_RAG_PROVIDER=your-backend`

## License

Apache License 2.0
