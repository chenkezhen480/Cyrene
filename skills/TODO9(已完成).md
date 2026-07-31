# TODO9：通用知识图谱独立检索路线与 Neo4j 可选接入

## 0. 背景与已确认决策

当前 Cyrene Agent 的知识检索以文档 RAG 为核心：

```text
文件 → 文本提取 → Chunk → Embedding → Milvus/pgvector
                                      ↓
                              向量/BM25 混合检索
                                      ↓
                           语义回溯、查询改写、rerank
```

该流程适合专业文档、制度、手册、课程资料和其他非结构化知识，但不适合直接表达结构化业务关系，例如人员、能力、观察、场景、项目、组织、代码依赖等。

TODO9 增加一条与传统向量 RAG 平级的知识图谱路线，首个 Provider 为 Neo4j。

本轮已确认以下设计决策：

1. **知识图谱是独立检索路线，不是 `VectorStore` 的一种实现。**
2. **Neo4j 返回节点、关系、路径和聚合结果，不强制转换为 Chunk。**
3. **图谱结果不进入现有向量 rerank，也不与向量相似度比较。**
4. **向量路线与图谱路线只在最终上下文组装阶段汇合，并保持来源分区。**
5. **框架只提供通用图谱能力，不内置儿童、教师、能力等具体业务模型。**
6. **具体业务通过 Schema、结构化数据和查询策略扩展框架。**
7. **首版以结构化图数据接入为主，不做全量文档 LLM 自动建图。**
8. **Neo4j 作为可选 Provider，未启用时不增加运行依赖。**
9. **Neo4j 使用独立 Docker 容器，通过 Compose Profile 按需启动。**
10. **所有图谱新增、更新、删除操作在单个 Neo4j 数据库内使用事务。跨数据库操作使用状态机与补偿事务，不伪装成本地原子事务。**

---

## 1. 目标与非目标

### 1.1 目标

- 为 Cyrene Agent 增加通用知识图谱存储抽象。
- 提供 Neo4j Community 单机 Provider。
- 支持结构化节点和关系的批量新增、更新、查询与删除。
- 支持业务注册自己的图谱 Schema 和查询策略。
- 支持 tenant、schema、subject 等检索范围隔离。
- 支持节点、关系和查询结果的游标分页。
- 增加独立 `GraphKnowledgeRetriever`。
- 保留现有 Milvus/pgvector 检索、语义回溯和 rerank 流程。
- 增加分区式 `KnowledgeContextAssembler`，分别组装专业知识和结构化图谱信息。
- 支持通过 Docker Compose Profile 可选启动 Neo4j。
- 保证 `HARNESS_GRAPH_PROVIDER=none` 时现有功能和部署行为不变。

### 1.2 非目标

- 不在框架内定义儿童、教师、能力、医疗、组织等领域实体。
- 不把 Neo4j 节点或路径伪装成向量 Chunk。
- 不把图谱结果放入向量 rerank。
- 不在首版实现自由文本全量 LLM 实体关系抽取。
- 不在首版实现 RDF、OWL、SPARQL 或完整本体推理。
- 不在首版实现社区发现、中心性分析等 GDS 图算法。
- 不在首版安装 APOC、GDS、GenAI 等 Neo4j 插件。
- 不允许 LLM 或外部调用方直接提交任意 Cypher。
- 不允许查询参数覆盖服务端 tenant、schema、subject 权限范围。
- 不在首版提供 Neo4j 集群、高可用和跨地域部署。
- 不将 Neo4j 作为向量知识库的替代品。

---

## 2. 总体架构

```text
                               ┌───────────────────────────────┐
                               │ KnowledgeRouteDecision        │
                               │ vector / graph / both / none  │
                               └───────────────┬───────────────┘
                                               │
                     ┌─────────────────────────┴─────────────────────────┐
                     │                                                   │
                     ▼                                                   ▼
        VectorKnowledgeRetriever                            GraphKnowledgeRetriever
                     │                                                   │
              Milvus/pgvector                                          Neo4j
                     │                                                   │
        Chunk + score + metadata                         Node + relation + path + aggregate
                     │                                                   │
     语义回溯 → 查询改写 → rerank                    权限过滤 → 路径限制 → 确定性排序
                     │                                                   │
                     ▼                                                   ▼
          VectorRouteResult                                  GraphRouteResult
                     └─────────────────────────┬─────────────────────────┘
                                               ▼
                                KnowledgeContextAssembler
                                               │
                        ┌──────────────────────┴──────────────────────┐
                        │ [专业文档知识]                              │
                        │ [结构化图谱信息]                            │
                        │ [来源与使用约束]                            │
                        └──────────────────────┬──────────────────────┘
                                               ▼
                                             ReAct
```

