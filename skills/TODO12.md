# TODO12：三层记忆重构、会话提取与混合召回

## 0. 文档定位

本计划重构 Cyrene Agent 现有长期记忆机制。短期会话上下文保持当前“完整历史 + 按上下文压力压缩”的设计，不改成固定最近五条，也不改成文件存储。长期记忆拆分为用户情景记忆、用户习惯偏好和系统操作经验，并分别使用适合的存储与召回方式。

本计划是 TODO12 的实施依据。当前源码和测试仍是实施时的事实来源；实施前必须再次核对接口、依赖和数据库状态。

### 0.1 已确认决策

1. **短期记忆保持现状。** Session 消息继续存储在 MySQL，使用当前内存/Redis Session Cache，并由现有压缩机制控制上下文长度。
2. **用户情景记忆使用向量存储。** 情景正文生成 Dense Embedding，同时使用 BM25/Sparse 参与混合检索；时间、用户和租户是结构化字段，不进入向量正文。
3. **用户习惯偏好使用 MySQL 原子追加存储。** 每条记录只表达一个偏好事实；不再把所有内容合并为一个 `memory` 文本，不保留 `category`，也不覆盖历史记录。
4. **系统操作经验使用向量存储。** 从通过规则筛选的 Trace 中提取，使用 Dense + BM25 混合检索；不自动沉淀为文件，不新增操作经验 SQL 元数据表。
5. **不增加 `agentId` 和 `applicationId`。** 当前框架面向一个主 Agent，不为假设中的多 Agent、多应用提前增加作用域。
6. **所有新增 `tenantId` 字段均可空。** 非多租户系统保存 `NULL`；多租户系统保存可信代理端透传并校验后的租户 ID。
7. **用户身份和租户身份不能由模型提供。** `userId` 来自认证结果，`tenantId` 来自可信代理边界；模型参数只能表达检索问题，不能扩大身份范围。
8. **长期记忆总预算固定为模型上下文窗口的 3%。** 三种长期记忆共享预算，不设置固定子比例，也不为了用满预算注入低相关内容。
9. **相似向量记忆允许同时存在。** 首期不做写入时语义去重，不为去重增加操作经验 SQL 主表、证据表和 Outbox。
10. **时间只在高相关候选中优先。** 系统操作经验最终只注入一条；先满足相关性和工具可用性，再在近似相关候选中选择最新记录。
11. **提取任务每天固定时间异步触发。** 调度器扫描“至少 3 轮完整交互，且连续 1 小时无新增操作”的 Session。
12. **3 轮定义为 3 个完整的 `user + assistant` 对。** Tool 消息、Summary 和未完成的单边消息不计入轮次。
13. **提取时先冻结消息边界。** Worker 只读取领取任务时的 `cutoffMessageId` 及其之前的数据。
14. **领取任务后出现的新消息首期不处理。** 不打断当前提取、不自动创建第二次提取；如何处理已提取 Session 的后续消息作为本计划的明确待思考项。
15. **结构化提取后不再进行第二次质量评分。** 是否值得提取由代码在模型调用前按主/子 Agent 任务树完成硬筛选和确定性评分；模型调用后只做 Schema、来源、工具、脱敏和长度校验。
16. **现有长期记忆实现直接替换。** 删除单文本偏好合并、旧质量打分、请求触发式超时提炼和 `update_memory` Tool，不保留双读或隐式回退。
17. **两类向量记忆使用独立 Collection。** 用户情景记忆和系统操作经验从归属、权限、生命周期和召回语义上分离，不能只用 `memoryType` 混在同一物理 Collection。
18. **两个 Collection 创建在当前选择的向量数据库中。** 当前 `.env` 选择 `HARNESS_RAG_PROVIDER=milvus`、`HARNESS_RAG_DATABASE=cyrene_test`，因此两个 Milvus Collection 都必须创建在 `cyrene_test` 数据库内，不能创建在 `default`。
19. **主 Agent 与全部子 Agent Run 独立评分后取算术平均。** 每个实际 Run 只计算一次，不给主 Agent 或某类子 Agent 额外权重；子 Agent 失败计 0 分并通过平均值降低任务分，不能仅因一个子 Agent 失败就直接否决已成功回退的主任务。

---

## 1. 当前实现基线与需要替换的内容

### 1.1 当前短期记忆

当前短期链路为：

```text
SessionStore / MessageStore（MySQL）
  → SessionMessageCache（Redis 或内存）
  → SessionContextLoader
  → MemoryCompressor
  → ReAct historyMessages
```

该链路继续保留：

- `SessionContextLoader` 的 Cache First、Database Refill 行为。
- `MessageWriteWorker` 的异步消息持久化职责，但实施时必须把伪批量逐条提交改为真实事务批量写入。
- `MemoryCompressor` 的上下文压力触发压缩。
- Tool 消息编码和会话恢复能力。
- 会话缓存与 Provider Prompt Cache 的独立观测。

TODO12 不规定短期记忆占整个上下文的固定百分比。短期上下文顺其自然增长，由压缩机制负责控制。

### 1.2 当前长期记忆问题

现有实现需要整体替换：

- `PreferenceRefinementWorker` 读取 Session 后，把身份、偏好、习惯、项目和目标等内容合并成一个自由文本。
- `PreferenceStore.upsert(userId, "memory", ...)` 最终只保留一条聚合记录。
- `AgentPromptBuilder` 把动态长期记忆直接追加进 System Prompt，长期记忆变化会破坏稳定 Prompt 前缀。
- `SessionLifecycleManager` 使用最少消息数、字符数和四项分数筛选；`.env.example` 中的分数示例与实际最大分不一致。
- 请求路径和清理调度器都可能提交提炼任务，职责重复。
- `PreferenceRefinementWorker` 使用进程内队列，进程退出后任务丢失。
- `update_memory` Tool 允许模型直接写入自由文本记忆，与新的统一提取流程冲突。
- `sessions.refinement_status` 状态定义和 Worker 完成状态不完整，无法形成可靠生命周期。

### 1.3 当前向量实现可复用能力

当前 `VectorStore` 已具有：

- Milvus Dense Vector 检索。
- Milvus BM25 Sparse 检索。
- Milvus Hybrid Search + RRF。
- 显式 ID Upsert。
- pgvector 的向量、关键词和混合检索。

但现有 `VectorStore.Document`、Collection Schema 和管理接口面向知识库 Chunk，不应直接把用户记忆伪装成知识文档。TODO12 新增长期记忆专用接口和 Schema，底层复用连接池、Embedding Provider 和通用检索实现。

---

## 2. 目标架构

```text
可信请求边界
  ├─ authenticated userId
  └─ optional tenantId
          │
          ▼
Session 短期记忆
  ├─ MySQL messages
  ├─ Redis / memory cache
  └─ context-pressure compression
          │
          ├─────────────────────────────────────┐
          │                                     │
          ▼                                     ▼
每日 Session 提取                         每次请求长期记忆召回
  ├─ Session >= 3 turns                   ├─ SQL：用户习惯偏好
  ├─ idle >= 1 hour                       ├─ Vector：用户情景记忆
  ├─ freeze cutoffMessageId               └─ Vector：系统操作经验
  ├─ message + filtered Trace                     │
  ├─ one structured extraction call               ▼
  ├─ preferences -> MySQL append             3% Token Allocator
  └─ episodic/experience -> active Vector DB       │
                                                    ▼
stable System + stable Tool Catalog + Session history
  + dynamic memory context + current user message
```

### 2.1 三类长期记忆

| 类型 | 归属 | 事实来源 | 主存储 | 召回方式 |
|---|---|---|---|---|
| 用户情景记忆 | `userId + optional tenantId` | Session 消息 | 当前选中的向量库 | Dense + BM25 + 时间优先 |
| 用户习惯偏好 | `userId + optional tenantId` | Session 消息 | MySQL 追加表 | 原子事实、时间倒序、有界读取 |
| 系统操作经验 | 全局或 `optional tenantId` | 通过规则筛选的 Trace | 当前选中的向量库 | Dense + BM25 + 工具过滤 + 最新优先 |

系统操作经验不按用户隔离，提取时必须删除具体用户、订单、手机号、凭证等实例数据，只保留可复用的任务条件、错误特征、工具顺序、参数角色和恢复策略。

