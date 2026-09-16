# TODO12：基于 LLM Wiki 与 OKF 的统一知识编译系统重构

## 0. 文档定位

本计划重构 Cyrene Agent 的长期记忆、文档知识库和知识图谱协作方式。目标不再是分别维护“偏好表、情景向量、操作经验向量、文档 Chunk、Neo4j 图数据”五套互不理解的数据，而是建立统一的知识编译与来源治理系统：

```text
不可变证据
  → 增量 Knowledge Compiler
  → Cyrene Concept / Revision（可导出为 OKF）
  → Wiki 当前视图与索引
  → 专用投影：Milvus / pgvector / Neo4j
  → 渐进式发现与本轮上下文
```

LLM Wiki 提供“原始资料不可变、知识增量编译、持续修订与维护”的方法；OKF v0.2 提供 `sources`、`generated`、`verified`、`status`、`stale_after` 等可交换语义。Cyrene 只继承 LLM Wiki 的增量编译、页面组织、交叉引用和来源维护思想，不采用仅靠 `index.md + Markdown Link + 文件文本搜索` 的召回方式：Wiki Catalog、Source Document Chunk、用户情景和操作经验的语义发现始终指向 Milvus/pgvector 向量 RAG；MySQL 管理权威状态，Neo4j 继续管理具有 Schema、事务和路径查询能力的确定性图数据。Cyrene 不把 Markdown 文件或 Git 当作在线事务数据库，也不把文档向量、Wiki Concept 和记忆强行伪装成 Neo4j 节点。

参考：

- [Andrej Karpathy: LLM Wiki](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f)
- [Google Open Knowledge Format v0.2](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md)

当前源码和测试仍是实施时的事实来源。实施前必须再次核对接口、依赖、数据库版本和当前数据状态。

### 0.1 已确认决策

1. **短期记忆保持现状。** Session 消息继续存储在 MySQL，使用当前内存/Redis Session Cache，并由现有压缩机制控制上下文长度，不改成固定五条窗口。
2. **原始证据不可变。** Session Message、Trace、用户反馈和业务结果是事实来源；长期知识只能引用来源，不能反向修改来源。
3. **三类长期记忆统一为 Knowledge Concept。** 用户偏好、用户情景和系统操作经验分别使用 `User Preference`、`User Episode`、`Operation Playbook` 类型，但共享 Concept、Revision、Source、Verification、Link 和生命周期模型。
4. **MySQL 是权威状态源。** 当前有效 Revision、权限范围、失效状态、来源、验证和索引任务均以 MySQL 为准。
5. **向量 RAG 是固定语义发现路径。** Wiki Catalog、文档 Chunk、用户情景和操作经验始终投影到当前选择的 Milvus/pgvector Provider；向量库是可重建检索投影，不决定哪个版本有效，不直接承担跨记录事务，也不作为唯一事实源。
6. **Wiki 是编译视图，不是第四套记忆。** Wiki Index、主题页、用户当前偏好视图和操作 Playbook 都由 Concept 当前 Revision 编译或按需生成。
7. **OKF 是语义与交换边界。** 在线运行使用关系表和向量索引；Markdown + YAML OKF Bundle 用于导入、导出、人工审阅和跨系统交换。
8. **新消息采用增量批次处理。** Worker 领取任务时冻结 `cutoffMessageId`；期间新增消息进入下一批，不取消或重启当前批。
9. **提取水位只在完整提交后前进。** `watermarkMessageId` 表示已经成功编译的最后一条消息；失败或部分写入不能推进水位。
10. **偏好是用户 1:N 的知识概念。** 每个注册 `preferenceKey` 对应一个 Concept；无法映射到注册 Key 的偏好进入该用户唯一的 `other` Concept。用户表达永远作为来源追加，当前偏好通过不可变 Revision 演化，不覆盖来源、不同时注入冲突旧版本。
11. **操作经验以 Playbook 修订。** 新经验可以创建 Playbook，也可以为已有 Playbook 生成新 Revision；旧 Revision 保留历史，旧向量通过 Outbox 异步删除。
12. **删除默认采用生命周期失效。** `deprecated` 立即从权威召回中失效，物理向量删除由异步索引任务完成。
13. **用户情景与系统操作经验物理索引分离。** 二者权限和召回策略不同，不能仅依靠 `conceptType` 混入同一个向量 Collection。
14. **所有新增 `tenantId` 均可空。** 非多租户系统保存 `NULL`；多租户系统保存可信代理端透传并校验后的租户 ID。
15. **身份不能由模型提供。** `userId` 来自认证结果，`tenantId` 来自可信代理边界；模型不得扩大作用域。
16. **不增加 `agentId` 和 `applicationId`。** 当前框架面向一个主 Agent，不为假设中的多 Agent、多应用提前增加作用域。
17. **长期记忆总预算固定为模型上下文窗口的 3%。** 各类型共享预算，不为了用满预算注入低相关或失效知识。
18. **时间不能覆盖相关性。** 先满足相关性、权限、有效版本、信任和 Tool 可用性，再在近似相关候选中选择较新 Revision。
19. **操作经验每次最多注入一个 Playbook。** 用户情景允许多条；偏好读取当前 Wiki View，不读取冲突历史。
20. **提取前评分，提取后只校验。** Trace 先通过确定性规则与评分；模型负责结构化理解和策略抽象，输出后不再进行第二次质量评分。
21. **主 Agent 与全部子 Agent Run 独立评分后取算术平均。** 每个实际 Run 只计算一次，失败子 Run 计 0，不为主 Agent 或某类子 Agent 额外加权。
22. **现有长期记忆直接替换。** 删除旧聚合偏好、请求触发式提炼、旧质量打分和 `update_memory` Tool，不保留隐式双读或静默回退。
23. **文档知识库纳入统一来源与版本模型。** 原始上传文件成为不可变 Knowledge Artifact；文档更新产生新的 Source Document Revision，并重新投影全部 Chunk，不允许继续直接编辑单个 Chunk 造成来源失真。
24. **Wiki 位于文档 Chunk 之上。** Source Document、Topic Synthesis、Entity Reference、API Reference 等 Concept 为 Agent 提供已经编译的知识；原始 Chunk 继续作为可追溯证据和精确上下文窗口。
25. **Neo4j 保持图数据权威。** Graph Schema、Graph Space 和少量高价值 Entity Reference 可以编译为 Wiki Concept，但具体节点、关系和路径仍由 Neo4j 事务与 Schema 约束管理。
26. **统一发现，不统一索引和执行引擎。** `KnowledgeDiscoveryRouter` 编排 Wiki Catalog、用户情景索引、操作经验索引和图谱入口；Catalog 本身只路由文档 Concept 与图谱目录，不复制记忆。文档使用 Dense/BM25/Rerank，图谱使用确定性节点与邻域查询，禁止把图结果塞进向量 Rerank。
27. **Graph 写入使用幂等 Saga。** 先记录持久 Mutation Job，再以现有 `requestId` 幂等提交 Neo4j，成功后事务写入来源绑定、Wiki Revision 和索引 Outbox；崩溃恢复时重放同一 `requestId`。
28. **知识管理从 Chunk 级提升到 Asset/Concept 级。** 列表、更新、失效和删除默认面向文档或 Concept；Chunk 只作为只读投影管理，不作为人工事实编辑入口。
29. **文件名只用于发现，不承担来源身份。** Source Document 的稳定身份是 `conceptId/documentId`，具体版本由 `revisionId` 锁定，原始字节由 `artifactId` 定位，Chunk 由 `chunkId` 定位；`fileName` 允许重名和变化，只用于展示、关键词检索和管理过滤，不能作为主键、幂等键或版本判断依据。
30. **模型只看到两个统一知识工具。** `knowledge_search` 负责发现并返回类型化 Handle，`knowledge_read` 负责按 Handle 有界读取；文档、图谱、Catalog 和记忆仍由内部独立执行器处理。旧 `knowledge_base_search/knowledge_context_read/knowledge_graph_search` 不再作为模型可见 Tool 暴露。
31. **OKF 不是在线检索 Tool。** 在线请求不先把 SQL、Milvus 和 Neo4j 内容转换成 OKF；OKF 只用于选定 Concept/Revision 的导入、导出、人工审阅和跨系统交换。
32. **偏好 Key 与注入时机分离。** `preferenceKey` 负责定位冲突域和当前 Head，`activationTags` 负责决定本轮是否适用；只有匹配当前意图、输出类型和可用 Tool 的偏好才进入 3% 候选。
33. **当前迁移目标由配置明确指定。** 新 Collection 必须创建在 `HARNESS_RAG_DATABASE` 指定的 Database；当前计划实例为 `cyrene_test`。宿主机上由旧项目 Docker 映射的 `zhiduyuan.knowledge` 只作为只读样例数据，不得因为“其中有数据”而被自动选中、迁移、重建或删除。

---

## 1. 当前实现基线与重构边界

### 1.1 保留的短期链路

```text
SessionStore / MessageStore（MySQL）
  → SessionMessageCache（Redis 或内存）
  → SessionContextLoader
  → MemoryCompressor
  → ReAct historyMessages
```

保留：

- `SessionContextLoader` 的 Cache First、Database Refill。
- `MemoryCompressor` 的上下文压力触发压缩。
- Tool 消息编码、会话恢复和 Provider Prompt Cache 观测。
- `MessageWriteWorker`，但伪批量逐条提交必须改为真实事务批量写入。

短期上下文不分配固定百分比，继续由上下文压力和压缩控制。

### 1.2 需要替换的旧长期记忆

- `PreferenceRefinementWorker` 把身份、偏好、习惯、项目和目标合并成一条自由文本。
- `PreferenceStore.upsert(userId, "memory", ...)` 覆盖历史。
- `AgentPromptBuilder` 把动态长期记忆放进 System Prompt，破坏稳定缓存前缀。
- `SessionLifecycleManager` 的字符数、问号、回复长度等旧筛选分。
- 请求路径和清理 Scheduler 重复提交提炼任务。
- 进程内队列退出后丢失任务。
- `update_memory` Tool 允许模型直接写长期记忆。
- Session 只能提取一次，后续消息永久遗漏。
- 向量记录写入后缺少来源版本、当前状态和可靠删除同步。

### 1.3 可复用能力

- MySQL Session、Message、Trace 持久化。
- Milvus Dense、BM25 Sparse、Hybrid Search、RRF 和显式 ID Upsert。
- pgvector Vector、全文检索和混合召回能力。
- Embedding Provider、连接池、Token Estimator。
- Trace 主子 Run 关系、Tool Catalog 和 ReAct 状态。

现有 `VectorStore.Document` 面向知识库 Chunk，不能继续把长期记忆伪装成知识文档。新系统增加独立的 Knowledge Repository、Compiler 和 Index Projection 接口。

---

## 2. 目标架构

```text
可信请求边界
  ├─ authenticated userId
  └─ optional tenantId
          │
          ├──────────────────────────────────────────┐
          ▼                                          ▼
Session 短期记忆                              每轮长期知识召回
  ├─ MySQL messages                           ├─ MySQL Wiki View
  ├─ Redis / memory cache                     ├─ User Knowledge Index
  └─ context-pressure compression             └─ Operation Knowledge Index
          │                                          │
          ▼                                          ▼
每日增量扫描                                  权威 Revision 校验
  ├─ >= 3 完整轮                              + 3% Token Allocator
  ├─ idle >= 1 hour                                  │
  ├─ watermark / cutoff                              ▼
  └─ Extraction Batch                       dynamicMemoryContext
          │
          ▼
不可变证据快照
  ├─ Session Messages
  ├─ filtered Trace tree
  └─ feedback / business validation
          │
          ▼
Knowledge Compiler
  ├─ 规则筛选与 Trace 确定性评分
  ├─ 单次结构化提取
  ├─ Concept 匹配与冲突识别
  ├─ 新建 Concept 或 Revision
  └─ MySQL 事务 + Outbox
          │
          ├──────────────────────┐
          ▼                      ▼
Wiki / OKF 当前知识             Index Projector
  ├─ User Preference             ├─ Milvus
  ├─ User Episode                └─ pgvector
  └─ Operation Playbook
```

### 2.1 三类 Concept

| `conceptType` | 归属 | 典型来源 | 当前视图 | 检索方式 |
|---|---|---|---|---|
| `User Preference` | `userId + optional tenantId` | 用户消息、用户确认 | 当前偏好 Wiki View | MySQL 精确/有界读取 |
| `User Episode` | `userId + optional tenantId` | Session 消息、业务结果 | 用户事件与决定页 | Dense + BM25 |
| `Operation Playbook` | 全局或 `optional tenantId` | 通过规则筛选的 Trace | 可复用操作策略页 | Dense + BM25 + Tool 过滤 |

系统操作经验不按用户隔离。生成 Playbook 前必须移除用户、订单、手机号、凭证和实例业务值，只保留适用条件、错误特征、信息流、工具角色、决策顺序、验证条件和恢复策略。

### 2.2 权威数据与投影

```text
MySQL Concept currentRevisionId / status
    = 当前有效知识的唯一权威

Milvus / pgvector records
    = 可重建的搜索投影

OKF Markdown Bundle
    = 可移植交换和审阅产物
```

不得从 Milvus 的“最新时间”推断当前版本，也不得因向量删除延迟把旧 Revision 重新视为有效。

### 2.3 现有知识库链路与问题

当前代码的知识库主链路是：

```text
KnowledgeUploadHandler
  → DocumentConversionService 转 canonical Markdown
  → TextChunker 按语义块打包
  → EmbeddingProvider
  → FileStorageService 保存原文件
  → VectorStore.upsert 写入 Chunk

KnowledgeBaseTool
  → 单查询或 LLM Query Rewrite
  → VectorStore.searchTextWithEvidence
  → Reranker
  → documentId + chunkIndex
  → KnowledgeContextReadTool 显式读取相邻窗口
```

现有语义块切分、稳定 `documentId/chunkIndex`、显式上下文窗口和 Rerank 都应保留；需要修复的是上层知识治理：

- 上传后只有磁盘文件和向量 Chunk，没有文档级权威 Registry、Revision、Source 和生命周期。
- `documentId` 每次上传随机生成，无法表达“同一文档的新版本”。
- 管理 API 可以直接更新单个 Chunk，原文件、相邻 Chunk、标题摘要和 Embedding 会失去一致性。
- 删除单个 Chunk 不会同步文档级来源；删除 Collection 又是向量库和文件存储的顺序操作，缺少统一状态。
- Query Rewrite 每次仍从原始 Chunk 重新发现跨文档知识，没有持续积累 Topic Synthesis、术语页和实体页。
- 上传 Trace 保存失败目前只告警，来源审计可能无声缺失。

Wiki 优化后，原始文档仍然存在并可精确引用，但常见问题优先命中已经编译的 Concept；只有需要定义原文、完整步骤、前后章节或证据核验时才读取 Chunk。

### 2.4 现有知识图谱链路与问题

当前图谱读取链路是：

```text
无可信 Graph Scope：
  listGraphSpaces → findNodes → findNeighborhood

可信 Graph Space：
  findNodes → findNeighborhood

可信 Subject Scope：
  findNeighborhood
```