两条路线的内部结果类型保持独立：

```java
public sealed interface KnowledgeRouteResult
        permits VectorRouteResult, GraphRouteResult {
}
```

禁止设计成一个跨路线的 `List<RetrievalCandidate>` 后统一评分。

---

## 3. 模块划分

### 3.1 新增 `harness-graph`

新增独立 Maven 模块，职责包括：

```text
harness-graph
├── model       通用节点、关系、路径、分页和查询模型
├── schema      Schema 定义、注册、校验
├── store       KnowledgeGraphStore 接口
├── neo4j       Neo4j Provider
├── query       查询计划、策略、结果裁剪
├── retrieval   GraphKnowledgeRetriever
└── config      Provider 工厂和配置校验
```

依赖方向：

```text
harness-core
    ↑
harness-env
    ↑
harness-graph
    ↑
harness-agent
    ↑
harness-server
```

约束：

- `harness-graph` 不依赖 `harness-preprocess`。
- `harness-preprocess` 继续负责向量 RAG，不反向依赖图谱模块。
- `harness-agent` 负责路线决策和最终上下文组装。
- `harness-server` 只负责 HTTP 参数解析、鉴权和响应映射，不写 Neo4j 业务逻辑。

### 3.2 现有模块变更

#### `harness-core`

- 增加路线级通用枚举或上下文模型：
  - `KnowledgeRoute`
  - `KnowledgeRouteDecision`
  - `GraphRequestContext`
- 不增加任何具体业务实体。

#### `harness-env`

- 增加 `HARNESS_GRAPH_*` 配置键。
- 对 Provider、URI、数据库名和超时进行启动期校验。

#### `harness-agent`

- 增加 `KnowledgeRetrievalCoordinator`。
- 增加 `KnowledgeContextAssembler`。
- 在 `AgentContext` 中增加只读 `GraphRequestContext`。
- 保持 `KnowledgeBaseTool` 的向量检索逻辑可独立工作。

#### `harness-server`

- 增加图谱 Schema、节点、关系和查询 API。
- 所有列表接口使用游标分页。
- 请求 DTO 与响应 DTO 明确定义，不直接把 Neo4j Driver 类型返回给前端。

#### `docker`

- 增加可选 Neo4j Compose Profile、数据卷和日志卷。
- `cyrene-agent` 通过 Docker 服务名访问 `bolt://neo4j:7687`。

---

## 4. 通用图谱模型

### 4.1 节点

```java
public record GraphNode(
        String nodeId,
        Set<String> labels,
        Map<String, Object> properties
) {
}
```

约束：

- `nodeId` 由上游业务生成并保持稳定。
- `labels` 至少包含一个 Schema 允许的类型。
- `properties` 只允许 JSON 可序列化的基础类型、列表和时间类型。
- 框架保留字段与业务字段分离，禁止业务覆盖 `tenantId`、`schemaId`、`createdAt`、`updatedAt`。

### 4.2 关系

```java
public record GraphRelation(
        String relationId,
        String sourceNodeId,
        String targetNodeId,
        String relationType,
        Map<String, Object> properties
) {
}
```

约束：

- `relationId` 必须稳定，支持幂等 `MERGE`。
- 起点、终点和关系类型必须通过 Schema 校验。
- 所有关联来源、观察、文档或业务记录的关系必须保存 `sourceRefs`。
- 不允许使用关系类型承载未经校验的任意用户文本。

### 4.3 路径

```java
public record GraphPath(
        List<GraphNode> nodes,
        List<GraphRelation> relations,
        int depth
) {
}
```

### 4.4 聚合结果

```java
public record GraphAggregate(
        String key,
        Map<String, Object> values
) {
}
```

聚合结果用于趋势、计数、分组等确定性查询，不能伪造成图路径。

### 4.5 图谱路线结果

```java
public record GraphRouteResult(
        List<GraphNode> nodes,
        List<GraphRelation> relations,
        List<GraphPath> paths,
        List<GraphAggregate> aggregates,
        GraphPageInfo pageInfo,
        Map<String, Object> metadata
) implements KnowledgeRouteResult {
}
```