---

## 3. 身份、租户和所有权边界

### 3.1 可选租户语义

新增存储字段统一使用：

```text
tenantId == NULL  → 非多租户数据或全局系统经验
tenantId != NULL  → 对应租户范围内的数据
```

不得把缺失租户自动写成 `000000`。现有 `AgentContext.tenantId()` 的默认租户行为仍可服务知识图谱兼容，但记忆链路需要新增明确的可空读取方法，例如：

```java
String optionalTenantId();
```

记忆模块只能使用该可空值，不能复用默认租户值冒充真实租户。

### 3.2 召回范围

用户情景记忆和偏好使用严格相等范围：

```text
请求 tenantId 有值：tenantId = requestTenantId AND userId = authenticatedUserId
请求 tenantId 为空：tenantId IS NULL AND userId = authenticatedUserId
```

系统操作经验允许全局经验：

```text
请求 tenantId 有值：tenantId = requestTenantId OR tenantId IS NULL
请求 tenantId 为空：tenantId IS NULL
```

### 3.3 Session 所有权

当前通过请求 Session ID 恢复会话时，必须同时验证：

```text
session.userId == authenticatedUserId
AND tenantId 使用 null-safe equality 匹配
```

新增 `findByIdAndOwner(sessionId, userId, tenantId)` / `findActiveByOwner(...)`，禁止先按 Session ID 找到记录后再默认信任。管理 API 同样不能从 Query 参数自由指定其他用户读取记忆。

---

## 4. MySQL 数据模型

### 4.1 `sessions` 调整

保留 Session 作为提取任务状态的唯一协调记录，不新增复杂的经验候选状态机。

```sql
ALTER TABLE sessions
    ADD COLUMN tenant_id VARCHAR(128) NULL,
    CHANGE COLUMN refinement_status memory_extraction_status VARCHAR(20)
        NOT NULL DEFAULT 'none',
    ADD COLUMN memory_extraction_cutoff_message_id BIGINT NULL,
    ADD COLUMN memory_extraction_started_at DATETIME(3) NULL,
    ADD COLUMN memory_extraction_completed_at DATETIME(3) NULL,
    ADD COLUMN memory_extraction_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN memory_extraction_error VARCHAR(1024) NULL;
```

状态只保留：

```text
none
pending
in_progress
done
failed
```

索引至少包含：

```sql
(memory_extraction_status, last_active, id)
(tenant_id, user_id, last_active, id)
```

领取任务必须在一个 MySQL 事务中完成：

1. 使用稳定游标查询满足条件的 Session，读取 `limit + 1`。
2. `SELECT ... FOR UPDATE SKIP LOCKED` 或 CAS 抢占。
3. 写入 `memory_extraction_status='in_progress'`。
4. 冻结当前 `MAX(messages.id)` 到 `memory_extraction_cutoff_message_id`。
5. 提交事务后再将任务交给异步 Worker。

### 4.2 新用户偏好追加表

新建 `user_preference_memories`，不继续使用覆盖式 `user_preferences` 作为运行时事实源。

```sql
CREATE TABLE user_preference_memories (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           VARCHAR(128) NULL,
    user_id             VARCHAR(128) NOT NULL,
    content             TEXT         NOT NULL,
    source_session_id   VARCHAR(64)  NOT NULL,
    source_message_ids  JSON         NULL,
    observed_at         DATETIME(3)  NOT NULL,
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    INDEX idx_preference_owner_time
        (tenant_id, user_id, observed_at DESC, created_at DESC, id DESC),
    INDEX idx_preference_source_session (source_session_id)
);
```

写入语义：

- 每次 Session 提取只追加，不覆盖历史偏好。
- 每条记录只表达一个可独立理解的偏好，不把语言、格式、工具习惯等多个事实重新聚合进一条文本。
- 明确表达的偏好可以写入。
- 隐式偏好只有在同一 Session 中出现重复证据时才允许写入，不能根据单次行为猜测稳定偏好。
- 新旧偏好冲突时，两条记录可以同时存在；动态上下文按时间倒序排列，并明确指示较新的用户表达优先。首期不增加分类、覆盖或自动撤销关系。
- 同一提取任务的偏好插入与 Session 标记 `done` 使用同一 MySQL 事务。

所有增长型读取使用 `(observed_at, created_at, id)` 稳定游标、明确 `ORDER BY` 和 `limit + 1`。管理接口若后续增加，返回共享 `PageResponse<T>`。

### 4.3 Trace 查询能力

提取系统操作经验需要按 Session 读取 Trace。扩展 `TraceStore`：

```java
PageResponse<AgentTrace> findBySession(
        String sessionId,
        TraceCursor cursor,
        int limit
);
```

MySQL 索引调整为：

```sql
INDEX idx_trace_session_time (session_id, timestamp, trace_id)
```

禁止使用一次性无上限 `listRecent()` 扫描全部 Trace。

---

## 5. 当前向量数据库中的独立记忆 Collections / Tables

### 5.1 Provider 选择规则

两类记忆向量实现复用当前已经选择的：

```text
HARNESS_RAG_PROVIDER
HARNESS_RAG_URL
HARNESS_RAG_DATABASE
Embedding Provider / Model / Dimension
```

不再增加重复的连接地址、数据库和 Embedding 配置。分别增加两个物理集合/表名称：

```properties
HARNESS_MEMORY_EPISODIC_COLLECTION=cyrene_user_episodic_memories
HARNESS_MEMORY_EXPERIENCE_COLLECTION=cyrene_operation_experiences
```

初始化器只初始化当前选中的 Provider：

- `milvus`：在当前 Milvus Database 内分别创建用户情景记忆和系统操作经验 Collection。
- `pgvector`：在当前 PostgreSQL 数据库/Schema 内分别创建两张记忆表。
- `none`：用户情景记忆和系统操作经验明确不可用；SQL 偏好仍可用，不静默切换文件或其他向量库。

### 5.2 当前 Milvus 落点

当前项目实际选择：

```text
HARNESS_RAG_PROVIDER=milvus
HARNESS_RAG_DATABASE=cyrene_test
HARNESS_RAG_COLLECTION=cyrene_test
```

实施 TODO12 时必须得到：

```text
Milvus Database: cyrene_test
  ├─ cyrene_test                    现有知识库 Collection
  ├─ cyrene_user_episodic_memories  新用户情景记忆 Collection
  └─ cyrene_operation_experiences   新系统操作经验 Collection
```

启动日志和集成测试必须同时输出并断言 Database 与两个 Collection，防止连接池回落到 `default` 后误建表。

### 5.3 用户情景记忆 Milvus Schema

新增 `UserEpisodicMemoryMilvusInitializer`，Schema 至少包含：

```text
id                  VarChar(64)       primary key
tenant_id           VarChar(128)      nullable
user_id             VarChar(128)      required
source_session_id   VarChar(64)
content             VarChar(65535)    analyzer enabled
event_time          Int64             epoch millis
created_at          Int64             epoch millis
metadata            JSON
embedding           FloatVector
sparse_content      SparseFloatVector
```

约束：

- `user_id` 必填，`tenant_id` 允许为空。
- BM25 Function 使用 `content -> sparse_content`。
- Dense 索引沿用 HNSW + COSINE。
- Sparse 索引使用 `SPARSE_INVERTED_INDEX + BM25`。
- 对 `tenant_id`、`user_id`、`event_time`、`created_at` 和 `source_session_id` 分别建立 Scalar Index；`tenant_id/user_id` 用于两路身份过滤，时间字段用于融合后的确定性排序，来源字段用于按 Session 重建和删除。

### 5.4 系统操作经验 Milvus Schema

新增 `OperationExperienceMilvusInitializer`，Schema 至少包含：

```text
id                  VarChar(64)       primary key
tenant_id           VarChar(128)      nullable
source_session_id   VarChar(64)
source_trace_id     VarChar(64)
task_type           VarChar(128)
content             VarChar(65535)    analyzer enabled
quality_score       Double
event_time          Int64             epoch millis
created_at          Int64             epoch millis
required_tools      JSON
metadata            JSON
embedding           FloatVector
sparse_content      SparseFloatVector
```

约束：