当前图谱构建链路是：

```text
Natural Language
  → LlmGraphDataConverter
  → Schema-constrained Preview
  → 人工确认 Structured JSON
  → GraphBuildService
  → Neo4j applyChanges / upsertBatch
```

现有可信作用域覆盖模型参数、Schema 校验、预览确认、`GraphChangeSet` 事务和 `requestId` 幂等 Mutation 记录都必须保留。Wiki 的优化点是：

- 为 Graph Schema 和 Graph Space 建立可搜索、可解释、可版本化的目录页，减少 Agent 为理解图域而机械遍历 Space。
- 为高价值实体域、关系语义和常用查询建立 Entity Reference / Relationship Guide，不把所有 Neo4j 节点复制成 Markdown。
- 把图变更绑定到 Source Revision 和 Mutation Receipt，回答“这个节点或关系从哪里来、何时写入、是否已失效”。
- 图谱查询仍由 Neo4j 完成；Wiki 只负责发现正确 Graph Space、Schema、查询策略和可解释来源。

### 2.5 扩展后的 Concept 类型

| `conceptType` | 作用 | 主事实源 | 专用投影 |
|---|---|---|---|
| `Source Document` | 一个可版本化文档的目录页、摘要与当前来源 | Knowledge Artifact | 文档 Chunk Index |
| `Topic Synthesis` | 跨多个来源持续修订的主题综合 | 多个 Source Revision | Wiki Concept Index |
| `Entity Reference` | 人可读实体说明，可绑定文档和图节点 | 文档/图谱/业务来源 | Wiki Index + optional Graph Binding |
| `API Reference` | 已有系统 API、参数、认证和使用说明 | OpenAPI/源码审阅结果 | Wiki Concept Index |
| `Graph Schema` | Schema 的人可读说明和版本 | Graph Schema Repository | Wiki Concept Index |
| `Graph Space` | 图空间用途、范围、Schema 和入口实体 | Neo4j + Access Binding | Wiki Concept Index |
| `User Preference` | 用户当前稳定偏好 | Session Message | MySQL Wiki View |
| `User Episode` | 跨会话事件、决定和承诺 | Session/业务结果 | User Knowledge Index |
| `Operation Playbook` | 可复用 Agent 操作策略 | Trace Tree | Operation Knowledge Index |

默认不为每个原始 Chunk、每个普通图节点或每次 Tool 调用创建 Concept。Concept 是已经编译、值得跨请求维护的知识单元；Chunk、Graph Node、Trace Step 是证据或专用投影记录。

### 2.6 统一 Knowledge Fabric

```text
Evidence / Artifact Layer
  ├─ immutable uploaded files
  ├─ canonical Markdown revisions
  ├─ Session Messages
  ├─ Trace Trees
  ├─ Graph Mutation Receipts
  └─ business-system facts
              │
              ▼
Concept / Revision Layer（MySQL）
  ├─ provenance / verification / lifecycle
  ├─ cross-links and graph bindings
  └─ current revision authority
              │
      ┌───────┼──────────────────┐
      ▼       ▼                  ▼
Wiki Index  Document/Memory     Neo4j
            Vector Projections  Graph Projection
      └───────┬──────────────────┘
              ▼
Knowledge Discovery Router
  ├─ compiled concept answer
  ├─ source chunk read
  ├─ user memory context
  └─ deterministic graph expansion
```

这里的“统一”发生在 Concept ID、Revision、Source、权限、生命周期和发现协议；底层检索与事务引擎继续按数据形态分工。

---

## 3. 身份、租户和访问边界

### 3.1 可空租户

```text
tenantId == NULL  → 非多租户用户数据或全局系统知识
tenantId != NULL  → 对应租户范围
```

记忆链路增加明确的 `optionalTenantId()`；不得复用图谱兼容逻辑中的默认租户值冒充真实租户。

### 3.2 作用域规则

用户知识：

```text
tenantId 有值：tenant_id = requestTenantId AND user_id = authenticatedUserId
tenantId 为空：tenant_id IS NULL AND user_id = authenticatedUserId
```

操作 Playbook：

```text
tenantId 有值：tenant_id = requestTenantId OR tenant_id IS NULL
tenantId 为空：tenant_id IS NULL
user_id 必须为 NULL
```

文档知识库：

- Collection 继续由可信服务端范围或 `KnowledgeCollectionAccessService` 授权；模型提供的 `collection` 只能在允许集合内选择。
- Catalog 中的 Source/Topic/API Concept 必须继承可读 Collection 范围，不能因为进入统一索引而变成全局可见。

图谱 Catalog：

- Graph Schema/Space/Entity Concept 的候选必须再次通过 `GraphSpaceAccessService.requireReadable/listReadable`。
- 图谱现有默认租户兼容语义继续由 Graph Access Service 决定，不能简单套用记忆的 `tenantId IS NULL` 规则。
- 服务端 `GraphRequestContext` 始终优先于 Wiki 路由提示。

### 3.3 Session 所有权

恢复 Session 时必须同时验证：

```text
session.userId == authenticatedUserId
AND tenantId null-safe equality
```

增加 `findByIdAndOwner` / `findActiveByOwner`，禁止先按 Session ID 查询后默认信任。管理 API 同样不能从 Query 参数自由指定其他用户或租户。

### 3.4 OKF 信任不等于权限

OKF 的 `verified` 只表达知识验证层级，不是访问控制。所有检索、导出、管理和删除仍必须先应用 Cyrene 的认证用户、租户和 Tool Catalog 权限。

---

## 4. MySQL 权威数据模型

所有增长型列表查询使用稳定游标、明确 `ORDER BY` 和 `limit + 1`。所有关联创建、修订、失效和 Outbox 写入使用显式事务。

### 4.1 `sessions` 提取水位

```sql
ALTER TABLE sessions
    ADD COLUMN tenant_id VARCHAR(128) NULL,
    ADD COLUMN memory_extraction_watermark_message_id BIGINT NULL,
    ADD COLUMN memory_extraction_last_completed_at DATETIME(3) NULL,
    ADD INDEX idx_session_memory_scan
        (last_active, id),
    ADD INDEX idx_session_owner_active
        (tenant_id, user_id, last_active, id);

ALTER TABLE messages
    ADD COLUMN trace_id VARCHAR(64) NULL,
    ADD INDEX idx_message_session_trace
        (session_id, trace_id, id);
```

`watermark_message_id` 表示最后一次成功提交的消息边界，不再用 `done` 表示 Session 永久完成。

新请求在创建 `RunTrace` 后，将同一个 root `traceId` 传给 user、assistant 和 Tool Message 的异步持久化任务；旧消息允许 `trace_id=NULL`。这样操作经验提取可以用本批 user Message 的稳定 ID 精确找到 root Trace，再通过 `parentTraceId/parentRunId` 展开子 Agent 树，不按输入文本相等或时间相邻猜测 Trace。`MessageWriteWorker` 每次领取的相关写入使用真实批事务并显式报告失败，但不跨整个 LLM 执行持有数据库事务；缺少最终 assistant Message 的轮次自然保持未完成且不能进入 cutoff。

### 4.2 `memory_extraction_batches`

```sql
CREATE TABLE memory_extraction_batches (
    id                       VARCHAR(64)  NOT NULL,
    session_id               VARCHAR(64)  NOT NULL,
    tenant_id                VARCHAR(128) NULL,
    user_id                  VARCHAR(128) NOT NULL,
    from_message_id          BIGINT       NOT NULL,
    cutoff_message_id        BIGINT       NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    extractor_version        VARCHAR(64)  NOT NULL,
    attempts                 INT          NOT NULL DEFAULT 0,
    available_at             DATETIME(3)  NOT NULL,
    claimed_at               DATETIME(3)  NULL,
    started_at               DATETIME(3)  NULL,
    completed_at             DATETIME(3)  NULL,
    error_message            VARCHAR(1024) NULL,
    created_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_batch_range
        (session_id, from_message_id, cutoff_message_id, extractor_version),
    INDEX idx_memory_batch_claim
        (status, available_at, id),
    INDEX idx_memory_batch_session
        (session_id, cutoff_message_id, id)
);
```

状态：

```text
pending / in_progress / succeeded / failed
```

`pending` 可领取，`in_progress` 正在处理，`succeeded/failed` 为终态。任务领取使用 `SELECT ... FOR UPDATE SKIP LOCKED` 或 CAS；多实例只能有一个 Worker 领取同一批次。可重试错误在达到最大次数前重新置为 `pending` 并推进 `available_at`；达到上限后进入 `failed`，不推进水位，必须告警并通过显式管理操作重放。

### 4.3 `knowledge_concepts`

```sql
CREATE TABLE knowledge_concepts (
    id                   VARCHAR(64)  NOT NULL,
    tenant_id            VARCHAR(128) NULL,
    user_id              VARCHAR(128) NULL,
    namespace_type       VARCHAR(32)  NOT NULL,
    namespace_key        VARCHAR(256) NULL,
    concept_type         VARCHAR(64)  NOT NULL,
    logical_key          VARCHAR(256) NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'draft',
    current_revision_id  VARCHAR(64)  NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    stale_after          DATETIME(3)  NULL,
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    INDEX idx_concept_user_type_key
        (tenant_id, user_id, concept_type, logical_key, id),
    INDEX idx_concept_scope_status
        (tenant_id, user_id, namespace_type, concept_type, status, updated_at, id),
    INDEX idx_concept_namespace
        (tenant_id, namespace_type, namespace_key, concept_type, id),
    INDEX idx_concept_current_revision (current_revision_id)
);
```

约束由 Repository 在事务中校验：

- `User Preference`、`User Episode`：`user_id` 必填。
- `Operation Playbook`：`user_id IS NULL`。
- 文档 Concept 使用 `namespace_type=COLLECTION`、`namespace_key=collectionKey`。
- 图谱 Concept 使用 `namespace_type=GRAPH`、`namespace_key=graphId + ':' + schemaId`。
- 用户与操作记忆分别使用 `USER_MEMORY/OPERATION_MEMORY`，不能借 `namespaceKey` 绕过 Owner 过滤。
- `User Preference.logical_key` 必填。
- `status` 只允许 `draft/stable/deprecated`。
- 当前 Revision 更新使用 `WHERE id=? AND version=?` 乐观锁，失败重新读取后重试编译，不能覆盖并发修订。

偏好 Concept ID 使用作用域和 `preferenceKey` 生成确定性 SHA-256，从物理上避免可空租户造成的唯一约束漏洞：

```text
SHA-256("preference" + normalizedTenant + userId + preferenceKey)
```

### 4.4 `knowledge_revisions`

```sql
CREATE TABLE knowledge_revisions (
    id                   VARCHAR(64)   NOT NULL,
    concept_id           VARCHAR(64)   NOT NULL,
    revision_number      BIGINT        NOT NULL,
    title                VARCHAR(512)  NOT NULL,
    description          VARCHAR(2048) NULL,
    body                 MEDIUMTEXT    NOT NULL,
    generated_by         VARCHAR(256)  NOT NULL,
    generated_at         DATETIME(3)   NOT NULL,
    content_hash         VARCHAR(64)   NOT NULL,
    metadata             JSON          NULL,
    created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_concept_revision (concept_id, revision_number),
    INDEX idx_revision_concept_time
        (concept_id, revision_number DESC, id)
);
```

Revision 只能插入，不能原地修改。格式错误时创建新的修订，不执行覆盖更新。

`User Preference` 的运行时结构保存在 `metadata.preference`，不能在召回时重新解析自然语言 Markdown：

```json
{
  "preference": {
    "preferenceKey": "response.verbosity",
    "valueText": "普通回答简洁，架构设计允许展开",
    "activationTags": ["GENERAL_RESPONSE"],
    "items": []
  }
}
```

`other` 使用相同结构，但 `valueText` 为 `null`，`items` 保存完整的 `itemId/statement/activationTags` 数组。`body` 是可读 Wiki 表达，`metadata.preference` 才是后端合并、过滤和注入的结构化输入；两者由同一次 Compiler 提交生成并做一致性校验。注册 Key 运行时以当前 `PreferenceKeyRegistry` 激活策略为准，Revision 中的标签是生成时快照；`other` 没有固定 Key 策略，因此以条目保存的标签为准。

### 4.5 `knowledge_revision_sources`

```sql
CREATE TABLE knowledge_revision_sources (
    revision_id          VARCHAR(64)  NOT NULL,
    source_type          VARCHAR(32)  NOT NULL,
    source_id            VARCHAR(128) NOT NULL,
    source_resource      VARCHAR(1024) NOT NULL,
    observed_at          DATETIME(3)  NOT NULL,
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (revision_id, source_type, source_id),
    INDEX idx_knowledge_source_lookup
        (source_type, source_id, revision_id),
    INDEX idx_knowledge_source_time
        (revision_id, observed_at, source_id)
);
```

来源类型首期固定为：

```text
SESSION_MESSAGE
TRACE
USER_FEEDBACK
BUSINESS_RESULT
KNOWLEDGE_CONCEPT
KNOWLEDGE_ARTIFACT
```

`source_resource` 使用内部稳定 URI，例如：

```text
cyrene://sessions/{sessionId}/messages/{messageId}
cyrene://traces/{traceId}
cyrene://knowledge/{conceptId}/revisions/{revisionId}
cyrene://artifacts/{artifactId}
```

URI 只作为来源标识，读取仍需权限校验。

来源保留与删除规则：

- `idx_knowledge_source_lookup` 是 Purge Guard 的反向查询入口。Session/Message、Trace、Artifact 和 Graph Mutation Receipt 的普通保留期清理必须先检查是否被当前或历史 Revision 引用；仍被引用时跳过物理清理并记录 retained-by-knowledge 指标。
- 用户主动删除、租户清退或合规删除优先于知识保留。删除服务必须在受控事务/Saga 中找到受影响 Concept，按语义执行重新编译、移除当前 Head 或 `deprecated`，写索引删除 Outbox，再删除原始证据；不能只删 Message/Trace 后留下看似可验证的孤儿知识。
- 普通 Revision 历史清理不能删除 Source 行来伪造“没有来源”；确需缩短历史保留时必须保留不可逆摘要和 `sourceUnavailable` 状态，并确保该 Revision 不再作为 `stable` 当前事实使用。

### 4.6 `knowledge_verifications`

```sql
CREATE TABLE knowledge_verifications (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    revision_id          VARCHAR(64)  NOT NULL,
    verified_by          VARCHAR(256) NOT NULL,
    verification_type    VARCHAR(32)  NOT NULL,
    result               VARCHAR(20)  NOT NULL,
    reason               VARCHAR(1024) NULL,
    verified_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_verification_revision_time
        (revision_id, verified_at DESC, id DESC)
);
```

类型：

```text
TRACE_VALIDATED
USER_CONFIRMED
BUSINESS_CONFIRMED
HUMAN_REVIEWED
```

模型生成本身不算验证。`verifiedBy` 按 OKF Actor 约定使用 `producer/version`、`process:id` 或 `human:id`。

### 4.7 `knowledge_links`