`GraphRouteResult` 不包含向量相似度，不进入向量 rerank。

---

## 5. Schema 扩展机制

通用框架不能依赖完全无约束的属性图。业务方必须注册 Schema：

```java
public interface GraphSchemaProvider {

    String schemaId();

    GraphSchemaDefinition definition();

    void validateMutation(GraphMutationBatch mutationBatch);
}
```

### 5.1 `GraphSchemaDefinition`

至少描述：

- 节点类型。
- 节点唯一标识规则。
- 节点属性名称和数据类型。
- 必填属性。
- 允许的关系类型。
- 每类关系允许的起点和终点类型。
- 关系属性和必填字段。
- 可查询字段。
- 可排序字段。
- 默认查询深度和最大查询深度。
- 允许暴露给 LLM 的字段。
- Schema 版本。

### 5.2 Schema 模式

```java
public enum GraphSchemaMode {
    STRICT,
    HYBRID,
    OPEN
}
```

- `STRICT`：只允许 Schema 明确定义的节点、关系和字段。
- `HYBRID`：已知类型严格校验，扩展类型进入待审核状态。
- `OPEN`：允许探索性类型，首版不开放给高敏感业务。

首版实现 `STRICT`，为 `HYBRID` 预留接口，暂不实现 `OPEN` 自动扩展。

### 5.3 业务扩展示例

以下仅作为外部业务插件示意，不进入 Cyrene Agent 核心代码：

```text
child-capability-v1
organization-v1
project-dependency-v1
code-structure-v1
```

核心仓库测试示例使用通用的 `Person → WORKS_ON → Project`，避免引入真实儿童或医疗教育数据。

---

## 6. 图谱写入流程

### 6.1 首版数据来源

首版支持：

1. HTTP 批量提交结构化节点和关系。
2. Java SPI 方式注册 `GraphDataSourceAdapter`。
3. 业务系统通过 Outbox/Event 将结构化变化同步到框架。

首版不支持：

- 上传任意文档后自动调用 LLM 建图。
- LLM 生成并直接执行 Cypher。
- 从聊天文本隐式写入图谱。

### 6.2 批量写入模型

```java
public record GraphMutationBatch(
        String requestId,
        String tenantId,
        String schemaId,
        List<GraphNode> nodes,
        List<GraphRelation> relations
) {
}
```

写入顺序：

```text
鉴权
  ↓
校验 tenantId/schemaId
  ↓
Schema 校验
  ↓
开启 Neo4j 事务
  ↓
批量 MERGE 节点
  ↓
批量 MERGE 关系
  ↓
记录 mutationId
  ↓
提交事务
```

任何节点或关系失败时整批回滚，不允许部分成功后返回 200。

### 6.3 幂等

- `requestId` 用于请求级幂等。
- `nodeId` 和 `relationId` 建立唯一约束。
- 重试相同 `requestId` 返回原执行结果。
- 同一业务 ID 的更新采用受控属性更新，不创建重复节点。
- 禁止把 Neo4j 内部 element ID 暴露为业务主键。

### 6.4 删除

删除接口必须明确目标范围：

- 按单个节点删除。
- 按单个关系删除。
- 按 `sourceId` 删除一批派生关系。
- 按 tenant + schema 删除整个图谱空间。

所有删除均使用事务，并在删除节点前校验关联关系处理策略：

```java
public enum GraphDeleteMode {
    REJECT_IF_REFERENCED,
    DETACH,
    DELETE_DERIVED_ONLY
}
```

禁止默认使用不受范围限制的 `DETACH DELETE`。

---

## 7. 查询与检索策略

### 7.1 禁止任意 Cypher

以下输入不允许直接进入 Neo4j Driver：

- LLM 生成的原始 Cypher。
- HTTP 请求体中的原始 Cypher。
- 前端传入的任意标签、关系类型和属性名。
- 未经 Schema 白名单验证的排序字段。

### 7.2 查询计划

```java
public record GraphQueryPlan(
        String queryId,
        String schemaId,
        Map<String, Object> parameters,
        int maxDepth,
        int limit,
        String cursor
) {
}
```

`queryId` 引用服务端注册的参数化查询模板，调用方只传参数。

### 7.3 查询策略 SPI

