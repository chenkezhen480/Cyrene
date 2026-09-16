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

The root `pom.xml` `revision` is the only current-version source. Do not change it or release notes unless explicitly requested.

### Version 0.5.10 implementation notes

- Structured output is an outward-facing business response contract, not a fixed Tool-result format. `/api/structured-output` accepts a final JSON Schema, applies it only to the final tool-free model call, uses the Provider's strict response format, validates the returned JSON again, and maps unsupported capabilities or invalid values to explicit API errors. Ordinary `/api/chat` text and SSE contracts remain unchanged.
- Tool results that benefit from machine readability use a shared JSON envelope and typed DTOs. Knowledge-base and knowledge-graph tools expose stable fields, explicit result status, truncation metadata, and filtered sensitive properties without coupling every Tool to one universal payload shape.
- ReAct tool-planning text is not streamed as final prose. Tool events are correlated by normalized `toolCallId`; cancellation raises a cancellation outcome; exhausting the iteration limit performs one final tool-free response generation instead of returning raw Tool output.
- Text budgeting uses an injected Unicode-aware estimator. Markdown ingestion parses semantic blocks and packs headings, paragraphs, lists, tables, fenced code, and separators within a token budget. Stable `documentId`, `chunkIndex`, and heading context are persisted for new knowledge chunks.
- Unified knowledge retrieval uses `knowledge_search` as the semantic Wiki entry and returns signed, typed handles. `knowledge_read` reauthorizes the current MySQL revision and expands only an exact returned handle. Source-document reads keep deterministic chunk identity and a bounded document window; `before` and `after` default to `1`.
- Milvus management operations are collection scoped. Chunk and collection lists use stable cursor pagination with `limit + 1`; filename filtering executes server-side; update re-embeds changed content; retrieval and storage failures are surfaced instead of being disguised as empty results. File-storage paths are normalized and constrained to the configured upload root.
- Milvus client and Docker image are aligned to `2.5.13`. Document chunks, Wiki catalog entries, user episodes, and Agent operation playbooks use dedicated configured collections; collection-scoped get, update, delete, explicit-ID upsert, and deletion semantics must remain isolated.
- Sub-Agent completion contracts are optional. A task can require an allowlisted tool set, successful Tool executions, typed artifacts, and a final output Schema. Results include contract validation, artifact summaries, and Tool execution summaries; unmet contracts finish explicitly as `INCOMPLETE` or `FAILED_CONTRACT` without unbounded retries.
- The Web model configuration names the existing Classifier configuration group "Sub-Agent small-task model" because it handles lightweight internal tasks such as GapAnalyzer Tier 2. It is separate from asynchronous agents created through `spawn_subagent`; those dispatched agents continue to use the primary Chat model in 0.5.10.
- Web-managed model settings are persisted atomically in `HARNESS_CONFIG_MODEL_FILE` and activate for later requests without restarting the service. Active Agent and Sub-Agent runs retain their provider generation until completion. Embedding provider, endpoint, model, or dimension changes are rejected until knowledge data is explicitly re-indexed.
- OpenAI-compatible chat Providers support validated `chat_completions` and `responses` API formats behind `HARNESS_MODEL_CHAT_API_FORMAT`. The framework keeps local full conversation state and does not enable `previousResponseId`, hosted reasoning continuation, or an inbound `/v1/responses` compatibility route in this version.
- Session-cache and Provider prompt-cache metrics are observable separately through Trace: hit/miss, source, load/refill latency, eviction reasons, active sessions, estimated memory, cached tokens, cache-hit ratio, and prefix fingerprints. Real traffic baselines and any TTL, capacity, backend, prompt-layout, or cache-key optimization remain intentionally pending.
- SearXNG uses `HARNESS_TOOL_WEB_SEARCH_ENGINES` as the request-level engine-selection source. Bing keeps the configured higher weight, engine failures are diagnostic metadata rather than user-facing result text, secrets are supplied or generated at deployment, and the container, health check, internal URL, and host mapping consistently use port `8888`.
- Trusted knowledge and graph request scopes are removed from untrusted HTTP context input and rebuilt at the server boundary. Model arguments cannot widen trusted tenant, graph, schema, subject, query, or knowledge scope.
- The management UI reads the exact `PageResponse<T>` contract, supports responsive collection/file-name search and load-more interaction, and renders asynchronous failures explicitly.
- Project licensing is MIT. Third-party libraries, images, services, and container images retain their own license terms.

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
java -jar harness-server/target/harness-server.jar
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
- Query rewrite is a `knowledge_search` argument chosen by the Agent, not a request-level switch or separate mode toggle.
- An `INSUFFICIENT` knowledge result may implicitly escalate once into query-rewrite mode. Keep this in the tool-result protocol and Agent policy, not a fixed workflow bypassing tool calls.
- The vector-store candidate threshold and the result-sufficiency/escalation threshold are different concepts. Preserve `HARNESS_RAG_SCORE_THRESHOLD` as the storage-side hard filter.
- Completely irrelevant results must not escalate. Any score-range change must keep Milvus, `KnowledgeDiscoveryRouter`, and tests aligned.

