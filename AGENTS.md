# AGENTS.md

Instructions for coding agents working in Cyrene Agent. Current source and tests are authoritative. Verify implementation before changing correct code to match documentation.

## Project

Cyrene Agent is a Java 21 framework for building vertical-domain agents on top of existing business systems. Its core is an autonomous ReAct loop, not a fixed graph workflow.

```text
Provider
  -> Input (authentication, input, memory, extensible Context)
  -> immutable request-scoped tool catalog
  -> ReAct Loop (model -> tool -> inspection/reflection)
  -> Trace (tools, latency, tokens, parent/child runs)
  -> response and persistence
```

Keep framework code domain-neutral. Domain models, tenant identity sources, authorization, and graph semantics belong to the integrating system. Existing-system APIs use user credentials from trusted `context.credentials`; authorization remains the business system's responsibility.

The root `pom.xml` `revision` is the only version source. Current version: `0.5.8`. Do not change it or release notes unless explicitly requested.

## Before Editing

1. Run `git status --short` and preserve unrelated user changes.
2. Use `rg` / `rg --files`; inspect relevant POMs, interfaces, return types, and tests.
3. Change only the requested scope. Split mixed-file staging by hunk when needed.
4. Remove dead code, obsolete branches, and unused imports replaced by the change.
5. Do not create or rewrite `CLAUDE.md`, README, TODO, migration, or other guidance files without explicit instruction.

Never use `git reset --hard`, whole-file checkout, or another command that can discard user work.

## Build and Verification

```bash
mvn clean test -DskipITs
mvn clean test -Dmaven.compiler.fork=true -DskipITs
mvn test -pl harness-agent,harness-server -am -DskipITs
mvn test -pl harness-agent -am -Dtest=KnowledgeGraphToolTest -Dsurefire.failIfNoSpecifiedTests=false -DskipITs
mvn clean package -pl harness-server -am -DskipTests
java -jar harness-server/target/harness-server-0.5.8.jar
```

Quote complex or comma-containing `-D...` arguments in PowerShell. Before committing, run scope-appropriate tests plus:

```bash
git diff --check
node --check harness-server/src/main/resources/public/js/app.js
node --check harness-server/src/main/resources/public/js/api.js
node --check harness-server/src/main/resources/public/js/i18n.js
```

## Module Boundaries

| Module | Responsibility |
|---|---|
| `harness-core` | Shared models, `HARNESS_*` configuration, pools, pagination, context, runtime contracts |
| `harness-provider` | Chat, embedding, rerank, vision, and voice providers; LangChain4j adapters |
| `harness-input` | Authentication, multimodal input, text processing, gap analysis, memory stores/compression |
| `harness-tool` | Tool API, registry, snapshots, executor, project APIs, MCP, Skills, RAG, knowledge graph |
| `harness-react` | ReAct engine, inspection, reflection, model-tool loop control |
| `harness-trace` | Trace collection, persistence, cleanup, parent/child linkage |
| `harness-agent` | Orchestration, context construction, session runtime, sub-agents, tool assembly |
| `harness-server` | Javalin HTTP/SSE API and Vue 3 management UI |

Keep dependencies one-way around `core -> provider/input/tool/react/trace -> agent -> server`.

- Put shared models, environment configuration, and runtime contracts in `harness-core`.
- Keep model adapters in `harness-provider`, retrieval and graph implementation in `harness-tool`, loop control in `harness-react`, and protocol mapping in `harness-server`.
- Do not recreate merged modules (`harness-env`, `harness-preprocess`, `harness-graph`, `harness-ai`, or `harness-audit`).
- Lower modules must not depend on the server or orchestrator.

## Configuration

- Declare all runtime configuration as `HARNESS_*` keys in `EnvKey`.
- Precedence: explicit overrides > process environment > working-directory `.env` > code defaults.
- Never hard-code ports, providers, database addresses, timeouts, concurrency, directories, or credentials.
- Provider value `none` disables a capability. Do not register its static tools; avoid per-request filtering of permanently disabled tools.
- `.env.example` contains examples only, never real passwords or tokens.
- The Web UI and API share one Javalin listener. A page not being open does not create another port.

## Existing-System API Discovery