```java
public interface GraphQueryStrategy {

    String schemaId();

    GraphQueryPlan plan(GraphRetrievalRequest request);

    GraphRouteResult execute(GraphQueryPlan queryPlan);
}
```

首版提供：

- `AnchoredNeighborhoodQueryStrategy`：从已知节点向外查询受控深度。
- `RegisteredTemplateQueryStrategy`：执行 Schema 注册的参数化查询模板。

后续可选：

- `TextToGraphQueryStrategy`：自然语言转受控查询计划。

即使后续增加 Text-to-Cypher，也必须通过 AST/Schema 校验、只读事务、超时、深度和行数限制后执行，不能直接运行模型输出。

### 7.4 图谱请求上下文

```java
public record GraphRequestContext(
        String tenantId,
        String schemaId,
        Set<String> subjectIds,
        Set<String> allowedQueryIds
) {
}
```

约束：

- `tenantId` 来自服务端认证上下文，不接受 LLM 覆盖。
- `schemaId` 必须由宿主应用允许。
- `subjectIds` 由业务系统明确传入，用于解析“这个对象”等指代。
- 没有合法 `GraphRequestContext` 时，不执行个体图谱检索。
- 查询模板必须自动注入 tenant、schema 和 subject 范围。

---

## 8. 独立图谱检索路线

### 8.1 接口

```java
public interface GraphKnowledgeRetriever {

    GraphRouteResult retrieve(GraphRetrievalRequest request);
}
```

### 8.2 内部流程

```text
GraphRequestContext 校验
  ↓
GraphQueryStrategy 选择
  ↓
生成受控 GraphQueryPlan
  ↓
Neo4j 只读事务查询
  ↓
tenant/schema/subject 二次校验
  ↓
路径去重
  ↓
深度、时间、置信度和数量裁剪
  ↓
GraphRouteResult
```

### 8.3 不使用向量 rerank

图谱路线不调用：

- `RerankModelProvider`
- `SemanticContextRetriever`
- `QueryRewriter`
- `VectorStore.searchVector`
- `VectorStore.searchHybrid`

图谱内部只允许确定性排序：

- 时间倒序或正序。
- 路径深度。
- 业务置信度。
- Schema 定义的优先级。
- 来源权威级别。
- 查询模板定义的聚合结果。

### 8.4 路线决策

```java
public record KnowledgeRouteDecision(
        boolean needsVectorKnowledge,
        boolean needsGraphKnowledge
) {
}
```

示例：

```text
通用概念、专业资料             → vector
具体对象的关系、状态和历史      → graph
结合专业知识分析具体对象        → vector + graph
普通闲聊                       → none
```

路线决策优先级：

```text
AgentContext 显式设置
    ↓
请求中是否存在合法 GraphRequestContext
    ↓
规则分类
    ↓
可选分类模型
```

没有图谱上下文时不得仅凭 LLM 猜测对象 ID。

---

## 9. 上下文组装

新增 `KnowledgeContextAssembler`：

```java
public interface KnowledgeContextAssembler {

    String assemble(
            VectorRouteResult vectorResult,
            GraphRouteResult graphResult
    );
}
```

输出保持明确分区：

```text
[专业文档知识]
由向量知识库检索到的 Chunk 和来源。

[结构化图谱信息]
由图数据库检索到的节点、关系、路径、时间和来源。

[使用约束]
区分通用知识与具体业务记录；不得把观察、预测或建议表述为永久事实。
```

规则：

- 不把图谱路径拼进向量 Chunk 列表。
- 不对两路结果计算统一分数。
- 不让向量知识覆盖更新、更具体且授权可见的结构化记录。
- 图谱记录必须保留来源、时间和类型。
- 图谱为空时不输出空标题。
- 向量为空时不输出空标题。
- 两路都为空时交由现有 ReAct 空结果流程处理。
- 上下文长度分别设上限，禁止一条路线吞掉全部上下文窗口。

### 9.1 图谱格式化

新增：

```java
public interface GraphResultFormatter {

    String schemaId();

    String format(GraphRouteResult result);
}
```

首版提供通用确定性格式化器，业务可以注入 Schema 专用格式化器。格式化过程不调用 LLM。

---

## 10. `KnowledgeGraphStore` 抽象