```sql
CREATE TABLE knowledge_links (
    from_concept_id      VARCHAR(64) NOT NULL,
    to_concept_id        VARCHAR(64) NOT NULL,
    link_type            VARCHAR(32) NOT NULL,
    created_at           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (from_concept_id, to_concept_id, link_type),
    INDEX idx_knowledge_link_reverse
        (to_concept_id, link_type, from_concept_id)
);
```

首期关系：

```text
SUPERSEDES
DERIVED_FROM
RELATED_TO
APPLIES_TO
CONTRADICTS
REQUIRES
```

OKF v0.2 的 Markdown Link 是无类型边；上述关系属于 Cyrene 扩展，导出时写入正文和 `x-cyrene-links`。

### 4.8 `knowledge_index_outbox`

```sql
CREATE TABLE knowledge_index_outbox (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    concept_id           VARCHAR(64)  NOT NULL,
    revision_id          VARCHAR(64)  NOT NULL,
    target_index         VARCHAR(64)  NOT NULL,
    operation            VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'pending',
    attempts             INT          NOT NULL DEFAULT 0,
    available_at         DATETIME(3)  NOT NULL,
    claimed_at           DATETIME(3)  NULL,
    completed_at         DATETIME(3)  NULL,
    error_message        VARCHAR(1024) NULL,
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_index_revision_operation
        (concept_id, revision_id, target_index, operation),
    INDEX idx_index_outbox_claim
        (status, available_at, id)
);
```

操作：

```text
UPSERT_CURRENT
DELETE_REVISION
DELETE_CONCEPT
```

Concept/Revision/Source/Verification/Link、Session 水位和 Outbox 必须在同一 MySQL 事务中提交。向量索引不再参与该事务，也不再使用“先写向量、失败后补偿删除”的脆弱流程。

### 4.9 Trace 分页能力

扩展 `TraceStore`：

```java
PageResponse<AgentTrace> findBySession(
        String sessionId,
        TraceCursor cursor,
        int limit
);
```

MySQL 索引：

```sql
INDEX idx_trace_session_time (session_id, timestamp, trace_id)
```

禁止无上限 `listRecent()` 扫描全部 Trace。

### 4.10 `knowledge_artifacts`

原始上传文件和 canonical Markdown 不直接等同于 Concept。新增不可变 Artifact Registry：

```sql
CREATE TABLE knowledge_artifacts (
    id                   VARCHAR(64)   NOT NULL,
    tenant_id            VARCHAR(128)  NULL,
    collection_key       VARCHAR(128)  NOT NULL,
    artifact_type        VARCHAR(32)   NOT NULL,
    file_name            VARCHAR(512)  NOT NULL,
    media_type           VARCHAR(255)  NOT NULL,
    content_hash         VARCHAR(64)   NOT NULL,
    storage_uri          VARCHAR(2048) NOT NULL,
    status               VARCHAR(20)   NOT NULL DEFAULT 'active',
    created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    INDEX idx_artifact_scope_hash
        (tenant_id, collection_key, content_hash, id),
    INDEX idx_artifact_scope_file
        (tenant_id, collection_key, file_name(191), id),
    INDEX idx_artifact_scope_time
        (tenant_id, collection_key, created_at DESC, id DESC)
);
```

- Artifact 内容不可原地修改；新文件或新 Markdown 产生新 Artifact。
- Artifact ID 使用 `SHA-256(normalizedTenant + collectionKey + artifactType + contentHash)`；Repository 以主键实现同作用域内容幂等，不依赖 MySQL 对 `NULL` 的唯一索引语义。
- `file_name` 保存上传时的原始文件名，只用于展示、服务端关键词/前缀过滤和迁移辅助；前缀索引命中后仍比较完整字段，不能用文件名判断是否为同一文档或同一版本。
- `storage_uri` 指向受约束的文件存储路径或对象存储 URI，不把二进制放进 Concept Body。
- `artifact_type` 首期包含 `SOURCE_FILE`、`CANONICAL_MARKDOWN`、`GRAPH_MUTATION_SOURCE`。
- 文件路径继续受配置根目录约束；物理清除必须是独立、明确、可审计的 Purge 操作。
- `Source Document` Revision 通过 `knowledge_revision_sources` 引用 Artifact。

### 4.11 `knowledge_ingest_jobs`

文件存储和 MySQL 不能组成同一个事务，文档上传使用可恢复 Ingest Job：

```sql
CREATE TABLE knowledge_ingest_jobs (
    id                   VARCHAR(64)   NOT NULL,
    artifact_id          VARCHAR(64)   NOT NULL,
    tenant_id            VARCHAR(128)  NULL,
    collection_key       VARCHAR(128)  NOT NULL,
    status               VARCHAR(24)   NOT NULL,
    attempts             INT           NOT NULL DEFAULT 0,
    available_at         DATETIME(3)   NOT NULL,
    converted_artifact_id VARCHAR(64)  NULL,
    source_concept_id    VARCHAR(64)   NULL,
    source_revision_id   VARCHAR(64)   NULL,
    error_message        VARCHAR(1024) NULL,
    created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at         DATETIME(3)   NULL,
    PRIMARY KEY (id),
    INDEX idx_ingest_job_claim
        (status, available_at, id),
    INDEX idx_ingest_job_artifact
        (artifact_id, id)
);
```

状态：

```text
uploaded → converted → compiled → indexed
       ↖ pending retry      ↘ failed（达到上限后的终态）
```

流程：

1. 原文件先写入内容寻址临时路径并原子移动到最终 Artifact 路径。
2. Artifact 与 Ingest Job 在 MySQL 事务中登记。
3. Worker 转换 canonical Markdown，并登记第二个 immutable Artifact。
4. 编译 Source Document Revision，在同一事务写 Source、Outbox 并标记 `compiled`。
5. Document Index Outbox 完成后标记 `indexed`。
6. 文件已落盘但数据库未登记的极少数崩溃窗口，由只扫描配置上传根目录的 Artifact Reconciler 在保留期后报告；可选隔离到已校验的受控 quarantine 目录，但物理 Purge 仍需明确策略/管理操作，不得自动扩大扫描路径或直接删除未知文件。

可重试失败在达到 `HARNESS_KNOWLEDGE_INGEST_MAX_ATTEMPTS` 前按 `available_at` 退避；达到上限进入终态 `failed` 并保留 Artifact、Job 和错误，不静默删除证据。

### 4.12 `knowledge_graph_bindings`

Wiki Concept 与 Neo4j 图目标使用显式绑定，不把图节点正文复制进 MySQL：

```sql
CREATE TABLE knowledge_graph_bindings (
    revision_id          VARCHAR(64)  NOT NULL,
    tenant_id            VARCHAR(128) NULL,
    graph_id             VARCHAR(128) NOT NULL,
    schema_id            VARCHAR(128) NOT NULL,
    target_type          VARCHAR(20)  NOT NULL,
    target_id            VARCHAR(256) NOT NULL,
    request_id           VARCHAR(128) NOT NULL,
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (revision_id, graph_id, schema_id, target_type, target_id),
    INDEX idx_graph_binding_target
        (graph_id, schema_id, target_type, target_id, revision_id),
    INDEX idx_graph_binding_request
        (request_id, revision_id)
);
```

`target_type` 为 `GRAPH_SPACE/NODE/RELATION`。Graph Schema Concept 通过 `schemaId` 资源链接绑定，不要求为 Schema 文件伪造 Neo4j 节点。

### 4.13 `knowledge_graph_mutation_jobs`

MySQL 与 Neo4j 无法组成一个本地事务。利用当前 Neo4j `requestId` 幂等记录实现可恢复 Saga：

```sql
CREATE TABLE knowledge_graph_mutation_jobs (
    request_id           VARCHAR(128) NOT NULL,
    tenant_id            VARCHAR(128) NULL,
    graph_id             VARCHAR(128) NOT NULL,
    schema_id            VARCHAR(128) NOT NULL,
    source_revision_id   VARCHAR(64)  NULL,
    payload_hash         VARCHAR(64)  NOT NULL,
    canonical_payload    JSON         NOT NULL,
    status               VARCHAR(24)  NOT NULL,
    attempts             INT          NOT NULL DEFAULT 0,
    available_at         DATETIME(3)  NOT NULL,
    graph_committed_at   DATETIME(3)  NULL,
    completed_at         DATETIME(3)  NULL,
    error_message        VARCHAR(1024) NULL,
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (request_id),
    INDEX idx_graph_mutation_claim
        (status, available_at, request_id)
);
```

状态：

```text
pending → graph_committed → knowledge_committed
   ↖ pending retry       ↘ failed（达到上限后的终态）
```

流程：

1. 人工确认后的 canonical GraphChangeSet 与 `payloadHash` 事务写入 `pending` Job。
2. Worker 使用相同 `requestId` 调用 Neo4j `applyChanges`。
3. Neo4j 已提交时，重放会读取既有 Mutation Result，不重复写图。
4. MySQL 事务把 Job 标记 `graph_committed`，写入 Graph Binding、Graph Space/Entity Reference Revision 和索引 Outbox。
5. 全部完成后标记 `knowledge_committed`。

同一 `requestId` 携带不同 `payloadHash` 必须拒绝，防止幂等键被复用到不同变更。
`canonical_payload` 必须经过现有 Graph Schema、节点/关系数量和请求体上限校验；不得把无界自然语言源或完整敏感业务响应写入 Job。
可重试失败在达到 `HARNESS_GRAPH_MUTATION_MAX_ATTEMPTS` 前按 `available_at` 退避；达到上限进入终态 `failed`，不得改用新的 `requestId` 绕过同一变更的幂等和审计链。

---

## 5. OKF 映射与 Wiki 编译视图

### 5.1 Concept Revision 到 OKF

导出一个当前 Revision 时映射为：

```yaml
---
type: User Preference
title: Response verbosity
description: Current preference for response detail.
generated:
  by: cyrene-memory-compiler/v1
  at: 2026-09-01T03:00:00Z
verified:
  - by: human:user-123
    at: 2026-09-01T03:10:00Z
status: stable
stale_after: 2027-03-01T00:00:00Z
sources:
  - id: message-101
    resource: cyrene://sessions/s1/messages/101
    last_modified: 2026-09-01T02:20:00Z
x-cyrene-concept-id: "..."
x-cyrene-revision-id: "..."
x-cyrene-logical-key: response.verbosity
---

# Current preference

普通问答倾向简洁；架构设计允许展开。
```

规则：

- `type` 使用三类 Concept 名称。
- `generated` 记录当前内容的生成者和有意义修改时间。
- `verified` 由 Verification 表生成，不能由模型自报。
- `status` 只映射 `draft/stable/deprecated`。
- `sources` 只引用真实存在并通过权限校验的来源。
- Cyrene 特有字段使用 `x-cyrene-*`，消费者必须容忍未知扩展。
- 不导出用户凭证、原始 Token、Cookie、Authorization Header 或未脱敏 Tool 大结果。

### 5.2 Bundle 边界

按权限边界生成 Bundle，而不是把所有用户和租户放进一个目录：

```text
user bundle: tenantId + userId
tenant operation bundle: tenantId
global operation bundle: tenantId NULL
```

Bundle 只在明确导出、人工审阅或离线快照时物化为 Markdown。在线请求不遍历文件系统决定权限或当前版本。

### 5.3 Wiki 当前视图

Wiki View 由当前 Stable Revision 编译：

```text
User Wiki
  ├─ Current Preferences
  ├─ Active Projects / Decisions
  ├─ Episodic Timeline
  └─ Open Commitments

Operation Wiki
  ├─ Playbooks by taskType
  ├─ Failure Recovery
  ├─ Research Before Generation
  └─ Tool Dependency Patterns
```

首期不需要把每个视图永久写成文件。`KnowledgeWikiViewService` 可从当前 Revision 生成有界 Markdown；高频视图后续可以缓存，但缓存键必须包含作用域和 Concept 版本。

### 5.4 Index 与 Log

- Wiki Index 是当前 Concept 的标题、描述、类型、状态和关系的渐进式目录。
- Update Log 来自 Revision、Verification、Deprecation 和 Outbox 事件，不另建不可查询的日志文件作为事实源。
- OKF 导出时可把这些事件生成 `index.md` 和 `log.md`。

### 5.5 文档 Wiki Compiler

文档上传改为：

```text
content-addressed Source Artifact
  → canonical Markdown Artifact
  → Source Document Concept Revision
  → semantic chunks + embeddings（投影）
  → 异步编译 Topic Synthesis / Entity Reference / API Reference
  → Wiki Index 更新
```

规则：

- 原始文件先按 SHA-256 内容寻址，重复上传同一作用域内容可以复用 Artifact，但必须保留本次请求审计。
- 同一逻辑文档更新时创建 Source Document 新 Revision，并对完整 canonical Markdown 重新切块；不修改旧 Chunk。
- Source Document 对外兼容字段 `documentId` 直接使用其稳定 `conceptId`，不再维护另一套随机文档身份；`revisionId` 锁定具体版本，`artifactId` 定位该版本引用的不可变原文件或 canonical Markdown。
- Revision 的 `title` 优先使用用户提供标题或文档内部标题；只能保守去掉路径、扩展名和纯技术分隔符。日期、版本号、“最终版”等是否具有业务意义不能靠通用规则删除，例如“客户会议记录 2026-08-20”必须保留日期。明确存在的 `sourceVersion/effectiveAt` 写入 Revision metadata；不能仅凭文件名猜测版本、生效时间或同一逻辑文档身份。
- Chunk ID 使用 `SHA-256(revisionId + chunkIndex + chunkContentHash)`，重试幂等。
- Source Document 当前 Revision切换与 Document Index Outbox 同事务提交。
- Topic Synthesis 可以引用多个 Source Document Revision；新来源进入时由 Compiler 修订相关主题，而不是每次问答重新跨文档综合。
- 编译出的综合结论必须按 Claim 绑定 Source；无法可靠归因的推断保持 `draft` 或不写入。
- 文档删除先把 Source Document 标记 `deprecated` 并删除当前 Chunk 投影；Artifact 保留策略与知识失效分离。

### 5.6 Graph Wiki Compiler

Graph Wiki 不复制完整图，而是维护图的“语义目录和来源层”：

```text
Graph Schema Concept
  ├─ 节点类型、关系方向、敏感属性说明
  └─ Schema version / resource

Graph Space Concept
  ├─ graphId + schemaId
  ├─ 业务用途与可信访问范围
  ├─ 主要实体类型和关系
  └─ 推荐入口与 registered queryIds

Entity Reference（有选择地创建）
  ├─ 人可读实体说明
  ├─ supporting Source Revisions
  └─ graph node bindings
```

创建和更新触发：

- Schema 注册、替换或删除后更新对应 Graph Schema Concept。
- Graph Space 首次出现、描述变化或删除后更新 Graph Space Concept。
- 已确认 Graph Mutation 提交成功后，根据受影响类型和来源修订相关目录页。
- 普通节点变更默认只写 Binding/Mutation Log，不为每个节点调用 LLM 生成页面。
- 达到使用频率、人工标记或跨来源价值门槛的实体才晋升为 Entity Reference。