- Prefer OpenAPI/Swagger. Without a specification, the discovery Agent may inspect source only through restricted `code_glob`, `code_grep`, and `read_class_hierarchy` tools.
- Traverse at most two parent levels. Do not scan unrelated files to increase recall.
- Collect method, path, parameter JSON Schema, return type, authentication mode, and token injection location. Require developer review before writing `project-apis.json` and hot-loading tools.
- Credentials come from trusted `context.credentials`. The key must match the endpoint `credentialKey`; `HttpApiTool` injects it at the configured header, query, or other location.
- Never copy plaintext credentials into tool parameters, prompts, logs, or traces. Never introduce a shared superuser account to bypass user or tenant permissions.

## Design Rules

### Layers and Objects

- Design around interfaces and injected dependencies. Instantiate objects only at composition, factory, or explicit lifecycle boundaries.
- Handlers perform protocol conversion, authorization calls, and responses. Put business rules in services and persistence in stores/repositories.
- Extract reusable authorization, pagination, exception mapping, and conversion functions, but avoid abstractions used only once.
- Use camelCase for Java/JavaScript identifiers. Preserve database snake_case and map it explicitly.

### Errors and Transactions

- Do not hide failures with empty arrays, default objects, or fallback chains such as `a || b || []`.
- Catch recognized exceptions at boundaries and map them clearly; rethrow what cannot be handled. Do not swallow errors.
- HTTP errors use `ApiError { code, message, details }`.
- Related creates, updates, and deletes must be atomic. Use Neo4j `GraphChangeSet`/`applyChanges`; use explicit JDBC commit/rollback.
- Clear `ThreadLocal`, cancellation tokens, tool state, and request context in `finally`.

### Pagination and API Contracts

- Every growing list query requires pagination with a stable cursor, `ORDER BY`, and `limit + 1` to detect more rows. Do not use offset pagination for continuously changing data.
- Use the shared `PageResponse<T>` and `PageInfo` contract:

```json
{
  "items": [],
  "pageInfo": {
    "limit": 50,
    "nextCursor": "",
    "hasMore": false
  }
}
```

- Frontend code must read the exact backend response type and correct `res` location. Fix contract mismatches or surface errors; do not add ambiguous fallbacks.

### Frontend

- Static UI files live in `harness-server/src/main/resources/public` and use Vue 3, `api.js`, `app.js`, `i18n.js`, and `style.css`.
- UI must be responsive; large editors use available viewport height and narrow screens collapse to one column.
- Async actions show loading/disabled/error states. Render backend errors.
- Confirm destructive actions. Keyboard shortcuts must not fire while editing an input.
- Recompute graph layout only after explicit user action; property edits must not move nodes.

## Tool Runtime

Tools implement `com.harness.tool.Tool`:

```java
ToolSpec spec();
String execute(JsonNode arguments);
```

Invariants:

1. `ToolRegistry` is the application-level mutable registry for startup registration, MCP discovery, and project API hot reload.
2. Each Agent run creates one immutable `RunToolCatalog` with `toolRegistry.snapshot()`.
3. Request filtering uses `RunToolCatalog.excluding(...)` / `allowing(...)`; child agents receive narrower allowlists.
4. The same snapshot provides model specs and resolves calls for the entire run. Later registry changes cannot affect it.
5. `ToolExecutor.executeAuthorized` runs only instances authorized by that snapshot and handles confirmation, timing, state, and error conversion.

Do not use a System Prompt as an execution boundary; unavailable tools must be absent from model-visible definitions. Load Skills through the ordinary `load_skill` tool. Treat Skill messages as normal tool messages during compression; do not build special reinjection paths.

### Reflection and Tool Evolution

- `reflectionInterval` is an environment-level loop policy, not request `context` JSON.
- Query rewrite is a knowledge-base tool argument chosen by the Agent, not a request-level switch or separate mode toggle.
- An `INSUFFICIENT` knowledge result may implicitly escalate once into query-rewrite mode. Keep this in the tool-result protocol and Agent policy, not a fixed workflow bypassing tool calls.
- The vector-store candidate threshold and the result-sufficiency/escalation threshold are different concepts. Preserve `HARNESS_RAG_SCORE_THRESHOLD` as the storage-side hard filter.
- Completely irrelevant results must not escalate. Any score-range change must keep Milvus, pgvector, `KnowledgeBaseTool`, `RetrievalEscalationPolicy`, and tests aligned.

## Knowledge Base and Knowledge Graph

Keep the retrieval paths separate:

- `knowledge_base_search`: document chunks through Milvus or pgvector, with optional query rewrite, semantic completion, and rerank.
- `knowledge_graph_search`: Neo4j entities, relations, and paths; never feed it into vector rerank.