```java
public interface KnowledgeGraphStore extends AutoCloseable {

    GraphMutationResult upsertBatch(GraphMutationBatch mutationBatch);

    GraphNode getNode(GraphNodeKey nodeKey);

    GraphPage<GraphNode> listNodes(GraphNodePageRequest request);

    GraphPage<GraphRelation> listRelations(GraphRelationPageRequest request);

    GraphRouteResult executeQuery(GraphQueryPlan queryPlan);

    GraphDeleteResult delete(GraphDeleteRequest request);

    String providerName();
}
```

实现：

```text
Neo4jKnowledgeGraphStore
NoOpKnowledgeGraphStore
```

约束：

- 上层通过构造器注入 `KnowledgeGraphStore`，不得在 Handler、Retriever 中直接 `new Neo4jKnowledgeGraphStore()`。
- Neo4j Driver 由工厂统一创建并在应用关闭时释放。
- 写操作使用显式事务函数。
- 读操作使用只读事务、超时和结果上限。
- 所有 Cypher 使用参数绑定。
- 日志不能输出密码、完整敏感属性或原始认证上下文。
- Provider 配置为 `neo4j` 但连接失败时启动失败，不静默替换为 NoOp。
- Provider 配置为 `none` 时不创建 Driver、不连接 Neo4j。

---

## 11. 配置设计

新增环境变量：

```properties
# Provider: none | neo4j
HARNESS_GRAPH_PROVIDER=none

# Neo4j
HARNESS_GRAPH_NEO4J_URI=bolt://localhost:7687
HARNESS_GRAPH_NEO4J_USER=neo4j
HARNESS_GRAPH_NEO4J_PASSWORD=
HARNESS_GRAPH_NEO4J_DATABASE=neo4j

# 连接池与超时
HARNESS_GRAPH_CONNECT_TIMEOUT_SECONDS=10
HARNESS_GRAPH_QUERY_TIMEOUT_SECONDS=15
HARNESS_GRAPH_MAX_CONNECTION_POOL_SIZE=20

# 查询限制
HARNESS_GRAPH_QUERY_DEFAULT_LIMIT=50
HARNESS_GRAPH_QUERY_MAX_LIMIT=200
HARNESS_GRAPH_QUERY_DEFAULT_MAX_DEPTH=1
HARNESS_GRAPH_QUERY_MAX_DEPTH=2

# 上下文限制
HARNESS_GRAPH_CONTEXT_MAX_ITEMS=50
HARNESS_GRAPH_CONTEXT_MAX_CHARS=12000
```

启动校验：

- `provider=none` 时忽略 Neo4j 连接参数。
- `provider=neo4j` 时 URI、用户、密码和数据库名必须完整。
- 密码为空时启动失败。
- 超时、连接池、limit 和 depth 非法时启动失败。
- `defaultLimit` 不得大于 `maxLimit`。
- `defaultMaxDepth` 不得大于 `maxDepth`。
- 不支持的 Provider 直接启动失败。

现有 `HARNESS_RAG_KNOWLEDGE_GRAPH_ENABLED` 标记为废弃，兼容期内只记录迁移提示，不同时维护两套真实开关。

---

## 12. HTTP API

所有查询接口必须分页，所有写接口必须支持批量事务和幂等。

### 12.1 Schema

| Method | Path | Description |
|---|---|---|
| GET | `/api/graph/schemas` | 分页列出当前用户可访问的 Schema |
| GET | `/api/graph/schemas/{schemaId}` | 查询 Schema 详情 |

首版 Schema 通过 Java SPI 或启动配置注册，不通过公共 API 动态创建。

### 12.2 节点

| Method | Path | Description |
|---|---|---|
| POST | `/api/graph/nodes/batch` | 事务批量写入节点 |
| GET | `/api/graph/nodes/{nodeId}` | 查询单个节点 |
| GET | `/api/graph/nodes` | 游标分页查询节点 |
| DELETE | `/api/graph/nodes/{nodeId}` | 按明确策略删除节点 |

分页参数：

```text
schemaId
label
limit
cursor
```

### 12.3 关系

| Method | Path | Description |
|---|---|---|
| POST | `/api/graph/relations/batch` | 事务批量写入关系 |
| GET | `/api/graph/relations` | 游标分页查询关系 |
| DELETE | `/api/graph/relations/{relationId}` | 删除单个关系 |

### 12.4 批量变更

推荐主要写入口：

```text
POST /api/graph/mutations
```