- 不保存 `user_id` 字段，避免在物理模型上暗示操作经验归某个用户所有。
- `tenant_id` 允许为空；空值表示全局系统经验。
- BM25 Function、Dense 和 Sparse 索引与情景记忆使用相同底层能力，但独立创建和独立调参。
- 对 `tenant_id`、`quality_score`、`task_type`、`event_time`、`created_at`、`source_session_id` 和 `source_trace_id` 分别建立 Scalar Index；Dense/BM25 两路在 ANN 前应用租户和 `quality_score >= 60` 过滤。
- `required_tools` 用于召回后的 RunToolCatalog 子集校验，不作为模型可修改的权限字段。
- `required_tools` 和 `metadata` 首期不建 Scalar/JSON 索引，因为它们不参与 Milvus 两路初筛；不得为尚未下推的召回后逻辑增加无效索引。
- `source_trace_id` 保存主 Agent 的根 Trace ID；参与评分的子 Trace ID 只放入有界 `metadata`，不建立额外 SQL 证据表。
- `quality_score` 保存主/子 Agent Run 的最终平均分，由代码计算并写入，模型不得生成或修改。

### 5.5 pgvector 等价表

当 `HARNESS_RAG_PROVIDER=pgvector` 时，分别创建：

```text
user_episodic_memories
  id, tenant_id NULL, user_id NOT NULL, source_session_id,
  content, event_time, created_at, metadata JSONB,
  embedding VECTOR(dim), search_vector TSVECTOR

operation_experiences
  id, tenant_id NULL, source_session_id, source_trace_id,
  task_type, content, quality_score DOUBLE PRECISION, event_time, created_at,
  required_tools JSONB, metadata JSONB,
  embedding VECTOR(dim), search_vector TSVECTOR
```

索引包含：

- Embedding HNSW。
- `search_vector` GIN。
- 用户情景表使用 `(tenant_id, user_id, event_time DESC, created_at DESC, id DESC)` B-Tree，并为 `source_session_id` 建立独立 B-Tree。
- 系统操作经验表使用 `(tenant_id, quality_score, created_at DESC, event_time DESC, id DESC)` 和 `(tenant_id, task_type, quality_score, created_at DESC, id DESC)` B-Tree，并为 `source_session_id`、`source_trace_id` 分别建立独立 B-Tree。
- `required_tools` 首期在有界融合候选上执行应用层子集校验，不建立未被查询使用的 GIN；只有确认下推为 JSONB containment 查询后才随 SQL 一起增加对应索引。

当前运行选择 Milvus，因此实施和验收不得因为 pgvector 代码存在而同时写入两个后端。

### 5.6 ID 与重复策略

首期不进行语义去重，相似经验和相似情景允许同时存在。ID 只保证同一 Session 提取重试幂等：

```text
SHA-256(
  extractorVersion
  + sourceSessionId
  + collectionKind
  + itemIndex
)
```

重试同一提取快照时使用相同 ID 执行 Upsert。新 Session 即使提取出相似内容，也生成不同记录。

---

## 6. 每日异步提取机制

### 6.1 调度时机

使用按时区计算下一次运行时间的单次调度，不使用“进程启动后每 24 小时”代替固定时间点。

建议配置：

```properties
HARNESS_MEMORY_EXTRACTION_ENABLED=true
HARNESS_MEMORY_EXTRACTION_DAILY_TIME=02:00
HARNESS_MEMORY_EXTRACTION_TIMEZONE=Asia/Shanghai
HARNESS_MEMORY_EXTRACTION_IDLE_MINUTES=60
HARNESS_MEMORY_EXTRACTION_MIN_TURNS=3
HARNESS_MEMORY_EXTRACTION_BATCH_SIZE=100
HARNESS_MEMORY_EXTRACTION_CONCURRENCY=2
HARNESS_MEMORY_EXTRACTION_STUCK_MINUTES=30
```

配置必须进入 `EnvKey`，启动时校验时间格式、时区、正数范围和并发上限，不能散落硬编码。

### 6.2 Session 资格规则

Session 只有同时满足以下条件才进入 `pending/in_progress`：

```text
conversationTurns >= 3
lastActive <= now - 1 hour
memoryExtractionStatus IN ('none', 'failed')
```

不再使用：

- 最少用户字符数。
- 最少消息数 5。
- 平均回复长度。
- 是否有问号。
- 四项加权分数。
- 请求到来时被动提交长期记忆提取。

Session 可以处于 `active`、`timeout` 或 `ended`；长期记忆提取资格只由完整轮次、空窗时间和提取状态决定，不依赖旧 Session timeout 状态。

### 6.3 冻结快照

Worker 领取任务时冻结：

```text
sessionId
userId
optional tenantId
cutoffMessageId
extractionStartedAt
extractorVersion
```

读取范围：

```sql
WHERE session_id = ? AND id <= cutoff_message_id
ORDER BY id ASC
```

消息读取必须使用稳定游标分批加载，不能无上限加载增长中的 Session。Tool 过程以相同 Session 下、截止时间前的 Trace 为来源。

### 6.4 新消息竞态的首期行为

如果提取开始后同一个 Session 收到新消息：

- 当前 Worker 继续处理冻结快照。
- 新消息不会进入当前提取 Prompt。
- 不取消、不重启、不延长当前任务。
- Session 完成后仍标记 `done`。
- 首期不自动为新增消息建立第二次提取任务。

该行为必须写入代码注释和测试，防止后续维护者误以为遗漏是偶发 Bug。后续策略见第 15 节待思考项。

### 6.5 Worker 与失败恢复

- 调度线程只负责分页发现和领取任务，不执行模型调用。
- 提取使用有界线程池，避免抢占正常 Agent 请求。
- `in_progress` 超过配置时间可重置为 `pending`，重试继续使用原 `cutoffMessageId`。
- 模型、Embedding、MySQL 或向量库失败必须记录明确错误，并将状态更新为 `failed`；不得日志后伪装成功。
- 多实例部署依赖 MySQL 行锁/CAS 抢占，同一个 Session 同一时刻只能被一个 Worker 处理。

---

## 7. 提取前规则筛选

### 7.1 用户情景记忆

Session 已满足 3 轮 + 1 小时空窗后，模型可以从会话中提取：

- 已发生的重要任务和结果。
- 用户做出的决定。
- 未完成事项和后续承诺。
- 用户对先前事实的修正。
- 跨会话继续工作需要的关键实体和上下文。

不得提取：

- 普通寒暄。
- 可从当前业务系统实时查询的瞬时状态。
- Token、Cookie、Authorization Header 和凭证。
- 没有跨会话价值的逐句复述。
- 模型自己推断但用户未表达、工具未证明的事实。

### 7.2 用户习惯偏好

允许提取：

- 用户明确表达的格式、沟通、工具使用和工作方式偏好。
- 同一 Session 内重复出现并有清晰证据的稳定习惯。

不得提取：

- 仅发生一次的偶然行为。
- Agent 自己建议的偏好。
- 与用户本人无关的业务数据。
- 未经用户表达的敏感身份推断。

### 7.3 系统操作经验 Trace 规则

评分单位不是一条孤立 Trace，而是一棵 Agent 任务树：

```text
root main Agent Run
  + direct child Agent Runs
  + nested descendant Agent Runs
```

根据 `parentTraceId/parentRunId` 关联根 Run 与全部后代 Run。每个实际 Run 只出现一次；不得把 `spawn_subagent`、`await_subagents` 的 Tool 成功当成子 Agent 的业务成功，也不得把同一子 Run 同时计入父级结果和独立分数两次。

#### 7.3.1 任务级硬筛选

命中任意一项，整棵任务树直接跳过，不进入评分：

- 整棵任务树没有 Tool 调用。
- 主 Agent `finalOutput` 为空。
- 主任务最终业务契约明确失败、请求取消或整体未完成。
- 主 Agent 结束于确认拒绝、确认过期、`max_iterations`、`tool_failure_limit` 或 `LOOP_DETECTED`，且没有形成成功的主任务结果。
- 要求结构化输出但最终 Schema 校验失败。
- 要求产物但最终缺少必需产物或产物类型不匹配。
- 用户已有明确负反馈。
- 只有最终文本声称成功，没有 Tool、业务状态、完成契约或产物证据。