## Unified Knowledge Architecture

Milvus is the semantic Wiki entry, MySQL is the authority and lifecycle control plane, and Neo4j owns graph data. Do not reintroduce the removed `knowledge_base_search`, `knowledge_context_read`, Topic/API concept types, or MySQL-revision-body retrieval paths for episodes and playbooks.

```text
trusted request scope
  -> inject active USER_PREFERENCE records from MySQL before the model call
  -> knowledge_search
       -> Wiki catalog + dedicated episode/playbook projections in Milvus
       -> signed typed handle
  -> knowledge_read(handle)
       -> recheck current revision, lifecycle, tenant/user/collection scope in MySQL
       -> document chunks | user episodes | operation playbooks | Neo4j graph route
```

`knowledge_search` discovers routes; it is not an authorization source. `knowledge_read` must reject stale, forged, expired, cross-tenant, cross-user, or otherwise unauthorized handles before reading downstream data. Returned handles and source anchors may be used in later calls for bounded deeper retrieval.

### Storage Roles

| Store | Role |
|---|---|
| MySQL | Authoritative concepts, current revisions, lifecycle/status, evidence and sources, permissions/scope metadata, preferences, outbox records, ingest jobs, and graph-mutation jobs/bindings. |
| Milvus | Semantic projections only: document chunks, Wiki catalog entries, user episodes, and Agent operation playbooks. It is never the authority for access or lifecycle. |
| Neo4j | Concrete graph nodes, relations, and paths only. Graph results are not disguised as document chunks and never enter vector reranking. |
| OKF | Portable structured import/export representation over the authoritative knowledge model. It does not replace MySQL, Milvus, or Neo4j. |

The live concept types are exactly `SOURCE_DOCUMENT`, `GRAPH_SCHEMA`, `GRAPH_SPACE`, `USER_PREFERENCE`, `USER_EPISODE`, and `OPERATION_PLAYBOOK`. Preferences remain MySQL-backed and are activated before the request; they are not searchable Wiki entries. The default Milvus collections are:

- document chunks: `cyrene_test`
- semantic Wiki catalog: `cyrene_knowledge_catalog`
- user episodes: `cyrene_user_knowledge`
- Agent operation playbooks: `cyrene_operation_knowledge`

Collection names remain configurable through `HARNESS_*` settings. User episodes are user-scoped; operation playbooks are reusable Agent knowledge and must not acquire a user owner.

### Ingestion and Durability

- Document upload stores an immutable source artifact, converts it to canonical Markdown through MarkItDown, creates the MySQL `SOURCE_DOCUMENT` concept/current revision, writes document chunks, and projects the semantic Wiki entry through the outbox.
- A retryable initial ingest failure returns HTTP `202` with `status: pending`, `jobId`, `documentId`, `sourceArtifactId`, `collection`, and `message`; the background worker retries. Successful ingestion returns `status: indexed`; non-retryable conversion or validation failures mark the job failed.
- Graph Schema Wiki namespace key is `schemaId`. Graph Space Wiki namespace key is `graphId:schemaId`. Graph concepts are global after explicit graph-access checks; callers cannot provide an arbitrary OKF graph namespace.
- Graph writes use the Neo4j change plus MySQL Graph Space Wiki Saga/outbox path. A retry after Neo4j commit must not reapply the graph mutation.
- Related MySQL writes use explicit transactions. Projection/outbox work must be idempotent and must not make Milvus the source of truth.

### Graph Model