一次事务同时写入节点和关系，避免调用方先创建节点、后创建关系导致中间状态不一致。

请求：

```json
{
  "requestId": "mutation-20260727-001",
  "schemaId": "project-dependency-v1",
  "nodes": [],
  "relations": []
}
```

`tenantId` 从认证上下文获取，不接受请求体传入后直接信任。

响应：

```json
{
  "requestId": "mutation-20260727-001",
  "committed": true,
  "nodeCount": 2,
  "relationCount": 1
}
```

### 12.5 查询

```text
POST /api/graph/query
```

请求只接受：

- `schemaId`
- `queryId`
- 模板允许的参数
- `limit`
- `cursor`

不接受 `cypher` 字段。

前端必须按照响应 DTO 的真实字段读取数据，不假设 `res` 本身就是节点数组。

---

## 13. Docker 部署

### 13.1 可选 Profile

在 `docker/docker-compose.yml` 增加：

```yaml
neo4j:
  image: neo4j:<项目验证过的明确版本>
  container_name: cyrene-neo4j
  profiles:
    - graph
  ports:
    - "${HARNESS_GRAPH_NEO4J_HTTP_PORT:-7474}:7474"
    - "${HARNESS_GRAPH_NEO4J_BOLT_PORT:-7687}:7687"
  environment:
    NEO4J_AUTH: "neo4j/${HARNESS_GRAPH_NEO4J_PASSWORD}"
  volumes:
    - neo4j-data:/data
    - neo4j-logs:/logs
  restart: unless-stopped
  networks:
    - cyrene-net
```

增加：

```yaml
volumes:
  neo4j-data:
  neo4j-logs:
```

约束：

- 镜像使用明确版本，不使用 `latest`。
- 密码只从 `.env` 或密钥系统读取，不提供弱默认密码。
- 不设置 `NEO4J_AUTH=none`。
- 首版不安装额外插件。
- 增加健康检查，应用在 Provider 启用时等待 Neo4j 可用。
- `cyrene-agent` 容器使用 `bolt://neo4j:7687`。
- 不为未启用图谱的普通部署添加强制 `depends_on`。

启动：

```bash
docker compose up -d
docker compose --profile graph up -d
```

### 13.2 生产限制

- Browser 端口 `7474` 默认只用于本地开发。
- 生产环境不得将 `7474` 和 `7687` 直接暴露公网。
- 配置 Neo4j 数据备份和恢复流程。
- 根据实际数据量配置 heap 和 page cache。
- 对数据卷、日志卷和备份文件设置访问权限。
- 高敏感业务使用独立网络、独立凭据和最小权限账号。

---

## 14. 一致性与事务

### 14.1 Neo4j 内部

以下操作必须在单个 Neo4j 事务内完成：

- 批量节点写入。
- 批量关系写入。
- 一次 Mutation 中的节点与关系写入。
- 删除来源证据及其派生关系。
- Schema 版本迁移中的单批变更。

### 14.2 跨存储

Milvus、Neo4j、MySQL 和文件系统无法共享普通本地事务。涉及多个存储时使用：

```text
PENDING
  ↓
PRIMARY_COMMITTED
  ↓
VECTOR_COMMITTED
  ↓
GRAPH_COMMITTED
  ↓
COMPLETED
```

失败时记录明确状态并执行幂等补偿：

- 删除已写入的向量记录。
- 删除当前 `sourceId` 派生的图谱关系。
- 保留原始失败原因。
- 重试只继续未完成步骤。

首版图谱结构化写入可独立运行，不强制与文档上传绑定。

---

## 15. 安全与数据治理

- tenant 范围必须在服务端认证上下文中确定。
- Schema 和 subject 访问权限必须在查询前校验。
- 所有 Cypher 使用参数化查询。
- 关系类型、标签、排序字段必须来自 Schema 白名单。
- 只读查询设置超时、最大深度和最大返回行数。
- 写入属性根据 Schema 执行允许字段过滤。
- 不向 LLM 暴露 Schema 标记为敏感的属性。
- 不在 trace、日志和错误响应中输出完整敏感节点。
- 图谱查询 trace 只记录 queryId、耗时、计数和脱敏范围。
- 删除请求记录操作者、范围、模式和结果。
- 图谱管理 API 复用现有认证机制，并增加 Schema 级授权。
- 任何用户输入、文档文本或 LLM 输出都不能提升图谱访问范围。