Do not disguise graph results as document chunks or couple graph construction to knowledge-document upload.

### Graph Model

- Schema defines allowed node/relation types, properties, and constraints.
- Graph Space identifies one business graph by `graphId + schemaId`.
- Graph Data is the concrete node/relation set.
- Neo4j stores Graph Data; the file repository stores Schema documents.
- Local Schema path: `./docker/neo4j/schemas`; container path: `/app/graph-schemas`. Neo4j data/logs bind to `docker/neo4j/data` and `docker/neo4j/logs`.

### Graph Enablement and Retrieval

`HARNESS_GRAPH_PROVIDER=none` is the only graph switch. It selects the NoOp store and prevents `knowledge_graph_search` registration.

```text
no graphId/schemaId: listGraphSpaces -> findNodes -> findNeighborhood
graphId/schemaId:    findNodes -> findNeighborhood
subjectIds present:  findNeighborhood
```

Trusted server `graphRequestContext` overrides model arguments. The model cannot widen graph, schema, subject, query, or tenant scope. Reject identical repeated graph calls within one Agent request while allowing valid pagination or changed queries.

### Graph Writes

- Validate structured JSON through the canonical converter before writing.
- Natural language uses `/api/graph/build/preview`; preview never writes Neo4j.
- Only a confirmed structured draft is submitted to `/api/graph/build`.
- Convert updates/deletes into one upsert/delete set and commit it in one Neo4j transaction.
- Revalidate Schema, relation endpoints, labels, depth, and count limits on the backend.

### Tenant Scope

- Trusted `context.tenantId`, not an LLM tool argument, defines tenant scope; missing values use `000000`.
- Single-tenant mode allows only the default tenant to access global graph spaces.
- Multi-tenant deployments may apply `sql/schema-graph-space-bindings-mysql.sql`; `graph_space_bindings` persists tenant-to-space access and purpose, not graph data or feature switches.
- Child agents inherit graph context. Remove graph tools from asynchronous recovery that lacks trusted tenant context.

## Docker and Persistence

```bash
docker compose -f docker/docker-compose.yml up -d --build
docker compose -f docker/docker-compose.yml --profile graph up -d neo4j
```

Main services: `cyrene-agent`, `mysql`, `milvus`, `redis`, `searxng`, and `browser-worker`; optional `neo4j` is under the `graph` profile.

- Compose volumes store MySQL, Milvus, Redis, knowledge uploads, and artifacts.
- Neo4j data/logs and Schema use bind-mounted `docker/neo4j` directories.
- `graph_space_bindings` is optional and is not created by default MySQL initialization.
- Never delete, rebuild, or migrate persistent directories without explicit authorization and verified target paths.

## Key Entrypoints

| Area | Path |
|---|---|
| Server and routes | `harness-server/src/main/java/com/harness/server/Main.java` |
| Orchestration | `harness-agent/src/main/java/com/harness/agent/AgentOrchestrator.java` |
| Context construction | `harness-agent/src/main/java/com/harness/agent/context/ContextBuilder.java` |
| Provider factory | `harness-provider/src/main/java/com/harness/provider/ModelProviderFactory.java` |
| ReAct loop | `harness-react/src/main/java/com/harness/react/ReActEngine.java` |
| Trace collection | `harness-trace/src/main/java/com/harness/trace/TraceCollector.java` |
| Tool registry/catalog | `harness-tool/src/main/java/com/harness/tool/ToolRegistry.java`, `RunToolCatalog.java` |
| Tool execution | `harness-tool/src/main/java/com/harness/tool/ToolExecutor.java` |
| Request context | `harness-core/src/main/java/com/harness/core/model/AgentContext.java` |
| Graph tool | `harness-agent/src/main/java/com/harness/agent/KnowledgeGraphTool.java` |
| Graph store | `harness-tool/src/main/java/com/harness/graph/neo4j/Neo4jKnowledgeGraphStore.java` |
| Graph Schema | `harness-tool/src/main/java/com/harness/graph/schema` |
| Graph APIs | `harness-server/src/main/java/com/harness/server/Graph*Handler.java` |
| Web UI | `harness-server/src/main/resources/public` |

When changing routes, update frontend API calls from `Main.java`. When adding configuration, update `EnvKey.java` and `.env.example`. New storage features require pagination, transactions, explicit exceptions, and integration tests.