“最后状态”必须取最后一个包含 Tool 调用的 Step。末尾用于生成最终文本的无 Tool `PASS` Step 不能覆盖前一个未解决的 `TOOL_ERROR/INSUFFICIENT`。

子 Agent 失败不属于任务级直接否决：如果主 Agent 已通过其他子 Agent 或其他 Tool 成功回退，失败子 Run 计 0 分并参与最终平均。只有主任务契约因此明确失败时，才由任务级硬筛选跳过整棵树。

#### 7.3.2 学习信号门槛

整棵任务树至少出现一个以下信号，否则直接跳过，即使执行平均分达到 60：

- `TOOL_ERROR` 或选错 Tool 后改变方案并成功。
- `EMPTY`、`LOW_RELEVANCE` 或 `INSUFFICIENT` 后改变检索/工具策略并成功。
- 同一 Tool 修改关键参数后成功。
- 后续 Tool 或子 Agent 明确消费前序结果，形成经过验证的依赖链。

多个互不相关的 Tool、普通聊天、单 Tool 一次直接成功和单纯知识检索都不构成学习信号。简单成功的子 Run 可以获得基础执行分并参与平均，但不能单独让整棵任务树获得提取资格。

#### 7.3.3 单个 Run 的 100 分评分

每个主/子 Agent Run 使用同一公式独立评分。Run 自身取消、循环、确认失败、没有任何有效结果或 `completionValidated=false` 时，该 Run 直接计 0 分；不因此自动否决整棵任务树。

结果可信度，最高 40 分：

| 项目 | 分值 | 判定 |
|---|---:|---|
| Agent Run 正常完成 | +15 | 明确 `reactOutcome=completed` 且最终输出非空 |
| 最终业务结果验证成功 | +15 | Tool 业务状态、子 Agent 完成契约、API 结果、产物或输出契约验证成功 |
| 最后有效 Tool Step 为 `PASS` | +10 | 只检查最后一个包含 Tool 调用的 Step |

旧 Trace 缺少 `reactOutcome` 时，如果最终输出非空、最后有效 Tool Step 成功且没有反向证据，“正常完成”只给 8 分；该 Run 的最终分最高为 79。缺少子 Agent 完成验证且无法从旧结构证明结果时，标记 `LEGACY_UNVERIFIED` 并计 0 分，不由最终文本猜测成功。

学习价值，最高 40 分：

| 项目 | 分值 |
|---|---:|
| `TOOL_ERROR` 或选错 Tool 后恢复 | +20 |
| 空结果、低相关或信息不足后恢复 | +15 |
| 修改关键参数后成功 | +10 |
| 存在经过验证的依赖 Tool/Agent 链 | +10 |
| 多个互不相关 Tool | +0 |

学习价值可以叠加，但封顶 40。一次行为同时符合多个描述时，必须基于不同或确实复合的证据；不能把同一状态换名重复计分。

执行质量，最高 20 分：

| 项目 | 分值 | 判定 |
|---|---:|---|
| 没有无意义的相同调用 | +8 | 没有相同 Tool、相同参数、相同目的且无新结果的重复 |
| 重试不超过 2 次 | +5 | 仅计算失败后的重试，不把独立查询算作重试 |
| ReAct 轮次合理 | +4 | `总轮次 <= Tool 调用数 + 2` |
| 有正向验证 | +3 | 用户正反馈或 Reply Audit 通过，二者不叠加 |

扣分项：

| 项目 | 扣分 |
|---|---:|
| 每个最终未被利用的 `EMPTY/LOW_RELEVANCE` | -5，累计最多 -10 |
| 超过 2 次后每次额外重试 | -5，累计最多 -15 |

`ESCALATING` 不直接扣分。已恢复的普通 `TOOL_ERROR` 不重复扣分，重试成本由执行质量和超量重试反映。已经确认的无进展循环属于 Run 计 0，而不是继续依靠扣分处理。

单 Run 公式：

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

#### 7.3.4 主/子 Agent 平均

通过任务级硬筛选且满足学习信号门槛后，对根 Run 和所有后代 Run 取等权算术平均：

```text
taskScore = roundHalfUp(
    (mainRunScore + sum(descendantRunScores))
    / (1 + descendantRunCount),
    2
)
```

- 不为主 Agent 增加额外权重。
- 不按 Token、耗时、层级或子 Agent 类型加权。
- 已创建并进入执行生命周期的失败/取消子 Run 计 0；创建前即被确定性去除、从未形成 Run 的计划项不进入分母。
- 嵌套子 Agent 展平后各计一次，不能递归平均后再参与父级平均。
- `await_subagents success=true` 只表示等待 Tool 执行成功，不替代任何子 Run 分数。

#### 7.3.5 最终分段

| `taskScore` | 处理 |
|---|---|
| `< 60` | 不提取操作经验 |
| `60 <= score < 80` | 提取普通操作经验 |
| `80 <= score <= 100` | 提取高优先级操作经验 |

只要任务树包含 `LEGACY_UNVERIFIED` Run，整棵任务树最高只能作为普通经验；旧数据回填时应单独统计，不能伪装成高置信新数据。

#### 7.3.6 实现要求

- 权重定义为 `OperationExperienceScorer` 的命名常量并由单元测试固定，首期不增加一组可随意组合的环境变量。
- 评分输入必须是裁剪后的结构化 Run 特征，不用 LLM 判断分数。
- 评分输出包含 `eligible/skippedReason/taskScore/runScores/learningSignals`，但不得把用户正文、Tool 原始输出或凭证写入指标标签。
- 写入操作经验时保存根 `sourceTraceId`、有界子 Trace ID 列表和 `qualityScore`；模型不能修改这些代码生成字段。
- 评分只发生在结构化提取之前。结构化提取后仍只做合法性校验，不进行第二次质量评分。

#### 7.3.7 旧 Trace 兼容

评分前使用确定性的 `TraceScoringFeatureAdapter` 统一新旧结构，不修改原始 Trace：

- ToolResult 缺少 `content` 但存在 `output` 时，使用 `output` 构造只读文本内容，不因此扣分。
- ToolResult 缺少 `status` 时，只能结合 `success=true`、非空结果和对应 Inspection 推断旧式成功；不能推断 `EMPTY/LOW_RELEVANCE/ESCALATING` 等细分状态。
- 缺少 `reactOutcome` 时使用前述 8 分降级规则，Run 总分最高 79。
- 依赖子 Agent 结果但缺少完成契约，且不能由旧结构中的明确产物/业务状态验证时，标记 `LEGACY_UNVERIFIED` 并计 0。
- 未识别的字段版本、损坏 JSON 或无法关联父子的 Run 不进入自动提取，记录有界原因后跳过。

旧数据兼容只发生在特征适配层，不回写 `agent_traces`，也不为了旧数据保留新的双套评分逻辑。

---

## 8. 单次结构化提取

### 8.1 一次调用输出三类结果

对一个 Session 快照只执行一次结构化提取调用。输入包括：

```text
会话 user/assistant 消息
+ 已通过规则筛选的 Trace 摘要
+ 当前可用 Tool 名称与 Schema 摘要
```

Tool 原始大结果必须先裁剪为提取所需字段，不能把完整凭证、长文档或大业务响应直接送入提取模型。

输出 Schema：

```json
{
  "episodicMemories": [
    {
      "content": "用户决定下次继续处理合同审批",
      "eventType": "decision",
      "eventTime": "2026-08-31T10:00:00Z",
      "sourceMessageIds": [101, 105]
    }
  ],
  "preferences": [
    {
      "content": "用户偏好简洁明确的回答",
      "sourceMessageIds": [101]
    }
  ],
  "operationExperiences": [
    {
      "taskType": "create_order",
      "scenario": "用户只提供客户名称时创建订单",
      "triggerSignals": ["CUSTOMER_NOT_FOUND"],
      "strategy": [
        "先调用 customer_search 获得 customerId",
        "再调用 order_create"
      ],
      "antiPatterns": ["不要把客户名称直接作为 customerId"],
      "requiredTools": ["customer_search", "order_create"],
      "sourceTraceId": "trace_root_xxx"
    }
  ]
}
```

数组允许为空。模型不得为了填充三种类型而编造内容。