### 5.7 OKF Bundle 中的文档与图谱

OKF 导出映射：

- Source Document：`type: Reference` 或 `type: Source Document`，`resource` 指向 Artifact URI。
- Topic Synthesis：`type: Topic Synthesis`，正文 Claim 使用稳定 Source ID 脚注。
- API Reference：`type: API Reference`，可通过 `resource` 指向 OpenAPI URI。
- Graph Schema：`type: Graph Schema`，`resource` 指向 Schema 文档。
- Graph Space：`type: Graph Space`，使用 `x-cyrene-graph-id/schema-id/query-ids` 扩展。
- Entity Reference：使用 `x-cyrene-graph-bindings` 表达图目标，实际权限仍由服务端校验。

Wiki 正文中面向人的来源列表可以显示为：

```markdown
- [产品说明书 v2](cyrene://knowledge/{conceptId}/revisions/{revisionId})
- [售后政策 v4](cyrene://knowledge/{conceptId}/revisions/{revisionId})
- [客户会议记录 2026-08-20](cyrene://knowledge/{conceptId}/revisions/{revisionId})
```

链接文字只负责可读展示；每项必须携带可解析的 `conceptId/revisionId`，并可经 Source 表继续解析到 `artifactId`。不得只保存“产品说明书 v2”这种文件名字符串后在召回时重新猜测来源。

Bundle 可以帮助其他 Agent 理解 Cyrene 的知识目录，但不能凭 Bundle 中的 `graphId/nodeId` 绕过 Graph Space Access Service。

---

## 6. 增量提取与消息竞态

### 6.1 调度时机

```properties
HARNESS_MEMORY_EXTRACTION_ENABLED=true
HARNESS_MEMORY_EXTRACTION_DAILY_TIME=02:00
HARNESS_MEMORY_EXTRACTION_TIMEZONE=Asia/Shanghai
HARNESS_MEMORY_EXTRACTION_IDLE_MINUTES=60
HARNESS_MEMORY_EXTRACTION_MIN_TURNS=3
HARNESS_MEMORY_EXTRACTION_BATCH_SIZE=100
HARNESS_MEMORY_EXTRACTION_CONCURRENCY=2
HARNESS_MEMORY_EXTRACTION_STUCK_MINUTES=30
HARNESS_MEMORY_EXTRACTION_MAX_ATTEMPTS=5
HARNESS_MEMORY_EXTRACTION_CONTEXT_OVERLAP_MESSAGES=4
```

使用按时区计算下一次运行时间的单次调度，不使用“启动后每 24 小时”。全部配置进入 `EnvKey` 并在启动时校验。

### 6.2 资格规则

```text
conversationTurns >= 3
lastActive <= now - 1 hour
latestCompleteTurnMessageId > watermarkMessageId
当前没有同 Session 的 in_progress Batch
```

3 轮定义为按消息顺序闭合的 3 个完整 `user + assistant final` 对；Tool、Summary 和单边未完成消息不计入。不能继续复用当前仅取 `min(userCount, assistantCount)` 的粗略统计，必须新增按稳定消息顺序计算完整轮次及最后完整轮边界的查询。

不再使用字符数、问号、平均回复长度、最少五条消息和请求到来时被动提交。

### 6.3 创建批次

在一个事务中：

1. 稳定游标扫描候选 Session，使用 `limit + 1`。
2. `SELECT ... FOR UPDATE SKIP LOCKED` 锁定 Session。
3. 读取 `fromMessageId = COALESCE(watermark, 0)`。
4. 冻结 `cutoffMessageId = latestCompleteTurnMessageId`，即最后一个完整 `user + assistant final` 轮次的结束消息 ID。
5. 若 `cutoff <= from` 则跳过。
6. 插入确定性 Batch。
7. 提交后交给有界 Worker。

Batch ID：

```text
SHA-256(extractorVersion + sessionId + fromMessageId + cutoffMessageId)
```

### 6.4 读取范围与上下文重叠

可写来源范围：

```text
(fromMessageId, cutoffMessageId]
```

为了理解跨边界语义，可以读取 `fromMessageId` 之前最多配置数量的消息作为只读上下文，但任何新 Revision 至少必须引用一个位于本批可写范围内的 `sourceMessageId`。

这样可以理解：

```text
旧消息：以后代码命名都用……
新消息：驼峰。
```

同时避免只因重复读取旧上下文而重新生成旧知识。

消息和 Trace 均使用稳定游标分页加载，不能无上限加载增长 Session。

本批可评分 root Trace 必须至少绑定一个 `(fromMessageId, cutoffMessageId]` 内的非 Summary user Message；子 Agent Trace 只能通过该 root Trace 的显式父子关系进入任务树。旧 `trace_id=NULL` 数据仅能进入离线兼容评估，不自动沉淀 Stable Playbook。

### 6.5 提取期间的新消息

```text
开始：watermark=20, cutoff=30
当前批处理：(20, 30]
期间新增：31..35
当前批不取消、不重启、不扩展 cutoff
成功提交后：watermark=30
再次空窗1小时后：创建 (30, 35]
```

新消息不会被永久忽略，也不会污染当前冻结快照。若空窗时最后一条仍是未完成用户消息，则 cutoff 停在上一完整轮；该尾部消息及后续完成回复一起留给下一批，水位不得越过它。

### 6.6 完成、失败与恢复

- 只有知识事务完整提交后，Session 水位才更新为 `cutoffMessageId`。
- 提取、Schema、来源或 MySQL 失败时，在最大尝试次数内按退避重新进入 `pending`；达到上限后 Batch 标记 `failed`，水位不变。
- `in_progress` 超时可重新置为 `pending`，重试同一个 Batch ID 和边界。
- 重试使用确定性 Concept/Revision/Outbox ID，不能生成重复版本。
- 同一水位存在终态 `failed` Batch 时，不得越过失败范围创建更大的后续 Batch；管理接口修复并显式重放后才能继续，避免静默丢失消息。
- 调度线程只发现和领取任务，不执行模型调用。

---

## 7. 提取前规则筛选

### 7.1 用户情景知识

允许：

- 已发生的重要任务和结果。
- 用户做出的决定、修正和承诺。
- 未完成事项和跨会话继续工作需要的关键上下文。
- 已由业务结果证明的重要状态变化。

禁止：

- 普通寒暄和逐句复述。
- 可从业务系统实时查询的瞬时状态。
- 凭证和敏感认证信息。
- 模型自行推断、来源未表达或 Tool 未证明的事实。

### 7.2 用户偏好知识

允许：

- 用户明确表达的格式、沟通、工具使用和工作方式偏好。
- 同一 Session 内重复出现且证据明确的稳定习惯。
- 用户对既有偏好的明确修改、例外和撤销。

禁止：

- 一次偶然行为。
- Agent 自己建议的偏好。
- 与用户本人无关的业务数据。
- 未经用户表达的敏感身份推断。

`preferenceKey` 是冲突域，不是宽泛分类。例如：

```text
response.verbosity
response.language
code.namingStyle
image.visualStyle
```

增加可注入的 `PreferenceKeyRegistry`。结构化提取只允许从 Registry 中选择 Key；框架提供通用 Key，接入系统可以注册领域 Key。模型不能生成 `response.detailLevel` 等临时 Key。Registry 无法映射的明确偏好统一使用保留 Key：

```text
other
```

`preferenceKey` 只负责定位同一偏好的 Concept Head，不直接等于注入条件。每个 Registry 定义同时声明受控的默认激活标签：

```text
response.verbosity  → GENERAL_RESPONSE
response.language   → GENERAL_RESPONSE
code.namingStyle    → CODE_TASK
image.visualStyle   → IMAGE_TASK
```

`PreferenceActivationTagRegistry` 提供框架和接入系统可注册的有限标签集合；模型不能生成任意标签。请求准备阶段根据 GapAnalysis 的意图、预期产物类型与 `RunToolCatalog` 能力构造 `PreferenceActivationContext`，仅选择 `GENERAL_RESPONSE` 或与本轮上下文匹配的偏好。Key 负责“修改哪一条”，Activation Tag 负责“什么时候使用这条”。

`other` 不是一个会被下一条未知偏好整体覆盖的字符串，而是该用户唯一兜底 Concept 的结构化条目集合。提取输入包含当前条目：

```json
{
  "preferenceKey": "other",
  "currentItems": [
    {
      "itemId": "other-item-1",
      "statement": "生成周报时先列风险，再列进展",
      "activationTags": ["REPORT_TASK"]
    }
  ]
}
```

模型只能输出 `APPEND/REVISE/REMOVE`、Registry 允许的 `activationTags` 和已有的 `targetItemId`；新增条目的 `itemId` 由后端生成，已有条目 ID 必须属于当前 Revision。后端按操作合并条目后创建完整的新 Revision，从而保证新增另一个未知偏好不会覆盖旧条目。能够映射到注册 Key 的内容禁止落入 `other`。无法可靠判断适用场景的未知偏好保持 `draft`，不能默认标记为全局适用。

### 7.3 系统操作经验的任务树

评分单位是一棵 Agent 任务树：

```text
root main Agent Run
  + direct child Agent Runs
  + nested descendant Agent Runs
```

根据 `parentTraceId/parentRunId` 关联。每个实际 Run 只出现一次；不得把 `spawn_subagent` 或 `await_subagents` 的 Tool 成功当作子 Agent 业务成功。

#### 7.3.1 任务级硬筛选

命中任意一项，整棵任务树跳过：

- 没有 Tool 调用。
- 主 Agent `finalOutput` 为空。
- 主任务契约明确失败、取消或整体未完成。
- 结束于确认拒绝、确认过期、`max_iterations`、`tool_failure_limit` 或 `LOOP_DETECTED`，且没有形成成功主结果。
- 结构化输出 Schema 失败。
- 必需产物缺失或类型不匹配。
- 已有能绑定当前 Trace 的明确用户负反馈。
- 只有最终文本声称成功，没有 Tool、业务状态、完成契约或产物证据。

最后状态取最后一个包含 Tool 调用的 Step；末尾无 Tool 的最终文本 Step 不能遮蔽未解决失败。

失败子 Agent 不直接否决任务树。主 Agent 成功回退时，失败子 Run 计 0 并进入平均；只有主任务因此失败时才整树跳过。

用户反馈只接受显式绑定证据：可信调用方使用 Agent 响应返回的 `traceId` 写入 Trace metadata 的 `user_feedback=positive|negative`，并通过 Session Owner/可空租户校验。普通后续用户消息即使出现“风格不对”等文本，也不能仅凭时间相邻自动判定为上一条 Trace 的负反馈；没有稳定绑定时不触发硬筛选，也不获得正反馈加分。现有 `TraceCollector.updateFeedback`/`TraceStore.updateMetadata` 可作为存储基础，但必须增加认证边界和错误显式返回，不能继续只记录日志后假装写入成功。若未来希望按响应消息绑定，必须先新增持久 `responseMessageId → traceId` 映射，本期不做隐式推断。

#### 7.3.2 学习信号门槛

整棵任务树至少出现一个信号：

- `TOOL_ERROR` 或选错 Tool 后改变方案并成功。
- `EMPTY/LOW_RELEVANCE/INSUFFICIENT` 后改变检索或工具策略并成功。
- 同一 Tool 修改关键参数后成功。
- 后续 Tool/子 Agent 明确消费前序结果，形成依赖链。

单 Tool 直接成功、普通聊天、多个互不相关 Tool 和单纯检索不构成学习信号。

`TraceDependencyFeatureBuilder` 生成候选依赖边，不能仅凭调用先后推断原因。能力来自 `ToolSpec.tags`：

```text
capability:retrieval
capability:read
capability:generation
capability:mutation
capability:orchestration
capability:unknown
```

依赖类型：

- `DIRECT_REFERENCE`：后序参数引用前序 Artifact ID、业务 ID、游标等稳定值。
- `VALUE_REUSE`：前序有界非敏感关键值出现在后序参数。
- `RESEARCH_BEFORE_GENERATION`：检索/读取先于生成，且目标、查询和生成参数存在同一规范化任务实体或主题。
- `SEQUENTIAL_ONLY`：只有时间先后，仅诊断，不作为学习信号或加分。

`RESEARCH_BEFORE_GENERATION` 只表示值得交给模型判断是否存在“先研究再生成”策略，不证明本次检索内容正确。

#### 7.3.3 单 Run 评分

Run 自身取消、循环、确认失败、没有有效结果或 `completionValidated=false` 时直接计 0。

结果可信度，最高 40：

| 项目 | 分值 | 判定 |
|---|---:|---|
| Run 正常完成 | +15 | `reactOutcome=completed` 且最终输出非空 |
| 最终业务结果验证成功 | +15 | `VERIFIED` Tool、业务/API、产物、输出或子 Agent 契约证据 |
| 最后有效 Tool Step 已验证 | +10 | 已验证且没有未解决失败；`AVAILABLE` 不得分 |

学习价值，最高 40：

| 项目 | 分值 |
|---|---:|
| Tool 错误或选错 Tool 后恢复 | +20 |
| 空结果、低相关或信息不足后恢复 | +15 |
| 修改关键参数后成功 | +10 |
| 存在非 `SEQUENTIAL_ONLY` 候选依赖链 | +10 |

执行质量，最高 20：

| 项目 | 分值 |
|---|---:|
| 无无意义相同调用 | +8 |
| 失败后重试不超过 2 次 | +5 |
| `总轮次 <= Tool 调用数 + 2` | +4 |
| 有可绑定当前 Trace 的明确用户正反馈 | +3 |

扣分：

| 项目 | 扣分 |
|---|---:|
| 每个最终未利用的 `EMPTY/LOW_RELEVANCE` | -5，最多 -10 |
| 超过 2 次后每次额外重试 | -5，最多 -15 |

```text
runScore = clamp(
    resultCredibility
  + min(learningValue, 40)
  + executionQuality
  - penalties,
  0,
  100
)
```

旧 Trace 缺少 `reactOutcome` 时，满足明确兼容证据只能给正常完成 8 分，Run 最高 79。无法验证子 Agent 结果的旧 Run 标记 `LEGACY_UNVERIFIED` 并计 0。

#### 7.3.4 主子 Agent 平均

```text
taskScore = roundHalfUp(
    (mainRunScore + sum(descendantRunScores))
    / (1 + descendantRunCount),
    2
)
```

- 不按主/子、Token、耗时、层级加权。
- 已进入执行生命周期的失败/取消子 Run 计 0。
- 从未创建 Run 的计划项不进入分母。
- 嵌套子 Run 展平，各计一次。

| `taskScore` | 处理 |
|---|---|
| `< 60` | 不提取 Playbook |
| `60 <= score < 80` | 普通 Playbook 候选 |
| `80 <= score <= 100` | 高置信 Playbook 候选 |

只要包含 `LEGACY_UNVERIFIED` Run，整棵任务树最高只能生成普通候选。

#### 7.3.5 Tool 执行与结果状态

