<p align="right"><a href="./README.md">中文</a></p>

# Cyrene Agent — A Dedicated Agent Framework for Existing Projects

> Add an autonomous ReAct Agent to an existing business system, its domain knowledge, and its authorization model instead of building an isolated AI application from scratch.

Cyrene Agent is an Agent development framework built with Java 21. It is designed for vertical-domain developers who need to give an existing system a natural-language interface, project API control, enterprise knowledge retrieval, relational-data retrieval, validated structured output, sub-agent collaboration, and end-to-end tracing.

## Why Cyrene Agent

Cyrene Agent addresses a different question: **how do you build a dedicated Agent for an existing system?**

Use case 1:
<table>
  <tr>
    <td><img src="docs/assets/图片1.png" height="560" alt="Scenario 1"></td>
    <td><img src="docs/assets/图片2.png" height="560" alt="Scenario 2"></td>
  </tr>
</table>

## Quick Start

### Docker Compose

```bash
cp .env.example .env
# Edit .env and configure at least the main Chat Model
docker compose --env-file .env -f docker/docker-compose.yml up -d --build
```

### Local Build

```bash
mvn clean package -pl harness-server -am -DskipTests
java -jar harness-server/target/harness-server.jar
```

- The service listens on `8080` by default.
- The Web console covers model configuration, project API discovery, knowledge upload, knowledge-graph management, and test conversations with the Agent.
- An intranet deployment connected to the project backend is recommended.
- The tenant and identity, along with the `userId` and token credentials, arrive as context parameters on the chat API. Both have defaults, so you can shape them around your own system — the defaults target systems with no tenancy and no identity.

See [.env.example](./.env.example) for the complete configuration surface.

### Requirements

- Java 21+
- Maven 3.8+
- Docker and Docker Compose

## Core Capabilities

### 1. LLM wiki + RAG

![Wiki interface](docs/assets/wiki.png)
![Knowledge-graph interface](docs/assets/graph.png)

Structure:
```text
                              Agent
          │                     │                     │
          ┌─────────────────────┼─────────────────────┐
          ▼                     ▼                     ▼
  knowledge_search       knowledge_read          query_graph
          │                     │                     │
          ▼                     ▼                     ▼
        Wiki                 MySQL                 Neo4j
       Milvus            authoritative data       graph data
          │
          ▼
          Document / Episode / Operation

          A Wiki may also carry a GRAPH_SCHEMA capability card
          │
          └──> reminding the Agent that query_graph is available
```
Memory and knowledge are split into distinct stores: Agent operation memory records the pitfalls hit while operating on your project; user scenario memory recalls context; vector documents hold vertical-domain knowledge chunks; the knowledge graph holds the real business relationships of the project's integrations; long-term preference memory records the user profile.

### 2. Tool Lists Isolated by Tenant and Identity

![Tool permissions](docs/assets/tool.png)

Tools are separated by design. If your users are consumers (ToC), they must not be given tools that control code — yet you still need those tools to generate the project's API integration tools. (Both tenant and identity default to values suited to an individual project.)

### 3. One-Click Integration with Project APIs

![Project API configuration](docs/assets/api-detail.png)

The project API integration window opens automatically on first launch. After configuration you can edit endpoints on the settings page. On later startups the API file is automatically wrapped as Agent tools — not a one-shot injection, but a tree of managed tools governed by an upstream tool.

## Main Modules

The architecture converges on **Provider → Input → ReAct Loop → Trace**, with Tool acting as the capability boundary between the loop and business systems.

### Provider

Provider is the composition boundary for model capabilities. It centers on the main LLM and tool-calling model while supporting independent Vision, ASR/TTS, Embedding, Rerank, Classifier, and Realtime capabilities. Image and video generation are connected through provider-configured generation tools. Each capability can use a different vendor, model, endpoint, and concurrency policy so enterprises can balance quality, cost, and data boundaries.

### Input

Input covers more than user text. It handles authentication, multimodal parsing, short- and long-term memory injection, context construction, and extensible request-level JSON Context. Integrators can provide trusted fields such as `userId`, `tenantId`, output mode, graph scope, and user credentials, allowing the Agent to inherit the identity, tenancy, and business scope of the host system.

### ReAct Loop

The ReAct Loop performs “model decision → tool call → tool-result inspection → next model decision or output.” Inspector and adaptive reflection use tool errors, empty results, low relevance, and repeated calls to guide the next action.

Tools can also expose structured result states that evolve later calls. For example, knowledge-base retrieval starts with a low-cost original query. If the result falls into a recoverable relevance range, the next round implicitly upgrades to query rewriting, multi-query, Step-back, and HyDE retrieval. Completely unrelated results, or a request that has already escalated, do not repeatedly pay the rewriting cost.

### Trace

Trace observes the complete run: input, model rounds, tool calls and results, inspection and reflection status, latency, token consumption, confirmation decisions, and final output. A sub-agent owns an independent trace linked to its parent through `parent_trace_id`, making the full task tree reconstructable.

![Trace interface](docs/assets/trace.png)

## License

[Apache License 2.0](./LICENSE). Third-party dependencies remain subject to their respective licenses.