### 8.2 提取后的校验仅限合法性

结构化提取后不再评分，只检查：

- JSON Schema 合法。
- `sourceMessageIds` 均存在且不超过 `cutoffMessageId`。
- `sourceTraceId` 是当前 Session 中已通过任务级筛选且 `taskScore >= 60` 的根 Trace。
- `requiredTools` 全部真实出现在对应主/子 Agent 任务树。
- 策略步骤不能包含对应任务树中不存在的 Tool。
- Worker 校验模型返回的根 `sourceTraceId`，据此查找对应任务树；`qualityScore` 和有界子 Trace ID 列表完全由代码生成并写入，不能接受模型提供的分数。
- 内容不含凭证、原始 Token、Cookie 和 Authorization Header。
- 系统操作经验已去除用户实例值，可以跨同作用域复用。
- 单条内容和总输出不超过配置的提取上限。

校验失败时整次提取失败并重试，不接受半份结果。

### 8.3 写入顺序与幂等

1. 根据 `extractorVersion + sessionId + type + itemIndex` 生成向量记录 ID。
2. 批量生成 Embedding。
3. 将情景记忆和操作经验批量 Upsert 到当前向量库；任意一条失败则整批报告失败并执行已知 ID 补偿删除。
4. MySQL 事务中追加偏好，并将 Session 更新为 `done`。
5. MySQL 事务失败时删除本次确定性向量 ID；补偿失败必须记录为可重试错误，不能返回成功。

不做语义去重，不合并 `sourceTraceIds`，不维护操作经验成功/失败次数。

---

## 9. 长期记忆召回

### 9.1 请求准备顺序

当前 `AgentRunPreparer` 在创建 `RunToolCatalog` 前构建 Prompt。TODO12 调整为两阶段：

```text
认证与输入解析
  → 解析 Session / 短期历史
  → GapAnalysis / unavailableTools
  → 创建本次不可变 RunToolCatalog
  → 构造有界 MemoryRetrievalQuery
  → SQL 偏好、用户情景、操作经验三路并行召回
  → 3% Token 打包
  → 构建 ReActRequest
```

这样系统操作经验可以根据本次真实 Tool Catalog 过滤，避免注入当前请求无权使用或未注册的 Tool。

`MemoryRetrievalQueryBuilder` 只使用当前请求已经可信可见的信息：

```text
当前用户消息
+ 当前 Session 已有的有界压缩摘要/最近任务目标
+ GapAnalysis 已识别的意图、错误或缺失参数
```

- 查询文本使用 `TextTokenEstimator` 限制在 512 tokens 内，按完整字段从后向前舍弃，不做字符串硬截断。
- 当前消息是“继续、还是这个、换一个”等依赖上下文的短句时，必须包含当前 Session 摘要或最近任务目标；新 Session 没有上下文时只使用当前消息。
- 不把全部 RunToolCatalog 名称拼进 BM25 查询，避免公共 Tool 名称污染关键词排序。Tool Catalog 只用于结构化可用性过滤。
- 不把 `userId/tenantId`、时间戳、Memory ID、评分或凭证放入向量正文；它们只作为可信结构化字段。

### 9.2 三路召回与故障语义

三个后端任务并行执行：

1. MySQL：用户习惯偏好。
2. 用户情景记忆 Collection：Dense + BM25。
3. 系统操作经验 Collection：Dense + BM25。

两类向量记忆只在各自 Collection 内融合，绝不把情景记忆和操作经验放进同一个 TopK。任一路存储或 Embedding 失败都必须抛出带类型和 Provider 的明确错误，不能把异常伪装成“没有相关记忆”，也不静默降级为文件、MySQL LIKE、单路 BM25 或单路 Dense。

### 9.3 向量记忆的统一混检算法

Milvus 和 pgvector 必须实现相同的检索语义：

1. 在 Dense 和 Sparse 两路查询中同时应用各自的身份/租户硬过滤。
2. 每路最多取 `laneTopK=20`。
3. Dense 候选先应用 COSINE 阈值，默认 `>= 0.70`。
4. Sparse 候选先应用 BM25 阈值，默认 `>= 0.10`。
5. 对通过各路阈值的候选执行 RRF，`k=60`。
6. 融合后最多保留 `fusedTopK=20`。

RRF 使用一基排名：

```text
rrfScore(memory) =
    sum(1 / (60 + rankInLane))
```

同一个 Memory ID 在 Dense/BM25 两路命中时只保留一条记录并累加两个排名贡献；只命中一路也可以进入候选。ID 去重是检索结果合并，不是写入时语义去重。

Milvus 使用原生 Hybrid Search + `RRFRanker(60)`，Dense/BM25 准入条件必须配置在各自 `AnnSearchReq` 上并在融合前生效。pgvector 分别执行有界 Dense/全文查询，然后在应用层按相同公式进行 RRF；不得继续把余弦分和 `ts_rank` 原始值按权重直接相加。

`HARNESS_RAG_SCORE_THRESHOLD=0.7` 是知识库 Dense 候选阈值，不能应用到 RRF 融合分。RRF 分只用于候选之间的相对排序，不能再与 0.7 比较，也不能和时间、`qualityScore` 相加形成新的不可解释总分。

默认参数独立于知识库配置：

```properties
HARNESS_MEMORY_RETRIEVAL_LANE_TOP_K=20
HARNESS_MEMORY_RETRIEVAL_FUSED_TOP_K=20
HARNESS_MEMORY_RETRIEVAL_DENSE_THRESHOLD=0.70
HARNESS_MEMORY_RETRIEVAL_SPARSE_THRESHOLD=0.10
HARNESS_MEMORY_RETRIEVAL_RRF_K=60
HARNESS_MEMORY_RETRIEVAL_NEAR_BEST_RATIO=0.90
HARNESS_MEMORY_RETRIEVAL_QUERY_MAX_TOKENS=512
```

这些默认值必须通过真实记忆集评测后再调整；调整时记录版本并同时验证 Milvus/pgvector，不允许两个 Provider 各自形成不同的召回含义。

### 9.4 用户偏好召回

SQL 条件使用严格用户作用域，按：

```text
userId = authenticatedUserId
AND tenantId null-safe exact match
ORDER BY observedAt DESC, createdAt DESC, id DESC
```

按时间倒序读取有界偏好记录，不做分类聚合，也不按分类只保留一条。候选按完整 Preference Block 交给 3% Token Allocator；预算不足时较旧记录整条舍弃。动态上下文保留 `observedAt`，并声明发生明确冲突时以较新的用户表达为准。偏好不生成 Embedding，也不参与 RRF。

### 9.5 用户情景记忆召回

情景查询使用完整的 `MemoryRetrievalQuery`。Dense 和 BM25 两路都由服务端添加相同的身份过滤：

```text
userId = authenticatedUserId
AND tenantId null-safe exact match
```

融合后按以下确定性顺序处理：

1. 取最高 `rrfScore` 为 `bestScore`。
2. `rrfScore >= bestScore * 0.90` 的候选进入最高相关区间。
3. 最高相关区间按 `eventTime DESC, createdAt DESC, id DESC` 排序，让近期情景优先。
4. 其余已通过单路阈值的候选继续按 `rrfScore DESC, eventTime DESC, id DESC` 排序。
5. 按完整 Memory Block 顺序交给 3% Token Allocator，允许选入多条；预算不足时整条舍弃。

不执行写入式或查询式语义去重；相似情景允许同时存在。时间只重排最高相关区间，不能让低相关的新情景越过明显更相关的旧情景。

### 9.6 系统操作经验召回

操作经验查询使用：

```text
当前任务文本
+ 当前阶段可识别的错误/缺失参数
+ 当前 Session 有界任务目标
```

召回顺序：

1. Dense/BM25 两路都使用 `tenantId IS NULL OR tenantId = currentTenantId`；非多租户请求只允许 `tenantId IS NULL`。
2. 两路都过滤 `qualityScore >= 60`。
3. 在系统操作经验 Collection 内执行统一 RRF 混检并取得最多 20 条融合候选。
4. 召回后要求 `requiredTools` 是当前不可变 RunToolCatalog 的子集；不满足的候选删除，不能让模型看到不可用 Tool。
5. 在剩余候选中重新确定 `bestScore`，只保留 `rrfScore >= bestScore * 0.90` 的最高相关区间。
6. 从该区间按 `createdAt DESC, eventTime DESC, id DESC` 选择最新一条。