```text
ExecutionStatus
  SUCCEEDED / FAILED / CONFIRMATION_REQUIRED / REJECTED / EXPIRED / CANCELLED

ResultStatus
  AVAILABLE / VERIFIED / EMPTY / PARTIAL / PENDING /
  LOW_RELEVANCE / ESCALATING / CONTRACT_FAILED
```

- `SUCCEEDED` 只表示没有执行异常。
- `AVAILABLE` 表示有输出但未验证，不获得结果可信度分。
- `VERIFIED` 必须携带有界 `validationSource/validationReason`。
- HTTP 2xx、Web Search 非空、产生 Artifact、提交视频任务、`await_subagents` 成功都不能单独证明语义正确。
- Inspector 聚合本轮全部 ToolResult，不能遇到第一条成功就提前返回。
- 外部兼容字段 `success` 只能由 `ExecutionStatus` 派生。
- `ThreadLocal<ResultStatus>` 替换为显式 `ToolExecutionOutcome`。

旧 `ResultStatus.SUCCESS` 或 `success=true + 非空结果` 只能映射为 `AVAILABLE`；只有旧 Trace 同时存在可复核验证证据时才映射为 `VERIFIED`。

#### 7.3.6 实现要求

- 权重定义为命名常量并由测试固定，不拆成任意组合环境变量。
- 输入是裁剪后的结构化特征，不用 LLM 打分。
- 输出包含 `eligible/skippedReason/taskScore/runScores/learningSignals`。
- 不在指标标签记录用户正文、Tool 原始输出或凭证。
- 评分只发生在结构化提取前；提取后只校验来源和合法性。

---

## 8. Knowledge Compiler

### 8.1 单次结构化提取

每个 Batch 只调用一次结构化提取模型。输入：

```text
只读重叠上下文
+ 本批 user/assistant 消息
+ 通过规则筛选的有序 Trace 策略特征
+ 当前可用 Tool 名称、能力标签与有界 Schema 摘要
+ 已相关召回的当前 Concept 摘要
```

已有 Concept 摘要用于判断“新建还是修订”，但模型不得自行指定数据库 ID、版本号、状态、质量分和验证层级。

每次 Tool 调用至少提供：

```json
{
  "stepRef": "root:step-7:call-image-1",
  "order": 7,
  "toolName": "image_generation",
  "toolTags": ["capability:generation", "image"],
  "argumentsSummary": {"prompt": "裁剪脱敏后的提示词"},
  "executionStatus": "SUCCEEDED",
  "resultStatus": "AVAILABLE",
  "resultSummary": "已生成图片 Artifact",
  "artifactTypes": ["IMAGE"],
  "candidateDependencies": [
    {
      "fromStepRefs": ["root:step-1:call-search-1"],
      "relation": "RESEARCH_BEFORE_GENERATION"
    }
  ]
}
```

模型输出 Proposal：

```json
{
  "conceptProposals": [
    {
      "conceptType": "User Preference",
      "logicalKey": "response.verbosity",
      "changeIntent": "CREATE_OR_REVISE",
      "activationTags": ["GENERAL_RESPONSE"],
      "title": "Response verbosity",
      "description": "用户希望普通回答简洁，技术设计可以展开",
      "body": "# Current preference\n\n普通问答保持简洁；架构和技术设计允许展开。",
      "sourceMessageIds": [101, 105],
      "relatedConceptIds": []
    },
    {
      "conceptType": "User Episode",
      "logicalKey": null,
      "changeIntent": "CREATE",
      "title": "合同审批后续计划",
      "description": "用户决定下次继续处理合同审批",
      "body": "# Event\n\n用户决定下次继续处理合同审批。",
      "eventTime": "2026-08-31T10:00:00Z",
      "sourceMessageIds": [110],
      "relatedConceptIds": []
    },
    {
      "conceptType": "Operation Playbook",
      "logicalKey": "generation.research-before-known-subject",
      "changeIntent": "CREATE_OR_REVISE",
      "title": "生成既定对象前先研究关键特征",
      "description": "上下文不足时先检索并验证对象特征，再生成",
      "body": "# Trigger\n\n...\n\n# Strategy\n\n...",
      "requiredTools": ["web_search", "image_generation"],
      "evidenceFlow": [
        {
          "fromStepRefs": ["root:step-1:call-search-1"],
          "toStepRef": "root:step-7:call-image-1",
          "relation": "RESEARCH_BEFORE_GENERATION"
        }
      ],
      "sourceTraceId": "trace-root-1"
    }
  ]
}
```

数组允许为空。不得为了填充三类知识而编造内容。

注册 `preferenceKey` 的激活标签由后端 Registry 归一化，模型输出只能是其允许子集；`other` 条目只能从 `PreferenceActivationTagRegistry` 选择标签。Proposal 不能通过声明任意 `GENERAL_RESPONSE` 把场景偏好提升成全局偏好。

“先研究再生成”只能抽象为流程和验证要求，不能把本次搜索得到的具体人物外观、产品属性、URL 或用户实例值沉淀成通用 Playbook。搜索结果未验证时，只能说“应检索并验证”，不能声称本次已经充分了解。

### 8.2 提取后合法性校验

- JSON Schema 合法。
- 每个 Proposal 至少引用一个本批 `(from, cutoff]` 内来源。
- 所有 `sourceMessageIds <= cutoffMessageId` 且属于当前 Session/Owner。
- `sourceTraceId` 必须是由本批可写 user Message 的 `trace_id` 精确绑定的 root Trace，或该 root 的显式 descendant，且任务树 `taskScore >= 60`；不按文本或时间近邻匹配。
- `requiredTools` 和策略 Tool 均真实出现在对应任务树。
- `evidenceFlow` 引用存在、顺序正确，并命中代码生成的非 `SEQUENTIAL_ONLY` 依赖边。
- 未验证搜索不得被描述为已确认事实。
- 内容不含凭证、用户实例值和超限 Tool 输出。
- 单条和总输出均在配置上限内。

任一 Proposal 非法时整批失败并重试，不提交半份知识。

状态由 Compiler 的确定性规则决定，Proposal Schema 不允许输出 `status`：

- `User Preference`：用户明确指令、明确纠正或撤销可以直接成为 `stable`；仅从重复行为归纳的习惯先为 `draft`，在用户确认或至少两个独立 Session 出现一致证据后晋升。来源互相冲突时保持 `draft`，不按最新时间自动覆盖语义冲突。
- `User Episode`：用户明确陈述的决定/承诺/已发生事件，或 `BUSINESS_CONFIRMED` 结果可以成为 `stable`；未完成推断、时间不确定或来源冲突保持 `draft` 或不写入。
- `Operation Playbook`：`taskScore >= 60`、所有硬筛选与合法性校验通过且不含 `LEGACY_UNVERIFIED` Run 时成为 `stable`；`80+` 只表示更高质量层级，不是另一种生命周期。含旧版不可验证 Run 的普通候选保持 `draft`，不得自动召回。
- `Source Document/Graph Schema/Graph Space`：对应 Artifact、Registry 或 Neo4j 状态成功提交且来源可解析后成为 `stable`。
- `Topic Synthesis/Entity/API Reference`：所有关键 Claim 均有可读 Source Anchor、没有未解决冲突时成为 `stable`；否则保持 `draft`。
- `deprecated` 只能由明确撤销、来源删除、上游对象失效或受控管理操作产生。时间衰减只影响 `staleAfter`，不能自动等同于删除。

`stable` 表示允许进入当前召回，不等于人工验证；`Verification` 仍独立记录 `TRACE_VALIDATED/USER_CONFIRMED/BUSINESS_CONFIRMED/HUMAN_REVIEWED`。

### 8.3 Concept 匹配与修订规则

#### User Preference

- 使用确定性 `preferenceKey` 定位 Concept。
- 注册 Key 由 `PreferenceKeyRegistry` 解析；无法归类的明确偏好固定进入 `other` Concept，不创建任意新 Key。
- `other` 的 `APPEND/REVISE/REMOVE` 由后端根据 `itemId` 合并；模型不能重写未涉及条目，也不能伪造已有 `itemId`。
- 注册 Key 的 `activationTags` 由 `PreferenceKeyRegistry` 校验；`other` 条目的标签必须来自 `PreferenceActivationTagRegistry`，并随完整 Revision 保存。
- 相同语义且没有新信息：只追加可验证来源或 Verification；不创建空洞 Revision。
- 相同 key、内容变化：创建新 Revision，并原子切换 `currentRevisionId`。
- 用户明确撤销：创建表达撤销后的 Revision；若该偏好不再适用，可把 Concept 标记 `deprecated`。
- 历史 Revision 不参与正常注入。

#### User Episode

- 独立事件通常创建新 Concept。
- 明确修正同一事件时，创建新 Revision 或新 Concept + `SUPERSEDES`，由事件身份是否稳定决定。
- 业务实时状态只保留必要历史决定，不复制可随时查询的完整业务对象。

#### Operation Playbook

- 先按作用域、`logicalKey/taskType` 和当前 Revision 摘要寻找候选，再使用高阈值语义匹配判断修订对象。
- 没有合适候选则创建新 Playbook。
- 新策略强化、纠正或替代已有策略时创建新 Revision。
- 不能确定是否相同时宁可创建独立 Playbook，并建立 `RELATED_TO`，不让模型覆盖不确定知识。
- `qualityScore` 保存在 Revision metadata，模型不能生成或修改。
- 新建或策略发生变化而创建新 Revision 时，`qualityScore` 由对应来源任务树的确定性 `taskScore` 直接写入；相同策略仅追加来源或 Verification 时不修改既有 Revision metadata，也不重新评分或对多条 Trace 求平均。

### 8.4 原子提交

在一个 MySQL 事务中：

1. 锁定 Batch 和待修订 Concept。
2. 校验 Batch 边界与 Session Owner。
3. 插入新 Concept/Revision。
4. 插入 Source、Verification 和 Link。
5. 乐观锁更新 `currentRevisionId/version/status/staleAfter`。
6. 插入对应 Outbox。
7. 更新 Session `watermarkMessageId=cutoffMessageId`。
8. Batch 标记 `succeeded`。
9. 提交。

事务失败全部回滚，不需要跨 MySQL/Milvus 补偿删除。

---

## 9. 向量检索投影

### 9.1 Provider 和 Collection

复用：

```text
HARNESS_RAG_PROVIDER
HARNESS_RAG_URL
HARNESS_RAG_DATABASE
Embedding Provider / Model / Dimension
```

新增：

```properties
HARNESS_KNOWLEDGE_CATALOG_COLLECTION=cyrene_knowledge_catalog
HARNESS_MEMORY_USER_KNOWLEDGE_COLLECTION=cyrene_user_knowledge
HARNESS_MEMORY_OPERATION_KNOWLEDGE_COLLECTION=cyrene_operation_knowledge
```

当前环境选择 Milvus `cyrene_test` 时：

```text
Milvus Database: cyrene_test
  ├─ cyrene_test                  现有且唯一的文档知识库
  ├─ cyrene_knowledge_catalog     Wiki Concept 当前 Revision
  ├─ cyrene_user_knowledge        User Episode 当前 Revision
  └─ cyrene_operation_knowledge   Operation Playbook 当前 Revision
```

`cyrene_knowledge_catalog` 只保存非记忆型 Wiki Concept：Source Document、Topic Synthesis、Entity/API Reference、Graph Schema 和 Graph Space。它不复制 User Preference、User Episode 或 Operation Playbook；统一路由是在应用层查询专用索引，不是把三类数据再复制到 Catalog。

偏好当前视图首期走 MySQL，不生成 Embedding。若后续偏好数量真实增长到无法有界读取，再单独评估索引，不提前混入用户情景 Collection。文档 Chunk 只保留 `HARNESS_RAG_COLLECTION` 指向的现有物理 Collection，不创建 v2、临时 Collection、别名或双写链路；新增来源字段写入现有 `metadata`，需要补的索引直接建立在现有字段上。实施时允许在明确维护窗口内对这一套 Collection 做一次全量重建，但运行时始终只有一套文档索引。

### 9.2 投影 Schema

Wiki Catalog：

```text
id                  VarChar(64) primary key = revisionId
concept_id          VarChar(64)
revision_id         VarChar(64)
tenant_id           VarChar(128) nullable
user_id             VarChar(128) nullable
namespace_type      VarChar(32)
namespace_key       VarChar(256)
concept_type        VarChar(64)
route_target        VarChar(32)
resource_uri        VarChar
title               VarChar
description         VarChar
content             VarChar analyzer enabled
generated_at        Int64
embedding           FloatVector
sparse_content      SparseFloatVector
```

Catalog 的 `route_target` 只允许 `CONCEPT/DOCUMENT/GRAPH`。User Episode 与 Operation Playbook 分别由专用索引返回 `USER_MEMORY/OPERATION_MEMORY` 路由结果，不进入 Catalog。Graph Space Concept 只索引人可读目录信息，不索引完整节点和关系。

文档 Chunk 继续复用当前 `VectorStore.Document`/Collection Schema：

```text
id             = chunkId
content        = 有界来源头 + Chunk 正文（现有 BM25 输入）
source         = 原始 fileName
collection     = 现有逻辑 collectionKey
embedding      = Dense Vector
chunk_index    = 原有分块序号
sparse_content = 由 content 生成的现有 BM25 Vector
metadata       = {
  document_id,
  concept_id,
  revision_id,
  artifact_id,
  source_file_artifact_id,
  tenant_id?,
  source_title,
  source_version?,
  effective_at?,
  heading_path,
  file_name_normalized
}
```

字段和索引规则：

- `document_id/concept_id` 是同一稳定逻辑文档身份，`revision_id` 锁定具体文档版本，`artifact_id` 定位用于切块的不可变 Markdown，`id/chunkId` 定位具体证据块。
- 现有 `source` 保存原始文件名，允许重名和版本间变化；为 `source`、`collection` 和 `chunk_index` 补现有 Collection 上的 Scalar Index，分别支持文件名过滤、Collection 过滤和文档窗口读取。文件名不能作为主键、去重键或版本键。
- `file_name_normalized` 去掉路径、扩展名和纯技术分隔符后只在 `content` 的来源头中出现一次，使现有 BM25 可以发现文件名；不新增第二个 `search_text` 字段或第二套稀疏索引。
- `source_title/source_version/effective_at/heading_path` 放在 metadata；其中规范标题、明确版本和章节同时进入有界来源头。`source_version/effective_at` 只有来源明确提供时才写入。
- Dense 文本使用“规范来源标题 + 明确版本 + 章节路径 + Chunk 正文”；不反复嵌入扩展名、路径和纯技术分隔符等物理文件名噪声。日期或“最终版”只有被判断为业务语义时才通过规范标题/明确版本进入 Dense 文本。
- `document_id/revision_id/artifact_id/tenant_id` 延续现有 metadata 过滤和 MySQL 权威校验，不为了它们再创建一个文档 Collection。若后续真实数据证明 JSON 过滤成为瓶颈，再单独给现有 Collection 增加受支持的 JSON Path Index，而不是复制数据。
- Wiki Source 已包含 `conceptId/revisionId` 时，直接按稳定 ID 读取该 Revision 的 Chunk，不再依赖相似文件名执行全库向量猜测。