---

## 16. 前端管理界面

首版提供基础管理能力，不把图谱可视化作为检索实现的前置条件。

### 16.1 页面

- Provider 状态。
- Schema 列表。
- 节点分页列表。
- 关系分页列表。
- 单节点邻域查看。
- 查询模板测试。
- 写入和删除错误展示。

### 16.2 自适应

- 桌面端使用列表与详情双栏布局。
- 窄屏切换为列表和详情独立页面。
- 图谱可视化区域允许缩放和全屏，不强制固定宽高。
- 大图只加载当前分页或当前深度，禁止一次渲染整个图库。
- 请求、空数据、权限拒绝和 Provider 未启用状态分别渲染。

### 16.3 数据类型

- 前端类型严格对齐后端 DTO。
- `GraphRouteResult`、`GraphPageInfo`、节点和关系分别定义类型。
- 读取 API 响应时确认 `res` 中准确的数据位置。
- 节点属性使用 `Record<string, unknown>`，消费前按 Schema 转换，不直接断言具体类型。

---

## 17. 实施阶段

### Phase 1：通用模型与模块骨架

- [ ] 新增 `harness-graph` Maven 模块。
- [ ] 增加通用节点、关系、路径、聚合和分页模型。
- [ ] 增加 `KnowledgeGraphStore`。
- [ ] 增加 `GraphSchemaDefinition` 和 `GraphSchemaProvider`。
- [ ] 增加 `GraphRequestContext`。
- [ ] 增加 `KnowledgeRouteDecision`。
- [ ] 增加 `NoOpKnowledgeGraphStore`。
- [ ] 为模型校验和分页模型增加单元测试。

### Phase 2：Neo4j Provider 与 Docker

- [ ] 引入 Neo4j Java Driver。
- [ ] 实现统一 Driver 生命周期和连接配置。
- [ ] 实现 `Neo4jKnowledgeGraphStore`。
- [ ] 实现事务批量 `MERGE` 节点和关系。
- [ ] 实现参数化查询和只读事务。
- [ ] 创建唯一约束和必要索引初始化器。
- [ ] 增加 Neo4j Compose Profile。
- [ ] 增加数据卷、日志卷和健康检查。
- [ ] 增加 Provider 启动校验。
- [ ] 增加 Neo4j 集成测试。

### Phase 3：Schema、写入与管理 API

- [ ] 实现 Schema 注册表。
- [ ] 实现节点、关系和属性白名单校验。
- [ ] 实现事务 Mutation API。
- [ ] 实现 requestId 幂等。
- [ ] 实现节点游标分页。
- [ ] 实现关系游标分页。
- [ ] 实现受控删除模式。
- [ ] 实现 Schema 查询 API。
- [ ] 增加 API DTO、异常映射和权限测试。

### Phase 4：独立图谱检索路线

- [ ] 实现 `GraphQueryPlan`。
- [ ] 实现 `AnchoredNeighborhoodQueryStrategy`。
- [ ] 实现 `RegisteredTemplateQueryStrategy`。
- [ ] 实现 `GraphKnowledgeRetriever`。
- [ ] 实现路径去重、深度限制和确定性排序。
- [ ] 实现 `GraphRouteResult`。
- [ ] 增加图谱上下文长度限制。
- [ ] 验证图谱路线不调用向量 rerank。

### Phase 5：路线协调与上下文组装

- [ ] 实现 `KnowledgeRetrievalCoordinator`。
- [ ] 实现 vector / graph / both / none 路线决策。
- [ ] 实现 `KnowledgeContextAssembler`。
- [ ] 实现通用 `GraphResultFormatter`。
- [ ] 支持业务注入 Schema 专用 Formatter。
- [ ] 保持专业文档知识与结构化图谱信息分区。
- [ ] 增加 GraphRequestContext 缺失时的明确行为测试。

### Phase 6：前端与运维

- [ ] 增加图谱 Provider 状态页。
- [ ] 增加 Schema、节点和关系分页管理页。
- [ ] 增加单节点邻域查看。
- [ ] 增加自适应布局。
- [ ] 增加生产端口、认证、持久化和备份说明。
- [ ] 增加图谱功能示例，但不使用具体儿童业务模型。

### Phase 7：后续可选扩展