系统操作经验每次最多注入一条。`qualityScore` 只负责 `>=60` 的准入和观测，80 分高优先级不获得额外检索加分；时间是高相关结果之间的决胜条件，不能让不相关的新经验覆盖相关旧经验。

如果融合候选全部因 Tool 不可用被删除，本次操作经验结果为空，不扩大 Tenant、降低质量门槛或绕过 RunToolCatalog 重新搜索。

### 9.7 不新增自动文件经验

系统操作经验不写入 Markdown/YAML 文件。人工维护并随代码发布的标准流程继续使用现有 Skill；自动学习经验只进入向量库。两者职责不同，不建立自动导出链路。

---

## 10. 3% Token 预算与 Prompt 布局

### 10.1 预算

```java
longTermMemoryBudget = floor(chatModelContextWindowTokens * 0.03);
```

示例：

```text
1,000,000 tokens → 30,000 tokens
128,000 tokens   → 3,840 tokens
32,000 tokens    → 960 tokens
```

要求：

- 使用注入的 `TextTokenEstimator`，不得继续使用字符数乘除估算。
- 偏好、情景和操作经验共享一个预算对象。
- 不设置固定类型百分比。
- 没有相关内容时允许使用 0 token。
- 打包过程中任何单条记忆都不能被字符串硬截断到无效 JSON/语义；应按完整 Memory Block 取舍。

新增配置：

```properties
HARNESS_MEMORY_LONGTERM_BUDGET_RATIO=0.03
```

首期固定默认值和推荐值均为 `0.03`；配置解析仍需限制合法范围，非法值启动失败。

### 10.2 动态打包

`LongTermMemoryBudgetAllocator` 接收：

```text
按观察时间倒序的原子偏好候选
用户情景候选
最多一条系统操作经验
总 Token Budget
```

根据完整块逐条加入，不预留固定子比例。偏好通常较短，操作经验只有一条，其余预算自然由情景记忆使用。

### 10.3 Prompt Cache 友好布局

删除 `AgentPromptBuilder.appendLongtermMemory()`。动态长期记忆不得继续进入基础 System Prompt。

ReAct 消息顺序调整为：

```text
稳定 System Prompt
+ 稳定 Tool Specifications
+ Session historyMessages
+ 本轮 dynamicMemoryContext
+ currentUserMessage
```

`dynamicMemoryContext`：

- 不保存回 Session 消息表。
- 不改变用户原始消息内容。
- 使用明确的 provider-neutral Memory Context Envelope。
- 标记为历史参考信息，不能覆盖 System Prompt、Tool Catalog、用户权限和业务系统实时结果。
- 在 Trace 中只记录选中的 Memory ID、类型、Token 数和检索耗时，不记录完整敏感正文。

该布局保持 System 与历史前缀尽可能稳定，动态召回只出现在当前请求尾部。

---

## 11. 分层与对象设计

### 11.1 `harness-core`

新增 provider-neutral 模型和契约：

```text
UserEpisodicMemory
UserEpisodicMemoryCandidate
OperationExperience
OperationExperienceCandidate
LongTermMemoryContext
MemoryScope
MemoryExtractionResult
MemoryExtractionStatus
```

`AgentContext` 增加可空租户读取方法，但不破坏知识图谱现有默认租户语义。

### 11.2 `harness-input`

保留短期记忆 Store、Cache 和 Compressor。重构：

```text
SessionStore
  ├─ findExtractionCandidates(cursor, limit, cutoff, minTurns)
  ├─ claimForMemoryExtraction(...)
  ├─ completeMemoryExtraction(...)
  └─ failMemoryExtraction(...)

UserPreferenceMemoryStore
  ├─ appendBatch(...)
  └─ loadLatestPage(...)
```

删除：

```text
PreferenceRefinementWorker
旧 PreferenceStore.upsert 语义
旧 refinement quality score
旧请求触发式 refinement 提交
```

### 11.3 `harness-tool`

新增两个语义独立的向量接口，不复用知识 Chunk DTO，也不通过一个带 `memoryType` 的通用 Store 混合两类记忆：

```java
public interface UserEpisodicMemoryVectorStore {
    void upsertBatch(List<UserEpisodicMemory> memories);
    List<UserEpisodicMemoryCandidate> searchHybrid(EpisodicMemorySearchRequest request);
    void deleteByIds(List<String> ids);
    int deleteBySourceSession(String sessionId);
    int deleteByOwner(String userId, String tenantId);
}

public interface OperationExperienceVectorStore {
    void upsertBatch(List<OperationExperience> experiences);
    List<OperationExperienceCandidate> searchHybrid(OperationExperienceSearchRequest request);
    void deleteByIds(List<String> ids);
    int deleteBySourceSession(String sessionId);
    int deleteByTenant(String tenantId);
}
```

实现：

```text
MilvusUserEpisodicMemoryVectorStore
MilvusOperationExperienceVectorStore
PgUserEpisodicMemoryVectorStore
PgOperationExperienceVectorStore
NoOpUserEpisodicMemoryVectorStore
NoOpOperationExperienceVectorStore
MemoryVectorStoreFactory
UserEpisodicMemoryMilvusInitializer
OperationExperienceMilvusInitializer
```

Milvus/pgvector 查询异常必须抛出明确异常，不能返回空集合伪装成无记忆。

删除 `UpdateMemoryTool` 及其 ThreadLocal 上下文，因为模型不再直接写长期记忆。

### 11.4 `harness-trace`

- 增加按 Session 稳定游标读取 Trace。
- Trace 保存失败必须向上抛出，不能吞异常后仍触发记忆提取。
- 保留 Trace 作为操作经验的原始证据；向量记录可由 Trace 重建。

### 11.5 `harness-agent`

新增编排服务：

```text
MemoryExtractionScheduler
SessionMemoryExtractionWorker
MemoryExtractionRuleFilter
TraceScoringFeatureAdapter
OperationExperienceScorer
StructuredMemoryExtractor
MemoryExtractionValidator
MemoryRetrievalQueryBuilder
LongTermMemoryRetriever
LongTermMemoryBudgetAllocator
MemoryContextFormatter
```

所有依赖通过构造注入；只在 Orchestrator/Factory 生命周期边界创建实例。

`AgentMemoryRuntime` 缩减为统一门面：

```text
短期 Session 生命周期
短期加载与压缩
长期召回
消息持久化
提取调度生命周期
```

### 11.6 `harness-react`

扩展 `ReActRequest` 支持独立动态上下文消息，并保证顺序为 History 之后、当前 User Message 之前。阻塞、流式、确认恢复和最终回答路径必须保持相同顺序。

### 11.7 `harness-server`

- 启动时在当前选中的向量数据库内分别初始化用户情景记忆和系统操作经验后端。
- 关闭时优雅停止 Scheduler 和 Worker。
- 首期不增加长期记忆管理 UI。
- 如果后续增加列表/删除 API，必须使用认证范围、稳定游标 `PageResponse`、事务和明确 `ApiError`。

---

## 12. 配置清理

删除或停止使用：

```text
HARNESS_MEMORY_MIN_MESSAGES
HARNESS_MEMORY_MIN_USER_CHARS
HARNESS_MEMORY_LONGTERM_MAX_TOKENS
HARNESS_MEMORY_REFINEMENT_MIN_SCORE
HARNESS_MEMORY_REFINEMENT_STUCK_MINUTES
```

`HARNESS_MEMORY_CLEANUP_INTERVAL_MINUTES` 若仍承担 Session Cache/Skill Cache 清理，应重命名或限定为缓存维护，不再控制长期记忆提取。

新增：

```text
HARNESS_MEMORY_STORE
HARNESS_MEMORY_EPISODIC_COLLECTION
HARNESS_MEMORY_EXPERIENCE_COLLECTION
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
```

`HARNESS_MEMORY_STORE=mysql|none` 与 `HARNESS_AUDIT_STORE` 解耦。长期记忆不能因为关闭 Trace 审计而隐式关闭 MySQL Session/Preference Store；操作经验提取依赖 Trace 时，应明确显示该部分不可用。