用户知识：

```text
id                  VarChar(64) primary key = revisionId
concept_id          VarChar(64)
revision_id         VarChar(64)
tenant_id           VarChar(128) nullable
user_id             VarChar(128)
concept_type        VarChar(64)
title               VarChar
description         VarChar
content             VarChar analyzer enabled
event_time          Int64
generated_at        Int64
embedding           FloatVector
sparse_content      SparseFloatVector
```

操作知识：

```text
id                  VarChar(64) primary key = revisionId
concept_id          VarChar(64)
revision_id         VarChar(64)
tenant_id           VarChar(128) nullable
concept_type        VarChar(64)
logical_key         VarChar(256)
title               VarChar
description         VarChar
content             VarChar analyzer enabled
quality_score       Double
generated_at        Int64
required_tools      JSON
embedding           FloatVector
sparse_content      SparseFloatVector
```

Dense 使用 HNSW + COSINE，Sparse 使用 BM25。对参与过滤和排序的作用域、Concept、Revision、类型、时间和质量字段建立 Scalar Index；不为未下推的 JSON 逻辑创建无效索引。

pgvector 为 Catalog、文档 Chunk、User Episode 和 Operation Playbook 分别建立与 Milvus 语义等价的投影表，使用 HNSW、`TSVECTOR + GIN` 和相同作用域索引；Preference 不建立向量表。只初始化当前选择的 Provider。

### 9.3 Outbox Projector

```text
pending Outbox
  → 事务领取
  → 读取 MySQL 当前 Concept/Revision
  → 按 targetIndex 生成 Catalog / Chunk / Memory 投影
  → Upsert 当前 Revision 或完整文档 Chunk 集
  → 删除该 Concept 的旧 Revision 投影
  → Outbox completed
```

- 幂等键为 `revisionId + targetIndex + operation`。
- `CATALOG` 只写入非记忆型 Wiki Concept 摘要；`DOCUMENT` 读取 canonical Markdown 后整篇重新切块；`USER_MEMORY/OPERATION_MEMORY` 写入对应专用索引。任何 User Preference、User Episode 或 Operation Playbook 都不得重复投影到 Catalog。
- 重试前重新读取权威状态；若 Concept 已 deprecated，执行删除而不是旧 Upsert。
- Worker 使用有界线程池、指数退避和最大失败观测，但不得把失败伪装成无记忆。
- Outbox 达到 `HARNESS_MEMORY_INDEX_OUTBOX_MAX_ATTEMPTS` 后进入终态 `failed` 并告警；权威 Revision 保持有效，但对应索引状态必须显式显示为未同步，可通过管理操作按原幂等键重放。
- 可以存在短暂旧向量，但召回阶段必须通过权威 Revision 校验，因此旧版本不会进入 Prompt。

### 9.4 重建

增加按稳定游标分页的 Reindex 能力：

```text
scan stable current revisions
  → upsert target index
  → compare projection revision ids
  → delete orphan/stale vectors
```

重建必须有明确作用域、分页、进度和失败状态；不得启动时无界全量扫描。

---

## 10. 统一知识发现与召回

### 10.1 请求准备顺序

```text
请求准备阶段（自动长期记忆）：
认证与输入解析
  → Session / 短期历史
  → GapAnalysis / unavailableTools
  → immutable RunToolCatalog
  → PreferenceActivationContext
  → MemoryRetrievalQuery
  → 偏好 Wiki View + User Episode + Operation Playbook
  → MySQL 当前 Revision 批量校验
  → 3% Token 打包
  → ReActRequest

ReAct 按需知识阶段：
knowledge_search
  → KnowledgeDiscoveryRouter
  → 返回 Concept / Document / Graph 等类型化 Handle

knowledge_read
  → 按 Handle 有界读取当前 Revision、Source Chunk 或 Graph Result
```

`MemoryRetrievalQuery` 使用：

```text
当前用户消息
+ 当前 Session 有界压缩摘要/最近任务目标
+ GapAnalysis 已识别的意图、错误或缺失参数
```

最多 512 tokens，按完整字段舍弃，不硬截断。`userId/tenantId`、时间戳、评分、ID 和凭证不进入检索正文，只作为可信结构化过滤。

3% 只约束自动注入的长期记忆。文档知识库和图谱仍由 Tool 按需调用，使用各自 Tool Result/上下文预算，不抢占长期记忆配额，也不在请求准备阶段自动塞入 System Prompt。

### 10.2 渐进式 Wiki 召回

```text
当前任务
  → Wiki Catalog Dense + BM25 有界召回
  → 选择相关 Concept
  → 读取当前 Revision
  → 必要时读取 links / sources 摘要
  → 注入完整 Knowledge Block
```

正常回答不展开全部历史 Revision 和完整来源正文。只有需要解释、冲突核验或用户询问“为什么记得”时，才按权限读取来源。

### 10.3 统一发现路由

增加 `KnowledgeDiscoveryRouter`，统一的是查询编排和路由结果，不是把所有知识复制进同一目录，也不是统一底层分数：

```text
query
  → KnowledgeDiscoveryRouter
      ├─ Knowledge Catalog Hybrid
      │   ├─ Topic/API/Entity Concept：直接读取当前 Revision
      │   ├─ Source Document：进入文档 Chunk Hybrid + Rerank
      │   └─ Graph Space / Entity Reference：交给 GraphKnowledgeExecutor
      ├─ User Knowledge Hybrid：只返回 User Episode
      └─ Operation Knowledge Hybrid：只返回 Operation Playbook
```

自动长期记忆准备阶段还会按认证作用域从 MySQL 读取当前 Preference Head；Preference 不通过 Catalog 或向量路由发现。Router 可以按请求类型只执行需要的分支，不能为了形式统一每次无条件查询全部索引。

路由输出必须包含：

```text
knowledgeKind
conceptId
currentRevisionId
routeTarget
sourceAnchors（可选）
graphRouteHint（可选，已经权限裁剪）
```

模型可见工具固定为：

- `knowledge_search`：接收有界查询和可选知识类型，调用 Router 执行必要分支，返回 `knowledgeKind/conceptId/currentRevisionId/routeTarget/handle` 及简短摘要；不返回全部原文，也不把异构分数相加。
- `knowledge_read`：接收类型化 Handle，但始终把 Handle 当作不可信定位信息重新校验当前 Revision、Owner、租户、Collection、Graph Scope 和 Tool 权限；按类型读取当前 Concept Revision、同一 Source Document Revision 的有界 Chunk 窗口，或执行受限的 Graph 节点/邻域读取。禁止自由 URI、自由 Cypher 和跨作用域扩展，不能因为 ID 猜中就获得读取权限。

内部执行器继续分离：

- 现有 `KnowledgeBaseTool`/`KnowledgeContextReadTool` 的检索与窗口读取能力下沉为 Document 执行器。
- 现有 `KnowledgeGraphTool` 的 `listGraphSpaces/findNodes/findNeighborhood` 能力下沉为 Graph 执行器。
- `KnowledgeDiscoveryRouter` 只编排这些执行器；Document 与 Graph 结果保持各自协议、阈值、权限和证据语义。
- 旧 `knowledge_base_search`、`knowledge_context_read`、`knowledge_graph_search` 不再注册到模型可见的 `RunToolCatalog`，也不保留模型侧隐式别名或双路径。
- 已有可信 Graph Space/Subject Scope 时，Graph 执行器跳过 Wiki 路由，继续使用服务端上下文的最短确定性路径。
- 在线搜索和读取不物化 OKF；只有管理端明确导出选定 Concept/Revision 时才调用 `OkfBundleExporter`。

禁止建立一个把 Concept RRF、Chunk Rerank Score、Graph Path 和 Memory Score 直接相加的“万能知识分”。Router 只能依据候选类型和可用性选择执行器，最终结果保留各自证据语义。

### 10.4 混合检索算法

用户情景和操作 Playbook 分别在自己的索引中：

1. Dense/Sparse 两路应用相同作用域硬过滤。
2. 每路 `laneTopK=20`。
3. Dense COSINE 默认 `>=0.70`。
4. Sparse BM25 默认 `>=0.10`。
5. 通过阈值后执行一基排名 RRF，`k=60`。
6. 融合保留 `fusedTopK=20`。

```text
rrfScore(revision) = sum(1 / (60 + rankInLane))
```

同一 Revision 两路命中只保留一条并累加贡献。RRF 只表达相关性，不能与 `qualityScore`、时间或知识库的 `0.7` 阈值相加比较。

Milvus 使用原生 Hybrid Search + `RRFRanker(60)`；pgvector 分路查询后在应用层按相同公式融合。

### 10.5 权威 Revision 校验

向量融合后，批量查询 MySQL：

```text
concept.status == stable
candidate.revisionId == concept.currentRevisionId
now < staleAfter，或明确允许以 stale 标记读取
scope 仍匹配当前认证上下文
```

不满足的候选立即删除。不得因为候选不足而恢复旧 Revision、扩大租户或绕过用户作用域。

### 10.6 用户偏好

从 MySQL 获取当前 `User Preference` Stable Revision：

```text
tenant/user exact scope
ORDER BY updatedAt DESC, id DESC
stable cursor + limit + 1
```

同一个 `preferenceKey` 只有一个 Concept Head，不注入历史冲突值。`other` Head 展开为多条独立偏好项，但物理上仍是一个 Concept；每项保留 `itemId` 供后续精确修订。Wiki View 可以把多个当前偏好编译成一个结构化块；预算不足时按任务相关性、明确用户确认和更新时间舍弃完整条目。

读取当前 Head 后必须再应用 `PreferenceActivationContext`：

1. `GENERAL_RESPONSE` 可参与所有需要最终回复的请求。
2. 其他标签必须匹配 GapAnalysis 意图、预期 Artifact 类型或当前 `RunToolCatalog` 能力。
3. `other` 按条目过滤，不因其中一项匹配而注入整个 `other` Head。
4. 同一条目多个标签默认使用 OR；若接入系统需要 AND 条件，应注册显式复合标签，不能让模型临时拼装表达式。
5. 不匹配的偏好不进入 3% 候选，也不写入 Prompt；存储时仍保留当前 Head。

### 10.7 用户情景

Dense/BM25 两路都严格过滤 `tenantId + userId`。权威校验后：

1. `bestScore` 为最高 RRF。
2. `score >= bestScore * 0.90` 进入近似相关区间。
3. 区间内按 `eventTime DESC, generatedAt DESC, revisionId DESC`。
4. 区间外继续按相关性排序。
5. 允许注入多条完整 Block。

时间不能让明显低相关的新事件越过高相关旧事件。

### 10.8 操作 Playbook

1. 两路过滤 `tenantId=current OR NULL`；无租户请求只允许 `NULL`。
2. `qualityScore >= 60`。
3. 权威 Revision 校验。
4. `requiredTools` 必须是当前 RunToolCatalog 子集。
5. 在剩余候选中计算最高相关区间。
6. 区间内优先 Verified、较高质量层级和较新 Revision；不把这些信号混入 RRF。
7. 最多注入一个 Playbook。

全部候选因 Tool 或权限失效时返回空，不降低门槛重新搜索。

### 10.9 文档与图谱召回

文档知识：

1. 若高相关 Stable Topic/API/Entity Concept 已直接回答问题，优先注入编译知识及 Source 摘要。
2. 需要原文定义、前后步骤、表格、代码或引用核验时，对绑定 Source Document 执行 Chunk Hybrid + Rerank。
3. `knowledge_read` 通过 Document Handle 读取同一 Revision 的有界相邻窗口，不跨文档自动扩展。
4. Topic Synthesis 的生成时间不能替代来源时效；任一关键 Source 已 stale/deprecated 时，综合页进入重新编译或带 stale 标记。
5. 用户明确提及“产品说明书”“售后政策”或实际文件名时，先对 Catalog 的规范标题、别名和 Chunk 投影的 `file_name` 做词法发现，得到 `documentId/revisionId` 后再在该版本内执行语义检索。
6. Wiki 已提供 Source Anchor 时直接按 `documentId/revisionId` 收窄检索；文件名得分只负责找到文档，不能覆盖权限、当前 Revision 或正文相关性。

图谱知识：

1. Wiki Catalog 只帮助选择可读 Graph Space、Schema 和入口实体域。
2. 具体实体匹配使用 `findNodes`，关系和路径使用 `findNeighborhood`。
3. Graph 结果不进入向量 Rerank，也不因 Wiki 相似度删除确定性路径结果。
4. 返回结果可以附带绑定 Concept/Source ID，支持来源解释，但正文仍由 Graph Result Formatter 按 Schema 脱敏。
5. Graph Space Concept 不存在或没有语义命中时，可以显式执行稳定分页 `listGraphSpaces`；Catalog 后端失败必须报错，不能伪装成“无匹配”后静默走另一条路径。

### 10.10 故障语义

- MySQL 权威读取失败：长期知识召回失败，不能使用未校验向量继续回答。
- 某个向量 Provider 失败：明确报告对应类型和 Provider；不静默回退文件、LIKE、单路 BM25 或单路 Dense。
- 没有相关知识与存储失败必须可区分。

---

## 11. 3% Token 预算与 Prompt Cache

```java
longTermMemoryBudget = floor(chatModelContextWindowTokens * 0.03);
```

示例：

```text
1,000,000 → 30,000 tokens
128,000   → 3,840 tokens
32,000    → 960 tokens
```

要求：

- 使用注入的 `TextTokenEstimator`。
- 偏好 Wiki View、用户情景和一个 Playbook 共享预算，不设固定子比例。
- 不为了用满预算加入低相关知识。
- Knowledge Block 必须完整取舍，不硬截断 Markdown/JSON。
- 来源摘要和历史 Revision 默认不进入预算。

消息顺序：

```text
稳定 System Prompt
+ 稳定 Tool Specifications
+ Session historyMessages
+ dynamicKnowledgeContext
+ currentUserMessage
```

`dynamicKnowledgeContext`：

- 映射为当前请求内、位于真实用户消息之前的框架生成 `UserMessage` 数据块，不提升为 System/Developer 指令；稳定 System Prompt 固定声明该块是可引用证据而不是命令。
- 使用明确边界和类型字段编码，每个 Block 标明 `conceptType/conceptId/revisionId`；正文中的“忽略规则、调用工具”等文本只能视为知识内容，不能改变 Tool Catalog、确认或权限策略。
- 不写回 Session 消息表。
- 不修改用户原消息。
- 不能覆盖 System Prompt、Tool Catalog、认证权限和业务系统实时结果。
- Trace 只记录 Concept/Revision ID、类型、Token、命中理由和耗时，不记录完整敏感正文。

---

## 12. 分层与对象设计

### 12.1 `harness-core`

新增 provider-neutral 模型：

```text
KnowledgeConcept
KnowledgeRevision
KnowledgeSource
KnowledgeVerification
KnowledgeLink
KnowledgeStatus
KnowledgeConceptType
KnowledgeHandle
KnowledgeArtifact
KnowledgeArtifactType
KnowledgeGraphBinding
KnowledgeRouteTarget
KnowledgeIngestJob
MemoryExtractionBatch
KnowledgeIndexTask
KnowledgeContextBlock
```