- Schema defines allowed node/relation types, properties, and constraints.
- Graph Space identifies one business graph by `graphId + schemaId`.
- Graph Data is the concrete node/relation set.
- Neo4j stores Graph Data; the file repository stores Schema documents.
- Local Schema path: `./docker/neo4j/schemas`; container path: `/app/graph-schemas`. Neo4j data/logs bind to `docker/neo4j/data` and `docker/neo4j/logs`.

### Graph Enablement and Retrieval

`HARNESS_GRAPH_PROVIDER=none` is the only graph switch. It selects the NoOp store and disables graph-backed `knowledge_read` routing.

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
- `knowledge_graph_bindings` persists tenant-to-space access and purpose, not graph data or feature switches. Graph concepts themselves remain global (`tenant_id = null`) after access is checked explicitly.
- Child agents inherit graph context. Remove graph tools from asynchronous recovery that lacks trusted tenant context.

### TODO12 Handoff Baseline (2026-09-09)

- The unified knowledge architecture above is implemented in the current working tree. It is intentionally uncommitted and includes broad TODO12 changes; preserve it and do not restore removed legacy tools/types.
- The last completed clean unit/component run reported 637 tests, 0 failures, 0 errors, and 7 skipped. Targeted MySQL, Redis, Milvus, and Neo4j integration suites also passed, including 9 `MysqlTraceStoreIT` tests after correcting its stale database name.
- A real upload-to-Agent E2E used a random local YAML file, produced two document chunks plus a Wiki catalog/current revision, and successfully exercised `knowledge_search` followed by `knowledge_read`. Its temporary knowledge rows, Milvus entries, and artifact directories were removed afterward; the service on port 8080 was stopped.
- `git diff --check`, the three JavaScript syntax checks, and the server package build passed. The packaged artifact is `harness-server/target/harness-server-0.5.10.jar`.
- Do not repeat the full suite merely to reconfirm this handoff. Run targeted checks proportional to later changes; reserve a new full clean suite for broad behavior/storage changes or release validation. A later redundant full-suite rerun was intentionally interrupted at the user's request and is not an additional completed result.
- Existing Milvus production data must not be mutated, deleted, or migrated without explicit confirmation and verified targets. MySQL/Neo4j schemas may evolve with the new architecture, but destructive work still requires exact target checks.

## Docker and Persistence

```bash
docker compose --env-file .env -f docker/docker-compose.yml up -d --build
docker compose --env-file .env -f docker/docker-compose.yml --profile graph up -d neo4j
```

Main services: `cyrene-agent`, `document-parser`, `mysql`, `milvus`, `redis`, `searxng`, and `browser-worker`; optional `neo4j` is under the `graph` profile.

- `document-parser` is the isolated MarkItDown worker. Knowledge uploads, document attachments, large-file parsing, and `context.File` must convert documents to canonical Markdown through the shared `DocumentConversionService` before downstream chunking or prompting.
- Document vision uses the complete `HARNESS_MODEL_VISION_*` group when its provider is configured; otherwise it reuses the complete `HARNESS_MODEL_CHAT_*` group. Provider `none` explicitly disables document vision.

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
| Unified knowledge tools | `harness-agent/src/main/java/com/harness/agent/knowledge/KnowledgeSearchTool.java`, `KnowledgeReadTool.java` |
| Knowledge discovery/router | `harness-agent/src/main/java/com/harness/agent/knowledge/KnowledgeDiscoveryRouter.java` |
| Knowledge authority | `harness-tool/src/main/java/com/harness/tool/knowledge/authority` |
| Knowledge projections | `harness-tool/src/main/java/com/harness/tool/knowledge/index` |
| Knowledge ingest/lifecycle | `harness-tool/src/main/java/com/harness/tool/knowledge/KnowledgeIngestService.java`, `KnowledgeDocumentLifecycleService.java` |
| Graph read adapter | `harness-agent/src/main/java/com/harness/agent/KnowledgeGraphTool.java` |
| Graph store | `harness-tool/src/main/java/com/harness/graph/neo4j/Neo4jKnowledgeGraphStore.java` |
| Graph Schema | `harness-tool/src/main/java/com/harness/graph/schema` |
| Graph APIs | `harness-server/src/main/java/com/harness/server/Graph*Handler.java` |
| Web UI | `harness-server/src/main/resources/public` |

When changing routes, update frontend API calls from `Main.java`. When adding configuration, update `EnvKey.java` and `.env.example`. New storage features require pagination, transactions, explicit exceptions, and integration tests.