不得修改根 `revision` 或发布说明，除非另有明确指令。

---

## 13. 迁移与死代码删除

### 13.1 数据迁移

1. 执行 MySQL Schema 迁移，新增 Session 提取字段和 `user_preference_memories`。
2. 旧 `user_preferences` 是身份、偏好、项目和目标混合的聚合文本，不自动迁入原子偏好表，也不通过模型批量拆分后伪造来源。
3. 新运行时只读新表，不做旧表双读或 fallback；新表从新提取的原子偏好开始积累。
4. 旧表在迁移验收期只作离线备份，确认新链路后再通过明确迁移脚本移除；不得在启动代码中自动 DROP。
5. 在当前 Milvus Database `cyrene_test` 分别创建 `cyrene_user_episodic_memories` 和 `cyrene_operation_experiences`。
6. 旧聚合偏好不自动生成偏好、情景记忆或操作经验，避免从缺少来源的自由文本反推事实。

### 13.2 删除代码

实施完成后删除：

- `PreferenceRefinementWorker` 及测试。
- `UpdateMemoryTool`、注册逻辑、ThreadLocal 设置/清理和测试。
- `AgentPromptBuilder.appendLongtermMemory()`。
- 旧 `PreferenceStore` / `MysqlPreferenceStore` / `NoOpPreferenceStore` 覆盖式实现，被新 Store 替代后不留兼容壳。
- `SessionLifecycleManager` 中旧提炼分数、字符数和请求触发逻辑。
- `AgentMemoryRuntime.scheduleRefinement()` 等重复入口。
- 失效 EnvKey、`.env.example` 示例和注释。

短期 Memory Compressor、Session Cache、Message Store 和 Tool Message Codec 不删除。

---

## 14. 实施顺序

### Phase 1：测试基线与领域模型

- [ ] 固定当前短期 Session、压缩和 Cache 回归测试。
- [ ] 为三类长期记忆增加 provider-neutral DTO。
- [ ] 增加 `optionalTenantId`，保持图谱默认租户兼容。
- [ ] 固定 3% Budget 计算和完整块打包测试。

### Phase 2：MySQL Schema 与提取状态

- [ ] 新增 Session 可空租户与提取状态字段。
- [ ] 新增 `user_preference_memories` 追加表。
- [ ] 实现 3 轮 + 1 小时空窗的稳定游标候选查询。
- [ ] 实现事务领取、冻结 `cutoffMessageId`、完成和失败状态。
- [ ] 修复 Session 所有权校验。
- [ ] 实现按 Session 分页读取 Trace。

### Phase 3：长期记忆向量后端

- [ ] 新增语义独立的 `UserEpisodicMemoryVectorStore` 和 `OperationExperienceVectorStore`。
- [ ] 在当前 Milvus Database `cyrene_test` 初始化两个独立 Collection。
- [ ] 实现 Milvus 两路融合前阈值、原生 `RRFRanker(60)` 和可空租户过滤，不复用知识库融合后 0.7 阈值。
- [ ] 实现 pgvector 两张等价表、两路有界检索和应用层同公式 RRF，不再直接加权余弦分与 `ts_rank`。
- [ ] 实现批量 Upsert、按 ID/Session/User 删除和明确异常。

### Phase 4：每日提取

- [ ] 实现固定时区每日 Scheduler。
- [ ] 实现有界异步 Worker 和 stuck recovery。
- [ ] 实现 Session 规则、Agent 任务树组装、任务级硬筛选和主/子 Run 等权评分。
- [ ] 一次模型调用输出三类结构化结果。
- [ ] 实现来源、Tool、脱敏和 Schema 校验，不增加二次评分。
- [ ] 实现确定性 ID、批量写入、MySQL 事务和补偿删除。

### Phase 5：召回与 Prompt 布局

- [ ] 两阶段准备 RunToolCatalog 与长期记忆。
- [ ] 构造最多 512 tokens 的 `MemoryRetrievalQuery`，上下文短句携带当前 Session 有界任务目标，不拼接完整 Tool Catalog。
- [ ] 并行加载 SQL 偏好、用户情景和系统操作经验。
- [ ] 在 Dense/Sparse 两路实现用户/租户硬过滤、操作经验 `qualityScore >= 60` 和召回后 `requiredTools` 子集校验。
- [ ] 情景记忆支持多条召回，操作经验只选择一条。
- [ ] 实现 `bestScore * 0.90` 最高相关区间内的最新优先，不把时间或质量分加进 RRF。
- [ ] 把动态长期记忆移出 System Prompt，放在 History 与当前消息之间。
- [ ] Trace 记录类型、ID、Token、耗时和命中，不记录完整敏感正文。

### Phase 6：迁移与清理

- [ ] 离线备份旧聚合偏好但不迁入新原子表，新运行时不保留双读。
- [ ] 删除旧提炼 Worker、评分逻辑和 `update_memory` Tool。
- [ ] 清理旧配置、注释、未使用导入和死代码。
- [ ] 更新 `.env.example`，不写真实凭证。
- [ ] 执行范围测试、全量测试和静态检查。

---

## 15. 明确待思考项

以下问题本轮不自动决定，先按已确认的简单行为实现：

### 15.1 已提取 Session 后出现新消息

首期行为：新消息不进入已冻结任务，Session 提取完成后仍为 `done`，不会再次提取。

后续需要单独决定：

- 是否按新的 `cutoffMessageId` 建立增量提取批次。
- 是否把 Session 重新置为 `none/pending`。
- 是否按消息区间维护多次提取版本。
- 如何撤销或覆盖前一次提取出的偏好、情景和操作经验。

在该决策完成前，不加入“检测到新消息就自动重跑”的隐式逻辑。

### 15.2 相似记忆的长期膨胀

首期允许相似记录并存，不做写入时语义去重。如果真实数据证明重复结果挤占 TopK，再单独评估：

- 离线压缩/归档。
- 查询时结果多样化。
- 基于时间窗口的清理。

不得提前为此增加操作经验 SQL 主表和复杂候选晋升状态机。

### 15.3 用户反馈对已提取经验的影响

首期只在提取前读取已有负反馈并跳过对应 Trace。提取完成后的迟到反馈是否删除、降级或重建向量记录，后续根据反馈入口和审计需求单独设计。

---

## 16. 测试与验证

### 16.1 单元测试

- 2 轮交互不满足提取条件，3 轮刚好满足。
- 空窗 59 分钟不满足，60 分钟满足。
- Tool/Summary 不计入完整对话轮次。
- 领取任务时正确冻结 `cutoffMessageId`。
- 领取后新增消息不进入当前提取。
- 正常多 Tool 链、失败恢复和参数修正通过 Trace 规则。
- 整棵任务树只有单 Tool 直接成功且没有学习信号时跳过；循环、取消、确认拒绝和纯失败按对应层级计 0 或整树跳过。
- 主 Agent 与所有后代 Run 各评分一次，最终结果使用等权算术平均。
- 子 Agent `completionValidated=false` 时该 Run 计 0，但主任务成功回退时不会直接否决整棵任务树。
- 主任务契约失败、主输出为空或结构化最终输出无效时整棵任务树直接跳过，不能由成功子 Agent 抬高平均分。
- 简单成功子 Run 可以获得基础执行分，但整棵任务树没有学习信号时仍不提取。
- 嵌套子 Agent 展平计数，失败 Run 进入分母，未实际创建的计划项不进入分母。
- 旧 Run 缺少 `reactOutcome` 时完成分降级且最高 79；`LEGACY_UNVERIFIED` 不得生成高优先级经验。
- 旧 ToolResult 缺少 `content` 时兼容读取 `output`；缺少 `status` 时不得编造细分状态。
- 学习价值和最终总分正确封顶，`60/80` 边界行为固定。
- 结构化结果只做合法性校验，不存在二次质量评分。
- 1M 上下文得到 30K 长期记忆预算。
- Memory Block 不被截断成半条记录。
- 同一 Session 提取出的多个独立偏好分别追加，不生成分类字段，也不重新合并为一条文本。
- 偏好按 `(observedAt, createdAt, id)` 倒序稳定分页；预算不足只舍弃完整旧记录。
- 可空租户查询不会跨租户或把 `NULL` 错当任意租户。
- 操作经验 `requiredTools` 不属于 RunToolCatalog 时不注入。
- 相近候选中最新操作经验胜出，低相关新经验不能覆盖高相关旧经验。