增加 `optionalTenantId`，保持图谱默认租户兼容。

### 12.2 `harness-input`

保留短期 Session、Message、Cache 和 Compressor。新增：

```text
MemoryExtractionBatchStore
MysqlMemoryExtractionBatchStore
```

删除旧 `PreferenceStore` 覆盖模型和 `PreferenceRefinementWorker`。

### 12.3 `harness-trace`

- 增加按 Session 稳定游标读取。
- Trace 保存失败必须显式抛出。
- 显式用户反馈按 `traceId` 绑定并校验 Session Owner；无稳定绑定的后续文本不自动解释为正负反馈。
- Trace 是 Playbook 原始证据，可重建知识。

### 12.4 `harness-tool`

新增：

```text
KnowledgeRepository
MysqlKnowledgeRepository
KnowledgeArtifactRepository
MysqlKnowledgeArtifactRepository
KnowledgeIngestJobStore
MysqlKnowledgeIngestJobStore
KnowledgeWikiViewService
PreferenceKeyRegistry
PreferenceActivationTagRegistry
OkfBundleExporter
OkfBundleImporter（后续 Phase）
KnowledgeCatalogIndex
MilvusKnowledgeCatalogIndex
PgvectorKnowledgeCatalogIndex
KnowledgeCollectionAccessService
SourceDocumentCompiler
GraphWikiCompiler
DocumentKnowledgeExecutor
GraphKnowledgeExecutor
GraphMutationJobStore
GraphMutationSagaWorker
UserKnowledgeIndex
OperationKnowledgeIndex
KnowledgeIndexProjector
MilvusUserKnowledgeIndex
MilvusOperationKnowledgeIndex
PgvectorUserKnowledgeIndex
PgvectorOperationKnowledgeIndex
NoOp variants
```

删除 `UpdateMemoryTool` 和模型直接写长期记忆的 ThreadLocal。

### 12.5 `harness-agent`

新增编排：

```text
MemoryExtractionScheduler
MemoryExtractionBatchWorker
MemoryExtractionRuleFilter
TraceScoringFeatureAdapter
TraceDependencyFeatureBuilder
OperationExperienceScorer
StructuredKnowledgeExtractor
KnowledgeProposalValidator
KnowledgeCompiler
KnowledgeConflictResolver
MemoryRetrievalQueryBuilder
PreferenceActivationContextBuilder
KnowledgeDiscoveryRouter
KnowledgeSearchTool
KnowledgeReadTool
LongTermKnowledgeRetriever
LongTermMemoryBudgetAllocator
KnowledgeContextFormatter
```

对象通过构造注入，只在 Factory/Composition Root 创建。

### 12.6 `harness-react`

扩展 `ReActRequest` 支持独立动态知识消息。阻塞、SSE、确认恢复和最终回答路径保持相同消息顺序。

### 12.7 `harness-server`

- 启动时初始化 Catalog、用户知识和操作知识索引，并保持现有 Document Chunk Index。
- 启动 Scheduler、Batch Worker、Outbox Projector 和 Graph Mutation Saga Worker。
- 关闭时优雅停止并释放任务。
- 知识上传先登记 immutable Artifact 和 Source Document，而不是直接把随机 Chunk 当成管理对象。
- `PUT /api/knowledge/{collection}/{documentId}` 改为创建完整文档 Revision并重新切块；取消单 Chunk 原地编辑语义。
- 删除文档/Collection先生命周期失效并写 Outbox；物理 Artifact Purge 必须是独立确认操作。
- Graph Build Handler 改为提交持久 Mutation Job；自然语言仍必须 Preview，只有确认后的 canonical JSON 才能进入 Saga。
- 增加认证后的 Trace Feedback API，按 Agent 响应返回的 `traceId` 写入明确 `positive/negative` metadata；写入失败返回 `ApiError`，不静默记录日志。
- 首期不增加新的复杂管理 UI，但现有知识管理页必须从 Chunk 列表迁移为 Document/Concept 列表。
- 列表、来源、导出、失效和删除 API 必须认证、分页、事务并返回明确 `ApiError`。

---

## 13. 配置

删除或停止使用：

```text
HARNESS_MEMORY_MIN_MESSAGES
HARNESS_MEMORY_MIN_USER_CHARS
HARNESS_MEMORY_LONGTERM_MAX_TOKENS
HARNESS_MEMORY_REFINEMENT_MIN_SCORE
HARNESS_MEMORY_REFINEMENT_STUCK_MINUTES
```

新增：

```text
HARNESS_MEMORY_STORE
HARNESS_KNOWLEDGE_CATALOG_COLLECTION
HARNESS_MEMORY_USER_KNOWLEDGE_COLLECTION
HARNESS_MEMORY_OPERATION_KNOWLEDGE_COLLECTION
HARNESS_MEMORY_LONGTERM_BUDGET_RATIO
HARNESS_MEMORY_RETRIEVAL_LANE_TOP_K
HARNESS_MEMORY_RETRIEVAL_FUSED_TOP_K
HARNESS_MEMORY_RETRIEVAL_DENSE_THRESHOLD
HARNESS_MEMORY_RETRIEVAL_SPARSE_THRESHOLD
HARNESS_MEMORY_RETRIEVAL_RRF_K
HARNESS_MEMORY_RETRIEVAL_NEAR_BEST_RATIO
HARNESS_MEMORY_RETRIEVAL_QUERY_MAX_TOKENS
HARNESS_MEMORY_EXTRACTION_ENABLED
HARNESS_MEMORY_EXTRACTION_DAILY_TIME
HARNESS_MEMORY_EXTRACTION_TIMEZONE
HARNESS_MEMORY_EXTRACTION_IDLE_MINUTES
HARNESS_MEMORY_EXTRACTION_MIN_TURNS
HARNESS_MEMORY_EXTRACTION_BATCH_SIZE
HARNESS_MEMORY_EXTRACTION_CONCURRENCY
HARNESS_MEMORY_EXTRACTION_STUCK_MINUTES
HARNESS_MEMORY_EXTRACTION_MAX_ATTEMPTS
HARNESS_MEMORY_EXTRACTION_CONTEXT_OVERLAP_MESSAGES
HARNESS_MEMORY_INDEX_OUTBOX_BATCH_SIZE
HARNESS_MEMORY_INDEX_OUTBOX_CONCURRENCY
HARNESS_MEMORY_INDEX_OUTBOX_STUCK_MINUTES
HARNESS_MEMORY_INDEX_OUTBOX_MAX_ATTEMPTS
HARNESS_MEMORY_OKF_EXPORT_ENABLED
HARNESS_KNOWLEDGE_COMPILER_ENABLED
HARNESS_KNOWLEDGE_COMPILER_BATCH_SIZE
HARNESS_KNOWLEDGE_SOURCE_REVISION_MAX_CHUNKS
HARNESS_KNOWLEDGE_INGEST_STUCK_MINUTES
HARNESS_KNOWLEDGE_INGEST_MAX_ATTEMPTS
HARNESS_KNOWLEDGE_ARTIFACT_ORPHAN_RETENTION_HOURS
HARNESS_GRAPH_MUTATION_WORKER_CONCURRENCY
HARNESS_GRAPH_MUTATION_STUCK_MINUTES
HARNESS_GRAPH_MUTATION_MAX_ATTEMPTS
```

`HARNESS_MEMORY_STORE=mysql|none` 与 `HARNESS_AUDIT_STORE` 解耦。关闭 Trace 时用户知识仍可运行，但 Operation Playbook 提取必须明确显示不可用。

不得修改根 `revision` 或发布说明，除非另有指令。

---

## 14. 迁移与清理

### 14.1 数据迁移

1. 新建 Batch、Concept、Revision、Source、Verification、Link 和 Outbox 表。
2. 新建 Artifact、Ingest Job、Graph Binding 和 Graph Mutation Job 表。
3. 增加 Session 水位和可空租户字段。
4. 在当前 `cyrene_test` Database 创建 Catalog、用户知识和操作知识索引 Collection。
5. 旧 `user_preferences` 是缺少来源的混合聚合文本，不自动拆分成偏好 Concept。
6. 旧聚合偏好离线备份，新运行时不双读、不 fallback。
7. 旧向量记忆若没有可靠来源和版本，不直接迁入 Stable Concept；只能进入人工审阅导入流程。
8. 现有知识库先做离线清单：按 `collection + documentId + fileName` 聚合 Chunk，再与受约束上传目录中的文件按内容 Hash 匹配。
9. 能唯一匹配原文件的旧文档可生成 `Source Document` Draft Revision并重建 Chunk；无法唯一匹配的只进入迁移报告，不伪造 Source URI。
10. 现有 Graph Schema 和 Graph Space 可以从 Registry/Neo4j 生成 Draft Concept；已有 Mutation Receipt 缺少来源时标记 `sourceUnknown`，不能补写虚假来源。
11. 新系统稳定后，再通过明确迁移脚本删除旧表/Collection；启动代码不得自动 DROP。

### 14.2 删除死代码

- `PreferenceRefinementWorker` 及测试。
- `UpdateMemoryTool`、注册逻辑和 ThreadLocal。
- `AgentPromptBuilder.appendLongtermMemory()`。
- 旧覆盖式 `PreferenceStore` 实现。
- `SessionLifecycleManager` 旧提炼分和请求触发逻辑。
- `AgentMemoryRuntime.scheduleRefinement()` 等重复入口。
- 失效 EnvKey、示例、注释和未使用导入。

短期 Compressor、Session Cache、Message Store 和 Tool Message Codec 不删除。

现有 Session Cleanup、Trace Cleanup 和 Artifact Purge 同步接入 Source Purge Guard；不得在新知识链路上线后继续绕过 `knowledge_revision_sources` 直接物理删除被引用证据。

---

## 15. 实施顺序

### Phase 1：模型和测试基线

- [ ] 固定短期 Session、压缩和 Cache 回归测试。
- [ ] 增加 Artifact/Concept/Revision/Source/Verification/Link/GraphBinding/Batch/Outbox 模型。
- [ ] 增加 `optionalTenantId`。
- [ ] 固定 `PreferenceKeyRegistry`、有限 `activationTags` 与 Knowledge Handle 协议。
- [ ] 固定 OKF 字段映射和 3% Budget 测试。

### Phase 2：MySQL 权威层

- [ ] 新建全部知识、Artifact、Graph Saga 和批次表、索引及 Repository。
- [ ] 实现文档 Ingest Job、内容寻址 Artifact 和受约束孤儿文件 Reconciler。
- [ ] 实现 Session `watermark/cutoff` 增量批次。
- [ ] 实现事务领取、超时恢复和稳定分页。
- [ ] 实现 Concept 乐观锁修订和偏好确定性 ID。
- [ ] 实现同事务 Outbox。
- [ ] 修复 Session 所有权查询。
- [ ] 新增按消息顺序计算完整轮次和最后完整轮边界的查询，不复用 `min(userCount, assistantCount)`。
- [ ] 为同一请求的 user/assistant/Tool Message 持久化 root `traceId`，消息事务失败显式暴露。
- [ ] 实现按 Session 分页读取 Trace。

### Phase 3：Trace 语义基础

- [ ] 拆分 `ExecutionStatus/ResultStatus`。
- [ ] 旧式成功映射为 `AVAILABLE`。
- [ ] Inspector 聚合本轮全部 ToolResult。
- [ ] 为内置 Tool 补齐能力标签。
- [ ] 实现候选依赖边和主/子 Run 等权评分。
- [ ] 实现认证后的 Trace Feedback 稳定绑定，禁止按相邻文本自动归因。
- [ ] 固定评分边界、旧 Trace 降级和学习信号测试。

### Phase 4：Knowledge Compiler

- [ ] 实现固定时间 Scheduler 和有界 Worker。
- [ ] 一次结构化调用输出 Concept Proposal。
- [ ] 实现来源、`stepRef/evidenceFlow`、脱敏和 Schema 校验。
- [ ] 实现偏好、情景和 Playbook 的匹配/修订规则；偏好 Key 与 Activation Tag 分开校验。
- [ ] 实现原子 Revision 提交、水位推进和幂等重试。
- [ ] 确保提取后没有第二次质量评分。

### Phase 5：索引投影

- [ ] 启动前读取并记录 `HARNESS_RAG_DATABASE/HARNESS_RAG_COLLECTION`；本计划只操作配置选中的 `cyrene_test`，不得自动选择旧项目的 `zhiduyuan.knowledge`。
- [ ] 在 `cyrene_test` 初始化 Catalog、用户知识和操作知识三个独立 Collection。
- [ ] 实现 Milvus/pgvector 等价投影。
- [ ] 实现 Outbox 领取、Upsert、旧 Revision 删除和失败恢复。
- [ ] 实现按稳定游标 Reindex 和孤儿向量清理。

### Phase 6：文档知识库与图谱 Wiki 化

- [ ] 上传文件改为 immutable Artifact + Source Document Revision。
- [ ] 上传、转换、编译和索引状态通过持久 Ingest Job 恢复。
- [ ] 完整文档更新重新转换、切块和建索引，删除单 Chunk 原地编辑入口。
- [ ] 复用现有文档 Collection Schema：来源字段进入 metadata，文件名沿用 `source`，只补现有字段索引并在维护窗口完成单套重建。
- [ ] 实现 Topic Synthesis、Entity/API Reference 的来源绑定编译。
- [ ] 为 Graph Schema/Graph Space 建立 Wiki Concept 和 Catalog 路由。
- [ ] 实现 `knowledge_graph_bindings` 和幂等 Graph Mutation Saga。
- [ ] 保留自然语言 Preview + 人工确认 + Schema 校验。
- [ ] Graph 查询继续走确定性 `findNodes/findNeighborhood`，不进入向量 Rerank。

### Phase 7：统一发现、召回和 Prompt

- [ ] 两阶段准备 RunToolCatalog 与长期知识。
- [ ] 实现 Knowledge Catalog、Source Chunk、偏好 Wiki View、用户情景和 Playbook 的独立发现路径；Router 按请求需要执行，不无条件查询全部索引。
- [ ] 实现 `KnowledgeDiscoveryRouter`，按 Concept 类型路由到文档、记忆或图谱执行器。
- [ ] 只注册模型可见的 `knowledge_search` 与 `knowledge_read`；移除旧三个知识 Tool 的模型注册，不保留双路径。
- [ ] 实现 RRF、当前 Revision 批量校验和 `requiredTools` 过滤。
- [ ] 实现带类型和稳定 ID 的 Knowledge Handle；`knowledge_read` 把 Handle 视为不可信输入并重新校验全部作用域，只做有界展开。
- [ ] 无可信 Graph Scope 时使用可读 Graph Space Concept缩小候选；有可信 Scope 时保持最短路径。
- [ ] 偏好只读取当前 Head 并按 `PreferenceActivationContext` 过滤；情景多条；Playbook 最多一条。
- [ ] 动态知识移出 System Prompt。
- [ ] Trace 记录 ID、版本、Token、耗时和命中原因。

### Phase 8：OKF 与迁移