- [ ] 结构化数据库到图谱的 Outbox Adapter。
- [ ] 代码 AST 图谱 Adapter。
- [ ] 规则型实体关系抽取器。
- [ ] 本地模型实体关系抽取器。
- [ ] 受控 Text-to-GraphQuery。
- [ ] 图谱可视化编辑器。
- [ ] GDS 图算法 Provider。
- [ ] Neo4j Enterprise/集群部署文档。

---

## 18. 测试与验收

### 18.1 配置测试

- [ ] `provider=none` 时不创建 Neo4j Driver。
- [ ] `provider=none` 时现有向量知识库测试全部通过。
- [ ] `provider=neo4j` 缺少密码时启动失败。
- [ ] Provider 非法时启动失败。
- [ ] limit、depth 和 timeout 非法时启动失败。
- [ ] Neo4j 不可连接时不静默降级。

### 18.2 事务与一致性测试

- [ ] 节点批量写入任一项失败时全部回滚。
- [ ] 关系引用不存在节点时整批回滚。
- [ ] 相同 requestId 重试不重复写入。
- [ ] 删除模式按预期处理关联关系。
- [ ] tenant/schema 范围外数据不受删除影响。
- [ ] 补偿操作可重复执行。

### 18.3 查询与分页测试

- [ ] 节点列表游标分页无重复、无遗漏。
- [ ] 关系列表游标分页无重复、无遗漏。
- [ ] 非法排序字段被拒绝。
- [ ] 最大深度不能由请求参数突破。
- [ ] 最大 limit 不能由请求参数突破。
- [ ] 查询模板始终注入 tenant/schema/subject 条件。
- [ ] 任意 Cypher 输入被拒绝。
- [ ] 超时查询能够及时中止并返回明确异常。

### 18.4 权限测试

- [ ] 用户不能访问其他 tenant 的节点和关系。
- [ ] 用户不能通过 nodeId 绕过 tenant 过滤。
- [ ] 用户不能通过查询模板参数切换 schema。
- [ ] LLM 参数不能覆盖 GraphRequestContext。
- [ ] 敏感属性不进入 LLM 上下文。
- [ ] 错误响应不泄露认证信息和完整 Cypher。

### 18.5 检索路线测试

- [ ] vector 路线保持现有 rerank 行为。
- [ ] graph 路线不调用 `RerankModelProvider`。
- [ ] graph 路线不生成伪 Chunk。
- [ ] graph-only 查询只产生图谱上下文。
- [ ] vector-only 查询只产生文档上下文。
- [ ] both 查询保持两个独立上下文分区。
- [ ] 图谱为空不影响向量路线。
- [ ] 向量为空不影响图谱路线。
- [ ] 没有合法 GraphRequestContext 时不查询个体图谱。

### 18.6 Docker 测试

- [ ] 普通 `docker compose up -d` 不启动 Neo4j。
- [ ] `docker compose --profile graph up -d` 启动 Neo4j。
- [ ] Neo4j 重启后数据仍然存在。
- [ ] cyrene-agent 通过 Docker 内网连接 Bolt。
- [ ] 未提供密码时 Compose 或应用明确失败。
- [ ] 生产示例不公开暴露 Browser 和 Bolt。

---

## 19. 完成标准

TODO9 完成必须同时满足：

1. Neo4j 是可选 Provider，默认关闭。
2. 未启用图谱时，现有 Milvus/pgvector RAG 行为不变。
3. 框架核心不存在儿童、教师、能力等具体领域类和硬编码。
4. 业务可以通过 Schema、结构化 Mutation 和查询策略接入自己的图谱。
5. Neo4j 返回节点、关系、路径和聚合结果，不伪装为 Chunk。
6. 图谱结果不进入向量 rerank，也不与向量分数融合。
7. 两条路线只在最终上下文中分区汇合。
8. 所有列表查询支持游标分页。
9. 所有图谱写入和删除使用事务，并支持幂等。
10. 跨存储一致性通过状态机和补偿事务处理。
11. 所有查询强制 tenant、schema、subject 范围。
12. LLM、前端和 HTTP 调用方均不能提交任意 Cypher。
13. Provider 启用但不可连接时明确失败，不静默降级。
14. Neo4j 可以通过 Docker Compose Profile 启动并持久化数据。
15. 前端管理界面自适应，并严格对齐后端 DTO。
16. 首版不依赖 LLM 自动建图，不安装非必要 Neo4j 插件。