### 16.2 MySQL 集成测试

- 候选 Session 查询使用稳定 `(last_active, id)` 游标和 `limit + 1`。
- 两个 Worker 并发领取同一 Session 只有一个成功。
- 偏好批量追加和 Session `done` 在同一事务提交/回滚。
- 偏好表不存在分类列或分类唯一约束，同一用户可追加多个独立偏好事实。
- `tenant_id IS NULL` 和具体租户的用户偏好严格隔离。
- Trace 按 `(session_id, timestamp, trace_id)` 稳定分页。
- requested Session 必须匹配认证用户和可空租户。

### 16.3 Milvus 集成测试

- 两个 Collection 均创建在配置的 `cyrene_test` Database，不在 `default`。
- 用户情景 Collection 和系统操作经验 Collection 各自支持 Dense、BM25 和 Hybrid Search。
- 用户情景检索的 Dense/Sparse 两路都应用用户和可空租户过滤。
- 操作经验检索的 Dense/Sparse 两路都应用全局/租户过滤。
- Dense/BM25 阈值在 RRF 前分别生效，融合结果不再经过知识库 `0.7` 阈值。
- 原生 `RRFRanker(60)` 的候选顺序符合一基排名公式，只命中一路和同时命中两路都能正确计算。
- 两路都低于准入阈值时返回真正的空候选，不用“最新记录”填充。
- 操作经验 `qualityScore < 60` 不进入两路检索，`requiredTools` 不可用的融合候选在选择前删除。
- 最高相关区间按 `bestScore * 0.90` 产生；区间内最新候选胜出，区间外的新候选不能越级。
- 用户情景的租户、用户、时间和来源 Scalar Index 全部存在；操作经验的租户、质量、任务类型、时间和来源 Scalar Index 全部存在。
- `required_tools/metadata` 没有创建未参与查询的无效 Milvus 索引。
- 用户情景 Collection 不可写缺失 `user_id` 的记录。
- 系统操作经验 Collection 不包含 `user_id` 字段。
- 相同确定性 ID 重试执行 Upsert，不新增重复主键。
- 批量失败可按已知 ID 补偿删除。
- 删除某用户时不会删除其他用户或全局操作经验。

### 16.4 pgvector 集成测试

- 只在 Provider 选择 pgvector 时初始化表。
- Dense、全文和应用层 RRF 结果与 Milvus 的作用域、阈值、排名和 TopK 语义一致。
- 不直接相加 COSINE 与 `ts_rank` 原始值，不复用 `HARNESS_RAG_BM25_WEIGHT` 形成另一套记忆排序。
- 可空租户、用户、类型和时间索引参与查询。
- `quality_score >= 60`、最新时间排序和按 Session/Trace 重建命中对应 B-Tree；未下推 `required_tools` 时不创建无效 GIN。
- 所有增长型管理查询使用稳定游标。

### 16.5 ReAct 与 Prompt Cache 回归

- System Prompt 不再包含动态 `[User Memory]`。
- 消息顺序固定为 System、History、Dynamic Memory、Current User。
- 动态 Memory 不被保存为真实用户消息。
- 阻塞、流式、确认恢复和最终回答阶段顺序一致。
- Prompt prefix fingerprint 与 Provider cached input tokens 可继续观测。
- 长期记忆变化不会改变 System Prompt 和 Tool Schema 前缀。

### 16.6 验证命令

```powershell
mvn test -pl harness-core,harness-input,harness-tool,harness-trace,harness-react,harness-agent,harness-server -am -DskipITs
mvn clean test -DskipITs
git diff --check
node --check harness-server/src/main/resources/public/js/app.js
node --check harness-server/src/main/resources/public/js/api.js
node --check harness-server/src/main/resources/public/js/i18n.js
```

Milvus、pgvector 和 MySQL 集成测试必须在对应 Provider/容器可用时单独运行，不能把未连接数据库当作通过。

---

## 17. 可观测性与验收

### 17.1 指标

提取指标：

```text
memoryExtractionSessions{outcome=eligible|skipped|success|failed}
memoryExtractionSkipped{reason}
memoryExtractionDurationMs
memoryExtractionModelTokens
memoryExtractionRecords{type}
memoryExtractionQueueDepth
memoryExtractionStuckTotal
operationExperienceRunScore
operationExperienceTaskScore
operationExperienceTaskTrees{outcome=skipped|ordinary|high}
```

召回指标：

```text
memoryRetrievalLatencyMs{type,provider}
memoryRetrievalCandidates{type}
memoryRetrievalLaneCandidates{type,lane=dense|sparse}
memoryRetrievalFiltered{type,reason=threshold|scope|tools|quality|budget}
memoryRetrievalRrfScore{type}
memoryRetrievalSelected{type}
memoryRetrievalTokens{type}
memoryLongTermBudgetTokens
memoryLongTermUsedTokens
```

指标标签不得包含 `userId`、`tenantId`、Session ID 或 Memory ID，避免敏感信息和无界基数。单次 Trace 可记录有界 Memory ID 列表用于诊断。

### 17.2 最终验收清单

- [ ] 短期 Session、Cache 和压缩行为保持兼容。
- [ ] 旧单文本长期记忆和 `update_memory` Tool 已完全移除。
- [ ] Session 至少 3 轮且空窗 1 小时后，才会在每日任务中进入提取。
- [ ] 提取领取后以 `cutoffMessageId` 冻结，新消息不影响当前任务。
- [ ] 一次结构化调用可输出情景、偏好和操作经验，之后没有二次质量评分。
- [ ] 操作经验在提取前按主/子 Agent 任务树评分；所有实际 Run 等权平均，失败子 Run 计 0。
- [ ] 操作经验只在任务树存在学习信号且平均分至少 60 时写入，80 分起标记为高优先级。
- [ ] 用户情景和操作经验进入当前选中的向量库。
- [ ] 当前 Milvus 的用户情景和系统操作经验 Collection 均位于 `cyrene_test` Database，且物理分离。
- [ ] 偏好以无分类的 MySQL 原子记录追加保存，召回按观察时间倒序且不覆盖历史。
- [ ] 所有新增 `tenantId` 字段允许 `NULL`，非多租户系统可正常运行。
- [ ] 用户记忆严格按认证用户和可空租户隔离。
- [ ] 操作经验只使用全局或当前租户记录，并通过 RunToolCatalog 校验。
- [ ] 情景与操作经验在独立 Collection 内分别执行 Dense/BM25 前置阈值和 RRF，不复用知识库融合后分数阈值。
- [ ] RRF 只表达相关性排序；时间仅在最高相关区间内决胜，`qualityScore` 只做操作经验准入。
- [ ] 相似向量记忆允许并存，时间只在高相关候选中决定优先级。
- [ ] 操作经验每次最多注入一条。
- [ ] 三类长期记忆共享模型上下文 3% 的动态预算。
- [ ] 动态记忆位于稳定历史之后，不再污染 System Prompt 前缀。
- [ ] 所有增长型 SQL/管理查询使用稳定游标、明确排序和 `limit + 1`。
- [ ] MySQL 相关写入使用事务，跨 MySQL/Milvus 写入使用确定性 ID 和明确补偿。
- [ ] 错误被明确抛出并记录，不用空结果或默认对象掩盖存储失败。

---

## 18. 非目标

- 不把短期记忆改成固定五条窗口。
- 不把短期消息、用户情景或自动操作经验改成文件主存储。
- 不为一个主 Agent 增加 `agentId`。
- 不增加假设中的 `applicationId`。
- 不建立操作经验 MySQL 元数据表、成功次数、失败次数和候选晋升系统。
- 不进行写入时向量语义去重。
- 不在首期解决已提取 Session 后新增消息的增量再提取。
- 不让模型传入或扩大 `userId/tenantId`。
- 不让历史记忆覆盖业务系统实时数据、认证权限或 Tool Catalog。
- 不因为 Milvus 不可用而静默回退到文件、MySQL LIKE 或其他 Provider。