- [ ] 实现权限隔离的 OKF Bundle 导出。
- [ ] 生成 `index.md/log.md`，但不把文件当在线事实源。
- [ ] 后续实现带 Schema、来源和冲突审阅的 OKF 导入。
- [ ] 备份旧数据、切换新运行时并删除死代码。
- [ ] 清理旧配置并更新 `.env.example`。

---

## 16. 测试与验证

### 16.1 增量批次

- 2 轮不满足，3 轮满足。
- 空窗 59 分钟不满足，60 分钟满足。
- Tool/Summary 不计入轮次。
- 尾部只有 user、没有 assistant final 时，cutoff 停在上一完整轮且不推进越过未完成消息。
- 两个 Worker 并发领取只有一个成功。
- 提取中新增消息不进入当前 cutoff，但下一空窗批次可处理。
- 只有由本批 user Message 的 `trace_id` 精确绑定的 root Trace 及其显式子树可参与评分；时间相邻 Trace 不进入。
- 重叠上下文可以帮助理解，但不能单独生成新 Revision。
- Batch 失败不推进水位；重试边界和 ID 不变。
- 达到最大尝试次数后进入终态 failed、告警且阻止越过失败范围；显式重放后可继续。

### 16.2 Concept/Revision

- Revision 不可更新，只能追加。
- 两个 Worker 并发修订同一 Concept 时，乐观锁阻止丢失更新。
- `currentRevisionId`、Source、Link、Outbox 和水位同事务提交/回滚。
- 偏好用户 1:N，同一 key 只有一个 Concept Head。
- 同 key 新表达创建 Revision，旧 Revision 不参与当前注入。
- 未注册偏好进入唯一 `other` Concept；连续追加两个未知偏好会保留两个 `itemId`，按 `itemId` 修订/移除时不影响其他条目。
- 已能映射到 Registry Key 的偏好不能错误落入 `other`，模型伪造 `targetItemId` 必须被拒绝。
- Registry Key 使用已注册默认 `activationTags`；`other` 只能选择允许标签，无法判断适用场景的条目不能提升为全局 Stable 偏好。
- 明确偏好可直接 stable；行为归纳偏好在跨 Session 一致或用户确认前保持 draft。
- 合法且非 Legacy 的 60 分以上 Playbook 可 stable；Legacy 不可验证候选保持 draft。
- `deprecated` 立即从权威召回失效。
- 可空租户不会跨租户匹配。
- 普通清理不会删除被 Revision 引用的 Message/Trace/Artifact；合规删除会先使受影响当前知识失效并产生索引删除任务。

### 16.3 Trace 评分

- Tool 成功但未验证为 `AVAILABLE`，不获得可信度分。
- 明确验证证据才可成为 `VERIFIED`。
- 前序可用但后序失败时 Inspector 不提前 PASS。
- 无标签外部 Tool 不按名称猜能力。
- 检索后生成且实体相关时产生 `RESEARCH_BEFORE_GENERATION`。
- 只有顺序时为 `SEQUENTIAL_ONLY`，不加分。
- 主/子 Agent 各评分一次，失败子 Run 计 0 后等权平均。
- 结构化提取可抽象“生成前研究并验证”，不保存具体对象结论。
- 提取后只做合法性校验，不二次评分。
- 只有显式绑定到 Trace 的负反馈才硬筛选，时间相邻的普通用户文本不自动归因；显式正反馈才获得 +3。
- Playbook Revision 的 `qualityScore` 等于其来源任务树的确定性 `taskScore`，追加相同策略来源时不改分。

### 16.4 Outbox 和向量索引

- Catalog、用户知识和操作知识 Collection 均创建在 `cyrene_test` 而非 `default`。
- Outbox 重复执行幂等。
- 新 Revision Upsert 后旧 Revision 删除。
- 删除延迟期间，权威校验仍拒绝旧 Revision。
- deprecated Concept 的待处理 Upsert 被转换为删除。
- Reindex 使用稳定游标，能删除孤儿向量。
- Milvus/pgvector 实现相同作用域、阈值和 RRF 语义。
- Catalog 不包含 Preference、User Episode 或 Operation Playbook；后两者只存在于各自专用投影，Preference 只走 MySQL。
- 文档索引升级始终复用同一配置和一套 Collection；不创建 v2/临时 Collection/双写链路，维护窗口内重建完成后直接恢复服务。

### 16.5 文档知识库

- 重复上传同内容得到相同 Artifact Hash，但保留请求审计。
- 文件落盘后进程崩溃可以由 Ingest Job 重试或受约束 Reconciler报告。
- 文档更新创建新 Source Document Revision，不修改旧 Chunk。
- 当前 Revision 整篇重新切块，Chunk ID 重试稳定。
- 单 Chunk 原地编辑 API 被拒绝或迁移到完整文档 Revision语义。
- Source Document deprecated 后当前 Chunk 全部失效，原始 Artifact 按保留策略处理。
- Topic Synthesis 的每个关键 Claim 能解析到真实 Source Revision。
- `knowledge_read` 的 Document Handle 只读取同一 Source Document Revision 的有界窗口。
- 旧文档迁移无法唯一匹配原文件时进入报告，不生成虚假 Source。
- 同名文件可以属于不同 `documentId`，同一逻辑文档更名后仍保持相同 `documentId` 并创建新 `revisionId`。
- 明确文件名查询可以发现目标文档，但最终 Chunk 读取使用 `documentId/revisionId`，不会把文件名当作唯一键。
- Dense Embedding 不受路径、扩展名和纯技术分隔符等文件名噪声主导；有业务意义的日期/版本通过规范标题或明确元数据保留，章节和正文可以正常混合召回。

### 16.6 知识图谱

- Graph Schema/Space Concept 变更跟随 Registry/Neo4j 状态。
- 无可信 Graph Scope 时 Catalog 只返回当前租户可读的 Graph Space。
- 有可信 Graph/Subject Scope 时 Wiki 不能扩大作用域。
- Graph Mutation Job 重试同一 `requestId` 不重复写图。
- 同一 `requestId` 更换 Payload Hash 被拒绝。
- Neo4j 成功、MySQL 提交前崩溃时可重放并完成 Binding/Outbox。
- 图结果不进入向量 Rerank，仍受 Schema 脱敏和深度/数量上限控制。
- 普通节点不会自动生成海量 Entity Concept；满足晋升规则时才创建。

### 16.7 统一发现与召回

- 用户知识严格按认证用户和可空租户过滤。
- 操作知识只允许全局或当前租户。
- 当前 Revision 校验失败的候选不注入。
- stale 知识不会伪装成当前确定事实。
- `requiredTools` 不可用的 Playbook 不注入。
- 近似相关区间内新 Revision 优先，低相关新知识不能越级。
- 偏好只注入当前 Head，情景允许多条，Playbook 最多一条。
- 代码偏好不进入纯图片任务，图片偏好不进入纯代码任务；`GENERAL_RESPONSE` 偏好可进入最终回复，`other` 按 item 独立过滤。
- Topic Concept 可直接回答时不强制读取原始 Chunk；需要原文核验时可以追溯。
- Graph Space Concept 只产生路由提示，最终实体关系必须来自 Neo4j。
- Router 不混加 Concept、Chunk、Graph 和 Memory 的异构分数。
- Router 按请求执行必要分支，不因“统一发现”无条件查询 Catalog、文档、用户记忆、操作经验和图谱全部后端。
- 模型 Tool Catalog 只出现 `knowledge_search` 和 `knowledge_read`，旧三个知识 Tool 不可见；内部 Document/Graph 执行器仍保持独立协议。
- 模型篡改 Handle 中的 Revision、Collection、Graph 或 Owner 信息时必须被权限/当前版本校验拒绝。
- 在线搜索与读取路径不生成或解析 OKF Bundle。
- 1M 上下文得到 30K 长期预算，Block 不被截断。

### 16.8 OKF

- 导出字段符合 OKF v0.2。
- Source、Generated、Verified、Status、StaleAfter 映射准确。
- Cyrene 扩展使用 `x-cyrene-*`。
- 不同用户/租户 Bundle 严格隔离。
- 导出内容不包含凭证和未脱敏 Tool 结果。
- 导出后可重新解析并保持未知扩展字段。

### 16.9 回归命令

```powershell
mvn test -pl harness-core,harness-input,harness-tool,harness-trace,harness-react,harness-agent,harness-server -am -DskipITs
mvn clean test -DskipITs
git diff --check
node --check harness-server/src/main/resources/public/js/app.js
node --check harness-server/src/main/resources/public/js/api.js
node --check harness-server/src/main/resources/public/js/i18n.js
```

数据库集成测试必须在对应容器可用时运行，未连接不能视为通过。

---

## 17. 可观测性

提取：

```text
memoryExtractionBatches{outcome}
memoryExtractionSkipped{reason}
memoryExtractionDurationMs
memoryExtractionModelTokens
memoryExtractionConcepts{type,operation=create|revise|deprecate}
memoryExtractionWatermarkLag
operationExperienceRunScore
operationExperienceTaskScore
```

编译与修订：

```text
knowledgeConcepts{type,status}
knowledgeRevisionConflicts
knowledgeProposalRejected{reason}
knowledgeVerificationEvents{type,result}
knowledgeArtifacts{type,status}
knowledgeDocumentCompiler{outcome}
knowledgeIngestJobs{status,outcome}
knowledgeTopicRevisions{operation}
knowledgeGraphMutationJobs{status,outcome}
```

索引：

```text
knowledgeIndexOutboxDepth{target,status}
knowledgeIndexProjectionLagMs{target}
knowledgeIndexOperations{target,operation,outcome}
knowledgeIndexOrphanVectors
```

召回：

```text
knowledgeRetrievalLatencyMs{type,provider}
knowledgeRetrievalCandidates{type,lane}
knowledgeRetrievalFiltered{type,reason}
knowledgeRetrievalSelected{type}
knowledgeRetrievalTokens{type}
knowledgeCurrentRevisionMismatch
knowledgeDiscoveryRoutes{target,outcome}
knowledgeSourceChunkReads{outcome}
knowledgeGraphRoutes{outcome}
memoryLongTermBudgetTokens
memoryLongTermUsedTokens
```

指标标签不得包含 userId、tenantId、Session ID、Concept ID 或 Revision ID。单次 Trace 可记录有界 ID 列表用于诊断。

---

## 18. 最终验收

- [ ] 短期 Session、Cache 和压缩保持兼容。
- [ ] 旧聚合偏好和 `update_memory` Tool 完全移除。
- [ ] 原始 Message/Trace 保持不可变并可追溯。
- [ ] 每轮 Message 与 root Trace 使用稳定 `traceId` 关联，Playbook 来源不依赖文本或时间猜测。
- [ ] Session/Trace/Artifact 普通清理受 Source Purge Guard 保护；合规删除不会留下仍可召回的孤儿知识。
- [ ] Session 使用 watermark/cutoff 增量批次，后续消息不会永久遗漏。
- [ ] cutoff 只到最后完整轮次，未完成尾部消息不会被水位越过；失败批次达到上限后显式阻塞和告警。
- [ ] 用户偏好、用户情景和操作经验统一为 Concept/Revision。
- [ ] 偏好是用户 1:N、同 key 一个当前 Head。
- [ ] 未注册偏好使用唯一 `other` Head，并通过后端生成的 `itemId` 独立追加、修订和移除，不互相覆盖。
- [ ] 偏好 Key 只定位 Head，`activationTags` 决定本轮注入；不相关偏好不占用 3% 预算。
- [ ] Operation Playbook 通过 Revision 演化，不在写入热路径硬删旧向量。
- [ ] Concept、Revision、Source、Verification、Link、Outbox 和水位事务一致。
- [ ] Milvus/pgvector 只是投影，所有候选经过 MySQL 当前 Revision 校验。
- [ ] Catalog、用户知识和操作知识索引物理分离并位于当前选择的 Database。
- [ ] 上传文件登记为 immutable Artifact，文档更新产生完整 Source Document Revision。
- [ ] 文档上传使用持久 Ingest Job，文件/数据库崩溃窗口可观测、可恢复。
- [ ] 文档 Chunk 是当前 Revision 的可重建投影，管理 API 不再直接修改单 Chunk。
- [ ] 文件名仅用于展示、关键词发现和管理过滤；所有来源链接与 Chunk 读取使用稳定 `documentId/conceptId + revisionId + artifactId`。
- [ ] Topic/API/Entity Concept 可以积累跨文档综合，并能追溯到 Source Revision。
- [ ] Graph Schema/Space 已进入 Wiki Catalog，但具体节点、关系和路径仍由 Neo4j 权威查询。
- [ ] Graph Mutation 使用持久 Job、现有 `requestId` 幂等写入和可恢复 Binding/Outbox。
- [ ] Knowledge Discovery Router 可以选择 Concept、文档、记忆或图谱，而不混加异构分数。
- [ ] 模型只看到 `knowledge_search` 和 `knowledge_read`；内部文档/图谱执行路径分离，Handle 每次读取都重新授权。
- [ ] Trace 在提取前确定性评分，主子 Run 等权平均，模型不生成分数。
- [ ] 用户反馈只有通过稳定 `traceId` 绑定后才影响筛选和评分，不从相邻文本猜测。
- [ ] “先研究再生成”可以从依赖证据抽象，不能把未验证搜索内容沉淀成事实。
- [ ] 当前偏好 Wiki View、多个情景和一个 Playbook 共享 3% 预算。
- [ ] 动态知识不进入稳定 System Prompt。
- [ ] OKF v0.2 Bundle 可按权限导出，并包含来源、验证、生命周期和时效。
- [ ] 在线检索和 Prompt 组装不依赖 OKF 物化；OKF 故障不影响未启用导出的普通问答。
- [ ] 所有增长型查询稳定分页，所有关联写入使用事务。
- [ ] 存储失败与“没有知识”明确区分，不使用隐式回退。

---

## 19. 非目标

- 不把短期记忆改成固定最近五条。
- 不把 Markdown 文件、Git 或 OKF Bundle 作为在线事务数据库。
- 不把 OKF 实现成聚合全部后端内容再发送给模型的在线检索 Tool。
- 不让 Wiki/OKF 的信任字段替代用户和租户权限。
- 不增加 `agentId` 或 `applicationId`。
- 不让模型直接决定 Concept ID、Revision、质量分、验证层级或失效状态。
- 不让历史知识覆盖业务系统实时数据。
- 不在向量库中实现跨记录事务。
- 不把用户知识和全局操作知识放入同一个物理索引。
- 不把所有原始文档 Chunk、Neo4j 节点和关系复制成 Wiki Concept。
- 不用 Wiki 替换 Neo4j Schema、事务、路径和邻域查询。
- 不把 Neo4j 图结果送入文档/记忆 Rerank。
- 不因为 Milvus 不可用而静默回退到文件、MySQL LIKE 或其他 Provider。
- 首期不实现复杂知识图谱推理；`knowledge_links` 只支持来源、修订和有限关系导航。
- 首期不建立长期记忆管理 UI。
- 首期 OKF 以导出为主；导入必须在后续增加权限、Schema、来源和冲突审阅后开放。
