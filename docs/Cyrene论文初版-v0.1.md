# Cyrene 论文研究协议 v0.1

> 文档性质：内部研究与实验规划文档，不直接用于投稿<br>
> 编制日期：2026-08-18<br>
> 项目仓库：[chenkezhen480/Cyrene](https://github.com/chenkezhen480/Cyrene)<br>
> 代码基线：`main`，提交 `496bbccced87f9c86155a17bd9f5b20ab1a6d9ef`<br>
> 当前建议题目：**Cyrene: Identity-Governed Knowledge, Memory, and Continual Adaptation for Agents over Existing Enterprise Systems**

---

## 1. 文档目标

本协议用于把 Cyrene 从工程项目转化为可验证、可复现、可投稿的研究成果，主要解决以下问题：

1. 明确论文究竟提出了什么问题，而不是罗列已有功能。
2. 明确四项核心贡献分别需要什么机制与实验支撑。
3. 区分当前已经实现、部分实现和尚未实现的内容。
4. 提前锁定研究问题、基线、指标和数据集划分，避免完成开发后无法设计有效实验。
5. 明确用户记忆、企业知识和模型参数学习之间的边界。
6. 建立从 Agent Trace 到 LoRA 训练、评测、发布与回滚的数据闭环。

本协议不是会议论文，也不建议上传至 arXiv。待方法、实验和正文完成后，再将其内容转化为正式论文。

---

## 2. 一句话研究主张

> Cyrene 面向已有企业系统，通过双通道企业知识接地、宿主身份驱动的能力投影、作用域隔离的持久化用户记忆，以及经验证 Trace 驱动的持续参数适配，为企业 Agent 提供有知识、有边界、有记忆、能演化的完整运行机制。

该主张包含四层含义：

| 层次 | 目标 | 核心问题 |
|---|---|---|
| 知识层 | 有知识 | 如何联合利用专业文档与关系型业务数据？ |
| 治理层 | 有边界 | 如何继承宿主系统身份、租户和权限边界？ |
| 记忆层 | 有记忆 | 如何在不修改模型权重的情况下保持用户级连续性？ |
| 演化层 | 能演化 | 如何从经过验证的业务轨迹中持续提升 Agent 操作能力？ |

---

## 3. 研究对象与概念边界

### 3.1 Cyrene 的研究对象

Cyrene 不是面向个人开发者的通用编码 Agent，也不是重新建设一套孤立的企业 AI 应用。其研究对象是：

> 已经拥有 API、身份体系、业务数据和内部知识的既有系统，如何低成本获得可治理的专用 Agent。

### 3.2 宿主租户与 Cyrene 租户

本文中的 `tenantId` 表示宿主系统提供的外部租户作用域，不表示 Cyrene 自身经营多租户 SaaS。

宿主系统负责：

- 租户与用户生命周期；
- 身份认证；
- 角色和权限定义；
- 业务数据真相；
- 最终接口鉴权。

Cyrene 负责：

- 接收经过可信验证的身份与租户上下文；
- 将外部作用域传播至工具、知识、记忆与 Trace；
- 在模型调用前缩小能力范围；
- 在工具执行前再次检查作用域和参数；
- 透传原用户凭证，由宿主系统完成最终授权判断。

因此，论文应使用以下表述：

- host-system tenant scope；
- tenant-aware integration；
- tenant context propagation；
- delegated authorization；
- identity-governed capability projection。

不应声称“Cyrene 提供完整多租户管理”。

### 3.3 用户长期记忆与模型学习

用户长期记忆存储在数据库、向量库或图结构中，不修改基础模型参数，属于外部非参数记忆。

Trace-to-LoRA 闭环会更新 Adapter 参数，属于 Agent 层的参数化持续适配。

二者边界如下：

| 对比项 | 用户持久化记忆 | Trace-to-LoRA 持续适配 |
|---|---|---|
| 作用对象 | 特定用户 | Agent 整体、某一领域或某一接入系统 |
| 存储位置 | MySQL、向量库、图谱等 | LoRA Adapter 参数 |
| 主要内容 | 偏好、历史事件、用户纠正 | 通用工具选择、流程和异常恢复能力 |
| 是否更新模型参数 | 否 | 是 |
| 更新频率 | 会话级或事件级 | 周期性批量训练 |
| 是否允许保存动态业务事实 | 经授权后可以 | 原则上不允许 |
| 删除难度 | 可直接删除或失效 | 需要废弃、重训或替换 Adapter |

### 3.4 不进入模型权重的内容

以下内容必须保留在业务系统、知识库、知识图谱或外部记忆中：

- 客户姓名、联系方式和合同明细；
- 实时订单、库存、价格和审批状态；
- 用户 Token、密码和密钥；
- 租户权限和用户角色；
- 经常变化的制度；
- 未经验证的 Agent 输出；
- 未脱敏的原始客户对话。

LoRA 应学习稳定、可泛化的操作行为，而不是记忆具体企业事实。

---

## 4. 当前项目进度审计

### 4.1 基线说明

本协议基于本地 `main` 分支提交 `496bbcc` 进行静态检查，没有修改 Cyrene 仓库代码，也没有在本次检查中重新执行 Maven 测试。

仓库当前静态统计包含 69 个 `*Test.java` 文件与 5 个 `*IT.java` 文件。该数字仅表示测试文件规模，不代表本协议已验证全部测试通过。

### 4.2 当前能力与论文缺口

| 研究方向 | 已有工程基础 | 代码证据 | 当前判断 | 投稿前主要缺口 |
|---|---|---|---|---|
| 既有系统接入 | OpenAPI/Swagger 优先解析；缺少规范时进行受限代码扫描；生成并热加载 `project-apis.json` | `ProjectDiscoveryService`、`OpenApiSpecParser`、`ToolRegistry` | 已实现基础 | 需要把接入速度与正确率纳入实验，而不是只做产品展示 |
| 知识库 | 文档切块、Embedding、混合检索、可选 Rerank、检索升级 | `KnowledgeBaseTool`、`KnowledgeIngestService`、`SemanticContextRetriever`、`RetrievalEscalationPolicy` | 已实现基础 | 缺少与图谱统一的查询分解、证据融合和作用域策略 |
| 知识图谱 | Neo4j 图存储、图 Schema、实体关系和路径检索、Graph Space 授权 | `KnowledgeGraphTool`、`Neo4jKnowledgeGraphStore`、`GraphSpaceAccessService` | 已实现较完整基础 | 缺少与知识库的联合规划、统一证据结构和跨来源推理评测 |
| 知识路由 | `GapAnalyzer` 支持知识库、思考和 Web Search 判定 | `GapAnalyzer`、`GapAnalysis` | 部分实现 | 当前没有把图谱需求纳入统一路由输出，也没有双通道联合执行计划 |
| 工具注册 | 工具注册、项目 API 工具热加载、请求级不可变工具快照、子 Agent 白名单 | `ToolRegistry`、`RunToolCatalog`、`SubAgentManager` | 已实现基础 | 主 Agent 尚缺少宿主身份到工具、参数和知识范围的统一策略编译 |
| 高风险接口治理 | 接口需人工确认，高风险非 GET 接口需显式风险确认 | `ProjectApiPolicy` | 已实现基础 | 需要与身份、任务目的、参数范围和业务状态验证合并为统一策略 |
| 外部身份传播 | `AgentContext` 支持 `userId`、`tenantId`、凭证和图请求范围；HTTP 工具支持用户凭证透传 | `AgentContext`、`HttpApiTool`、`GraphRequestAuthenticator` | 部分实现 | 需要引入 `integrationId`，防止不同系统中租户与用户编号碰撞；多租户接入模式应 fail closed |
| 图空间作用域 | 租户到 Graph Space 的可选映射，查询采用游标分页，图检索范围由可信上下文缩小 | `MysqlGraphSpaceAccessService`、`graph_space_bindings` | 已实现基础 | 知识库、记忆、会话和 Trace 尚未与相同外部作用域统一 |
| 用户长期记忆 | 会话消息持久化、上下文压缩、用户偏好提炼、单记录长期记忆注入 | `AgentMemoryRuntime`、`PreferenceRefinementWorker`、`UpdateMemoryTool` | 部分实现 | 当前主要是单段用户画像；缺少结构化情景记忆、来源、时间、冲突、置信度与检索评测 |
| Trace | 记录输入、RAG 命中、模型、提示版本、ReAct 步骤、输出、风险、用户确认、耗时和 Token | `AgentTrace`、`RunTrace`、`TraceCollector`、各类 `TraceStore` | 已实现基础 | 缺少业务结果标签、训练候选状态、脱敏状态、数据集版本和 Adapter 血缘 |
| LoRA 训练闭环 | 当前未发现训练、Adapter、SFT、DPO 或模型注册实现 | 全仓库关键词静态检查 | 尚未实现 | 需要新增独立训练服务、数据集构建、评测、模型注册、灰度发布和回滚 |

### 4.3 关键结论

1. Cyrene 已经不是概念原型，具备知识、工具、记忆、Trace 和既有系统接入的工程骨架。
2. 当前项目仍以“分别存在的能力”为主，论文需要补充“统一机制”和“可重复实验”。
3. 四项贡献中，Trace-to-LoRA 的工程完成度最低，但潜在研究价值最高。
4. 用户记忆与模型适配必须保持严格边界，避免将企业动态事实训练进共享 Adapter。
5. 多租户能力应作为宿主系统接入扩展进行评测，不应扩张为 Cyrene 自有租户管理模块。

---

## 5. 核心贡献 C1：企业双通道知识接地

### 5.1 贡献表述

> 提出一种面向企业数据的双通道知识接地机制，将非结构化专业知识交由知识库处理，将实体关系与动态业务数据交由知识图谱或宿主 API 处理，并通过任务分解、查询路由和证据融合支持复合业务推理。

### 5.2 不能只做的事情

以下实现不足以单独构成创新：

- 同时注册 `knowledge_base_search` 和 `knowledge_graph_search` 两个工具；
- 完全依赖模型自行决定调用哪个工具；
- 将图谱结果简单转换为文本 Chunk 后进入统一向量排序；
- 只展示若干成功对话截图。

### 5.3 拟新增机制

建议定义统一知识需求对象：

```text
KnowledgeDemand
  ├─ requiresDocumentEvidence
  ├─ requiresRelationalEvidence
  ├─ requiresLiveSystemState
  ├─ requiresUserMemory
  ├─ temporalConstraint
  └─ requestedSubjects
```

由知识规划器生成：

```text
KnowledgePlan
  ├─ documentQueries
  ├─ graphQueries
  ├─ apiQueries
  ├─ memoryQueries
  └─ fusionStrategy
```

各通道输出统一证据对象：

```text
EvidenceItem
  ├─ evidenceId
  ├─ sourceType
  ├─ sourceId
  ├─ tenantScope
  ├─ subjectIds
  ├─ content
  ├─ validFrom
  ├─ validTo
  ├─ confidence
  └─ provenance
```

### 5.4 假设

> H1：相较于仅向量 RAG、仅知识图谱或图数据文本化方案，Cyrene 双通道机制在同时依赖专业规则和业务关系的任务上具有更高的端到端成功率与证据准确率。

### 5.5 验收条件

- 能自动识别文档、关系、实时状态和用户记忆需求；
- 能在一个任务中生成两个及以上知识子查询；
- 能保留每条结论的来源；
- 能检测文档制度与实时业务状态的时间冲突；
- 能通过消融实验说明路由和融合机制各自的作用。

---

## 6. 核心贡献 C2：宿主身份驱动的能力投影

### 6.1 贡献表述

> 提出一种面向既有系统的身份治理型能力投影机制，将宿主系统提供的可信身份、外部租户、角色、任务目的和凭证编译为请求级工具集合、参数约束、知识范围和输出范围。

### 6.2 外部访问作用域

建议新增不可变对象：

```java
public record ExternalAccessScope(
        String integrationId,
        String tenantId,
        String userId,
        Set<String> roles,
        Map<String, String> attributes,
        Map<String, String> credentials
) {}
```

真实主体键应为：

```text
integrationId + tenantId + userId
```

不能只使用 `tenantId + userId`，否则接入多个宿主系统时可能发生编号碰撞。

### 6.3 策略编译结果

```text
CapabilityProjection
  ├─ allowedTools
  ├─ deniedTools
  ├─ argumentConstraints
  ├─ allowedCollections
  ├─ allowedGraphSpaces
  ├─ allowedMemoryScopes
  ├─ outputFieldPolicy
  └─ credentialBindings
```

### 6.4 双重执行点

1. 模型调用前：无权限工具不进入工具定义，减少越权诱导和工具选择干扰。
2. 工具执行前：重新检查参数、数据范围和凭证，宿主系统继续进行最终鉴权。

### 6.5 单租户与多租户宿主模式

```text
standalone 模式：tenantId 缺失时允许使用固定默认作用域
host-multitenant 模式：tenantId 缺失、无效或与凭证不一致时拒绝请求
```

多租户接入模式不得静默回退到 `000000`。

### 6.6 假设

> H2：相较于全工具暴露、仅执行时鉴权或仅使用工具名称白名单，宿主身份驱动的完整能力投影能够显著降低越权暴露和跨作用域泄漏，同时保持合法任务成功率。

### 6.7 验收条件

- `tenantId`、`userId` 和凭证只能来自可信入口或经验证 Token；
- 模型不能在工具参数中扩大外部作用域；
- 知识库、图谱、记忆和工具使用同一访问作用域；
- 策略输出在一次 Agent Run 内保持不可变；
- 具备无权限工具暴露率、越权调用率和跨作用域泄漏率测试。

---

## 7. 核心贡献 C3：作用域隔离的持久化用户记忆

### 7.1 贡献表述

> 提出一种作用域隔离的用户级持久化记忆机制，将相对稳定的用户语义记忆与可追溯的历史情景记忆分开管理，并通过时间、来源、置信度和任务相关性进行更新与检索。

### 7.2 两类用户记忆

#### 用户语义记忆

- 沟通偏好；
- 专业领域；
- 长期目标；
- 常用输出形式；
- 当前负责项目；
- 经用户明确确认的稳定事实。

#### 用户情景记忆

- 某次任务的目标；
- 当时使用的知识和工具；
- 任务是否成功；
- 用户做过什么纠正；
- 某个决策当时依据什么；
- 经验的有效时间和业务对象。

### 7.3 建议记忆模型

```text
UserMemory
  ├─ memoryId
  ├─ integrationId
  ├─ tenantId
  ├─ userId
  ├─ memoryType: semantic | episodic
  ├─ content
  ├─ sourceTraceId
  ├─ subjectIds
  ├─ confidence
  ├─ verificationStatus
  ├─ validFrom
  ├─ validTo
  ├─ createdAt
  └─ updatedAt
```

### 7.4 写入规则

以下信息允许自动进入“候选记忆”，但不一定立即成为可信记忆：

- 用户明确要求记住的信息；
- 用户对 Agent 结果的明确纠正；
- 已完成且业务状态验证成功的任务；
- 来自宿主系统可信事件的信息。

以下信息不得直接进入可信记忆：

- 模型自行推测的用户属性；
- 未验证的工具失败结果；
- 无来源的总结；
- 其他用户或其他外部作用域的信息。

### 7.5 假设

> H3：相较于无记忆、完整历史拼接、单段会话摘要或普通向量记忆，Cyrene 的作用域隔离记忆能够提高跨会话任务连续性和个性化准确率，并减少过期记忆与跨用户污染。

### 7.6 验收条件

- 语义记忆和情景记忆分开存储和检索；
- 所有记忆都可追溯到来源；
- 支持冲突检测、时间失效、人工纠正和删除；
- 支持按用户、主体、任务和时间范围分页查询；
- 能通过序列化多会话测试证明记忆作用，而不是依赖当前上下文窗口。

---

## 8. 核心贡献 C4：经验证 Trace 驱动的持续参数适配

### 8.1 贡献表述

> 提出一种经验证 Trace 驱动的持续适配闭环，将成功业务轨迹转换为可追溯的工具调用训练数据，通过 SFT 或 DPO 进行 LoRA 微调，并经过安全评测、模型注册、灰度发布和回滚完成 Agent 能力演化。

### 8.2 Trace 不是天然训练数据

只有满足以下条件的轨迹才能成为 SFT 正样本：

```text
任务成功
AND 业务状态验证通过
AND 未发生越权
AND 无非预期数据修改
AND 用户确认或确定性规则认可
AND 完成敏感字段脱敏
AND 工具与提示版本可追溯
```

失败轨迹不能直接作为 SFT 正样本。经过人工修正或确定性修正后，可与原失败轨迹形成 DPO 的 `chosen/rejected` 数据。

### 8.3 数据生命周期

```text
COLLECTED
  → VALIDATED
  → REDACTED
  → APPROVED
  → DATASET_INCLUDED
  → TRAINED
  → EVALUATED
  → CANARY
  → DEPLOYED
  → RETIRED / ROLLED_BACK
```

### 8.4 数据血缘

需要能够从生产 Adapter 追溯至：

```text
adapterVersion
  → trainingRunId
  → datasetVersion
  → sampleId
  → sourceTraceId
```

反向也应能够查询某条 Trace 进入了哪些数据集和 Adapter，以支持数据删除、事故定位和模型废弃。

### 8.5 训练目标

允许进入 LoRA 的内容：

- 工具选择；
- 参数结构；
- 一般业务操作顺序；
- 工具错误恢复；
- 何时查知识库、图谱和实时 API；
- 稳定的领域术语与输出结构。

不进入 LoRA 的内容：

- 特定租户客户事实；
- 实时业务状态；
- 凭证与权限；
- 频繁变化的制度；
- 未经验证的模型推理。

### 8.6 系统分层

```text
Cyrene Runtime（Java）
  ├─ Agent 运行
  ├─ Trace 记录
  ├─ 业务结果验证
  └─ 模型路由

Training Service（Python）
  ├─ Trace 筛选与脱敏
  ├─ 数据集构建
  ├─ PEFT / LoRA
  ├─ SFT / DPO
  └─ 离线评测

Inference Server（vLLM 或等价服务）
  ├─ 基础模型
  ├─ Adapter 版本
  └─ OpenAI-compatible API
```

训练服务不建议嵌入 Java 主进程，避免运行时、训练依赖和 GPU 生命周期相互耦合。

### 8.7 模型路由一致性

每次 Agent Run 应固定使用同一个模型与 Adapter 版本：

```text
ModelRouteSnapshot
  ├─ baseModel
  ├─ adapterName
  ├─ adapterVersion
  ├─ endpoint
  ├─ integrationId
  └─ routeVersion
```

不得在同一 ReAct Loop 中途切换 Adapter。

### 8.8 假设

> H4：相较于基础模型、原始 Trace 直接 SFT 或仅依赖外部记忆，使用经业务结果验证、脱敏和抽象的 Trace 进行 LoRA 适配，能够提高未见业务任务的工具调用成功率，同时控制安全退化、知识泄漏和通用能力回归。

### 8.9 验收条件

- 能从 Trace 生成模型原生工具调用训练格式；
- 训练、验证和测试数据无模板、实体和时间泄漏；
- 新 Adapter 在未见任务上获得稳定提升；
- 越权率和跨作用域泄漏率不增加；
- 支持版本注册、灰度发布和快速回滚；
- 能记录 Adapter 的完整数据与评测血缘。

---

## 9. 研究问题与变量

| 研究问题 | 自变量 | 主要因变量 | 核心消融 |
|---|---|---|---|
| RQ1：双通道知识是否改善复合任务？ | 知识接地方式 | 任务成功率、证据准确率、关系推理正确率、Token 与延迟 | 去掉路由、去掉融合、图数据文本化 |
| RQ2：能力投影是否改善安全性？ | 权限控制方式 | 工具暴露率、越权调用率、跨作用域泄漏率、合法任务成功率 | 仅执行时鉴权、仅工具白名单、无参数约束 |
| RQ3：结构化用户记忆是否改善长期连续性？ | 记忆方案 | 记忆准确率、跨会话成功率、过期事实错误率、污染率 | 无记忆、完整历史、单段摘要、向量记忆 |
| RQ4：经验证 Trace 微调是否改善操作能力？ | 训练数据与微调方法 | 工具成功率、参数准确率、未见任务泛化、安全回归 | 基础模型、原始 Trace SFT、验证 Trace SFT、SFT+DPO |

---

## 10. CyreneBench 评测设计

### 10.1 评测目标

建立一个可重复、可重置、可验证数据库最终状态的企业 Agent 测试环境，避免依赖主观对话截图。

### 10.2 宿主系统场景

第一版建议围绕受控 CRM 场景构建，至少包含：

- 客户；
- 联系人；
- 合同；
- 沟通记录；
- 销售人员；
- 部门；
- 跟进任务；
- 审批记录；
- 企业续约和报价制度文档；
- 不同用户、角色与外部租户作用域。

该环境可以是专用模拟宿主系统，也可以是经过固定版本和数据快照的开源系统。无论采用哪一种，都必须提供数据库重置脚本和确定性验证接口。

### 10.3 初始任务集合

建议第一阶段建立不少于 200 个可执行任务：

| 类别 | 建议数量 | 示例 |
|---|---:|---|
| 文档知识任务 | 30 | 查询续约制度、报价规则、审批要求 |
| 图谱关系任务 | 30 | 查询负责人、客户关系、合同路径 |
| 知识融合任务 | 40 | 结合客户关系与制度生成建议 |
| 工具操作任务 | 40 | 创建任务、更新状态、提交审批 |
| 权限与攻击任务 | 40 | 请求其他作用域数据、诱导越权工具调用 |
| 跨会话记忆序列 | 20 组 | 先产生纠正，若干会话后验证复用 |

任务数量可以在预实验后扩充。数量不是唯一目标，关键是每项任务都具有确定性前置状态、允许操作和结果断言。

### 10.4 代表性复合任务

> 查询当前用户负责的、30 天内合同到期、60 天内没有沟通且价值较高的客户；根据最新续约制度生成跟进计划，并在 CRM 中创建任务。

该任务同时覆盖：

- 用户和外部租户作用域；
- 图谱关系查询；
- 专业制度检索；
- 多工具规划；
- 写操作；
- 最终数据库状态验证；
- Trace 记录；
- 后续经验记忆和训练候选生成。

### 10.5 状态型验证

每项任务应验证：

```text
回答是否满足业务要求
目标数据库状态是否正确
是否修改了无关记录
是否调用了无权限工具
是否访问了其他外部作用域
是否引用了正确知识来源
失败时事务是否保持一致
```

尽量使用确定性状态检查。LLM-as-Judge 仅用于无法完全确定化的表达质量，不作为权限和业务正确性的唯一判断者。

### 10.6 数据集划分

至少同时按以下维度隔离：

- 业务实体隔离：测试客户和合同不出现在训练轨迹中；
- 任务模板隔离：测试任务不能只是训练模板替换名称；
- 时间隔离：模拟制度或接口变化后的未来任务；
- 作用域隔离：保留未参与训练的外部租户作用域用于泄漏测试；
- 提示隔离：测试不复用训练中的完整系统提示和示例。

建议划分：

```text
Train：用于 Trace-to-LoRA
Validation：用于参数选择和早停
Test-N：已见任务类型、未见实体
Test-C：未见任务组合、未见实体和制度变化
Security：越权、提示注入和跨作用域测试
```

---

## 11. 实验基线

### 11.1 RQ1 知识基线

- 无企业知识；
- 仅向量 RAG；
- 仅知识图谱；
- 图谱结果文本化后统一向量检索；
- 模型自行选择两个工具；
- Cyrene 双通道路由与证据融合。

### 11.2 RQ2 治理基线

- 所有工具全部暴露；
- 模型可见全部工具，仅由宿主系统执行时拒绝；
- 仅工具名称白名单；
- 工具白名单 + 参数约束；
- Cyrene 完整能力投影。

### 11.3 RQ3 记忆基线

- 无记忆；
- 完整历史拼接；
- 单段会话摘要；
- 普通向量记忆；
- 当前 Cyrene 单记录用户画像；
- Cyrene 结构化语义与情景记忆。

### 11.4 RQ4 训练基线

- 基础模型；
- 未经验证的原始 Trace SFT；
- 经验证和脱敏的 Trace SFT；
- 经验证 Trace SFT + DPO；
- 仅外部程序记忆，不更新权重；
- 经验证 Trace LoRA + 外部程序记忆。

---

## 12. 指标定义

### 12.1 正确性

- End-to-End Task Success；
- Tool Selection Accuracy；
- Argument Exact Match / Schema Validity；
- Final State Accuracy；
- Knowledge Evidence Precision / Recall；
- Multi-hop Relation Accuracy。

### 12.2 安全性

- Unauthorized Tool Exposure Rate；
- Unauthorized Tool Invocation Rate；
- Cross-Scope Leakage Rate；
- Sensitive Field Exposure Rate；
- Unexpected State Mutation Rate；
- Prompt Injection Success Rate。

### 12.3 记忆

- Memory Precision；
- Memory Recall；
- Stale Memory Error Rate；
- Contradiction Resolution Accuracy；
- Cross-User Contamination Rate；
- Longitudinal Task Success；
- Forget/Delete Compliance。

### 12.4 持续适配

- Adapter Task Success Gain；
- Unseen Task Generalization；
- Tool-Call Recovery Accuracy；
- Base Capability Regression；
- Safety Regression；
- Training Data Scaling Curve；
- Retention and Forgetting；
- Rollback Recovery Time。

### 12.5 工程成本

- 既有系统接入时间；
- 人工配置数量；
- 每任务 Token；
- 推理延迟；
- 工具调用次数；
- 检索次数；
- Adapter 训练成本和部署开销。

---

## 13. 实验规范

1. 锁定基础模型、模型版本、Tokenizer、Chat Template、Prompt 版本和工具 Schema 版本。
2. 固定实验数据快照，并为每项任务提供环境重置能力。
3. 对随机性明显的 Agent 任务进行多次独立运行，报告平均值、方差或置信区间。
4. 调参只能使用 Validation，Test 仅在方案冻结后执行。
5. 所有失败需要归类：路由、检索、规划、工具选择、参数、权限、执行、记忆或最终回答。
6. 报告负结果，不隐藏权限机制降低合法任务成功率等权衡。
7. 记录实验使用的硬件、模型、推理后端、上下文长度、温度、最大步骤数和成本。
8. 权限、数据泄漏和最终数据库状态不得只依赖 LLM 评分。
9. 不保存或发布模型隐藏思维链；仅保留可观察的工具调用、结果、简要理由和最终输出。
10. 论文中的所有提升必须能够由公开或匿名复现材料重新计算。

---

## 14. Trace-to-LoRA 数据方案

### 14.1 Trace 需要补充的字段

在现有 `AgentTrace` 基础上建议增加或关联：

```text
integrationId
tenantId
toolCatalogVersion
knowledgePlanVersion
policyVersion
baseModelVersion
adapterVersion
businessOutcome
stateValidationResult
policyValidationResult
redactionStatus
trainingEligibility
datasetVersion
```

敏感字段建议通过单独受控表关联，避免直接扩张公开 Trace 返回结构。

### 14.2 SFT 样本

每条样本至少包含：

- system、user、assistant、tool 消息；
- 经过过滤的工具 JSON Schema；
- 正确工具调用与参数；
- 脱敏后的工具结果；
- 最终回答；
- 仅用于数据治理而不进入模型文本的来源元数据。

### 14.3 DPO 样本

```text
prompt：相同任务与可用工具
chosen：经确认或修正的正确轨迹
rejected：原失败轨迹或低质量轨迹
```

### 14.4 脱敏策略

- 客户、用户、合同和订单 ID 替换成稳定占位符；
- 删除手机号、邮箱、地址、证件号和账户信息；
- 删除所有 Token、Cookie、Header 密钥和连接串；
- 将具体金额按研究需要区间化或合成化；
- 保留调用关系和参数结构；
- 所有脱敏操作记录规则版本和摘要哈希。

### 14.5 Adapter 组织方式

第一阶段推荐：

```text
共享基础模型
  ├─ CRM 领域 Adapter
  ├─ ERP 领域 Adapter
  └─ 其他系统 Adapter
```

不默认采用“每个外部租户一个 Adapter”。外部租户事实继续通过宿主系统和检索层提供。

只有在数据授权、样本规模、隔离和删除要求均满足时，才考虑宿主系统专属 Adapter。

---

## 15. 安全、隐私与伦理要求

1. 训练数据必须具有明确授权和用途说明。
2. Trace 中的凭证必须在持久化前清除或不可逆遮蔽。
3. 训练数据构建、Adapter 存储和模型部署均需访问控制与审计。
4. 外部租户数据不得未经授权跨作用域混合训练。
5. 共享 Adapter 不得包含可恢复的客户事实。
6. 用户删除请求应能定位相关外部记忆、数据集版本和 Adapter 影响范围。
7. 高风险写操作继续使用宿主系统事务、人工确认和最终鉴权。
8. 动态加载 Adapter 的管理接口不得暴露给普通用户。
9. 新 Adapter 未通过安全回归测试不得进入生产路由。
10. 论文应披露合成数据、真实数据、人工标注和模型生成数据的比例。

---

## 16. 实施路线

### M0：研究冻结（第 1 周）

- [ ] 冻结题目、主张、C1-C4 和 RQ1-RQ4；
- [ ] 建立相关工作矩阵；
- [ ] 冻结实验指标和数据划分原则；
- [ ] 确定一篇完整系统论文或两篇拆分论文。

交付物：本协议 v0.2、相关工作表、实验矩阵。

### M1：外部作用域与能力投影（第 2-3 周）

- [ ] 引入 `integrationId` 与统一 `ExternalAccessScope`；
- [ ] 建立身份到工具、参数、知识范围的策略编译；
- [ ] 区分 standalone 与 host-multitenant 模式；
- [ ] 为主 Agent 和子 Agent 使用统一不可变能力快照；
- [ ] 增加越权和跨作用域测试。

交付物：C2 可运行机制和安全测试集。

### M2：双通道知识规划（第 3-5 周）

- [ ] 将图谱需求纳入统一知识需求分析；
- [ ] 建立查询分解和多通道执行计划；
- [ ] 建立统一证据对象和来源引用；
- [ ] 实现文档、图谱、实时 API 的证据融合；
- [ ] 建立知识消融实验。

交付物：C1 可运行机制和 RQ1 预实验结果。

### M3：结构化用户记忆（第 5-7 周）

- [ ] 拆分用户语义与情景记忆；
- [ ] 加入来源、时间、置信度和验证状态；
- [ ] 实现冲突、失效、删除和作用域检索；
- [ ] 构造多会话序列任务；
- [ ] 与当前单记录记忆进行基线比较。

交付物：C3 可运行机制和 RQ3 预实验结果。

### M4：CyreneBench（第 2-7 周并行）

- [ ] 固定宿主 CRM 场景与数据库快照；
- [ ] 建立文档、图谱、工具、身份和攻击任务；
- [ ] 为每项任务编写状态验证；
- [ ] 建立 Train、Validation、Test-N、Test-C 和 Security 划分；
- [ ] 建立一键重置和批量运行能力。

交付物：任务集、状态检查器、评测脚本和数据说明。

### M5：Trace-to-LoRA 最小闭环（第 7-10 周）

- [ ] 增加 Trace 业务结果与训练资格元数据；
- [ ] 实现 Trace 筛选和脱敏；
- [ ] 导出工具调用 SFT 数据；
- [ ] 使用独立 Python 服务完成第一版 LoRA；
- [ ] 对基础模型和 Adapter 执行相同测试集；
- [ ] 建立模型版本和数据血缘。

交付物：C4 最小闭环、Adapter 和 RQ4 预实验结果。

### M6：完整实验与论文（第 10-13 周）

- [ ] 冻结代码和数据版本；
- [ ] 执行全部基线、消融和安全实验；
- [ ] 进行错误分析和统计分析；
- [ ] 完成论文正文、图表和局限性；
- [ ] 准备匿名仓库与复现说明；
- [ ] 根据目标会议模板排版。

交付物：匿名投稿 PDF、补充材料、匿名代码和数据包。

---

## 17. 投稿拆分决策

### 方案 A：一篇完整系统论文

适用条件：

- C1-C4 全部完成；
- 每个 RQ 都有有效基线和消融；
- Trace LoRA 在未见任务上有稳定提升；
- 安全指标没有明显退化；
- 篇幅允许完整解释四层机制。

风险：内容过宽，审稿人可能认为每个部分深度不足。

### 方案 B：拆成两篇论文

#### 论文一：企业 Agent 运行框架

覆盖：

- 双通道知识；
- 宿主身份能力投影；
- 作用域隔离的用户记忆；
- CyreneBench 的知识、安全和记忆部分。

#### 论文二：持续适配闭环

覆盖：

- Trace 资格判定；
- 脱敏与数据血缘；
- SFT/DPO LoRA；
- Adapter 路由、灰度和回滚；
- 持续学习、安全回归与遗忘评测。

当前推荐：优先按方案 B 管理工程边界；当 M5 完成后，再根据实验强度判断是否合并成一篇完整系统论文。

---

## 18. 论文正文结构

```text
1. Introduction
2. Related Work
3. Problem Definition
4. Cyrene Architecture
   4.1 Dual-Channel Enterprise Knowledge Grounding
   4.2 Host-Identity-Governed Capability Projection
   4.3 Scoped Persistent User Memory
   4.4 Verified Trace-Driven Continual Adaptation
5. CyreneBench
6. Experimental Setup
7. Results
   7.1 RQ1 Knowledge Grounding
   7.2 RQ2 Governance and Isolation
   7.3 RQ3 Longitudinal Memory
   7.4 RQ4 Trace-to-LoRA Adaptation
8. Ablation and Error Analysis
9. Security, Privacy, and Ethics
10. Limitations
11. Conclusion
```

---

## 19. 投稿窗口建议

以 2026-08-18 的完成度判断，不建议为了赶 2026 年 10 月截稿而压缩实验。

- [AAMAS 2027](https://warwick.ac.uk/fac/sci/dcs/aamas2027/calls/) 主会论文截止时间较近，适合已经完成实验的 Agent 方法论文，当前风险较高。
- [ACL Rolling Review](https://aclrollingreview.org/dates) 适合强调 LLM Agent、工具调用、知识和记忆的方法论文；应以完整实验而不是产品功能为前提。
- 软件工程方向可以持续关注后续 ASE、FSE、ICSE 的 Research、Industry 或 Demo Track。
- 若先形成较小但完整的成果，可考虑 Agent、LLM Memory、Tool Use、Agentic AI 相关 Workshop 或 System Demo，再扩展为完整论文。

预印本只应在论文方法、实验和局限性基本完成后发布。

---

## 20. 论文完成定义

满足以下条件后，才进入正式投稿阶段：

- [ ] 四项贡献均有清晰机制，不只是功能描述；
- [ ] 每个研究问题至少有两个有效基线；
- [ ] CyreneBench 可重复运行并重置状态；
- [ ] 训练集和测试集不存在明显污染；
- [ ] 所有写操作都有最终状态验证和非预期副作用检查；
- [ ] 多租户宿主扩展测试的跨作用域泄漏率满足预设安全要求；
- [ ] 用户记忆具有来源、时间、冲突和删除机制；
- [ ] Trace-to-LoRA 数据完成授权、脱敏和血缘记录；
- [ ] 新 Adapter 在未见任务上优于基础模型；
- [ ] 新 Adapter 不造成不可接受的安全或通用能力退化；
- [ ] 所有关键结论均有表格、图和统计证据；
- [ ] 匿名论文、代码、数据和复现说明符合目标会议要求。

---

## 21. 近期第一批任务

在继续开发前，优先完成以下事项：

1. 决定第一篇论文采用方案 A 还是方案 B。
2. 将 `integrationId + tenantId + userId` 确定为外部访问作用域主键。
3. 设计 `CapabilityProjection`，明确工具、参数、知识和输出四类约束。
4. 设计 CyreneBench 的 CRM 数据模型、制度文档和首批 30 个任务。
5. 将现有单段用户记忆迁移方案设计为语义记忆与情景记忆两类。
6. 扩展 Trace 结果标签，但暂不立即开发完整自动训练平台。
7. 先人工完成一次“Trace → 脱敏数据 → LoRA → 离线评测”的最小实验。
8. 根据最小实验判断 Trace-to-LoRA 是否足以成为独立论文主贡献。

---

## 22. 主要风险

| 风险 | 表现 | 缓解措施 |
|---|---|---|
| 贡献过宽 | 四项都实现但每项实验不足 | 采用拆分论文方案，保证每个主张有深度 |
| 工程组合缺少创新 | 只把 KG、RAG、Memory 和 LoRA 接在一起 | 强调路由、策略编译、结构化记忆和验证闭环机制 |
| Trace 数据污染 | 模型错误被训练并进一步强化 | 业务状态验证、人工确认、脱敏和训练资格状态机 |
| 测试集泄漏 | 同模板、同实体进入训练与测试 | 实体、模板、时间和作用域多维隔离 |
| 权限泄漏 | 只在工具执行时鉴权 | 模型前能力投影 + 执行前检查 + 宿主最终鉴权 |
| 用户记忆污染 | 模型推测被当成用户事实 | 候选记忆、来源、置信度和确认机制 |
| LoRA 记住企业事实 | 共享 Adapter 泄漏客户内容 | 仅训练抽象流程，事实继续外置，执行反记忆测试 |
| 微调导致退化 | 工具能力提升但通用能力下降 | 基础能力回归集、灰度发布和版本回滚 |
| 缺少真实数据 | 无法公开企业 Trace | 使用可验证的合成或开源宿主系统，并披露局限性 |

---

## 23. 初始相关工作清单

以下文献用于建立相关工作矩阵，后续需补充正式 BibTeX、方法差异与实验差异：

1. [RestGPT: Connecting Large Language Models with Real-World RESTful APIs](https://arxiv.org/abs/2306.06624)
2. [ToolLLM: Facilitating Large Language Models to Master 16000+ Real-world APIs](https://arxiv.org/abs/2307.16789)
3. [API-Bank: A Comprehensive Benchmark for Tool-Augmented LLMs](https://aclanthology.org/2023.emnlp-main.187/)
4. [AppWorld: A Controllable World of Apps and People for Benchmarking Interactive Coding Agents](https://aclanthology.org/2024.acl-long.850/)
5. [AgentBench: Evaluating LLMs as Agents](https://arxiv.org/abs/2308.03688)
6. [Reflexion: Language Agents with Verbal Reinforcement Learning](https://arxiv.org/abs/2303.11366)
7. [MemGPT: Towards LLMs as Operating Systems](https://arxiv.org/abs/2310.08560)
8. [Voyager: An Open-Ended Embodied Agent with Large Language Models](https://arxiv.org/abs/2305.16291)
9. [Generative Agents: Interactive Simulacra of Human Behavior](https://arxiv.org/abs/2304.03442)
10. [LLM Agents Making Agent Tools](https://aclanthology.org/2025.acl-long.1266/)
11. [APEX-MEM: Agentic Semi-Structured Memory with Temporal Reasoning for Long-Term Conversational AI](https://aclanthology.org/2026.acl-long.749/)
12. [Hugging Face PEFT: LoRA](https://huggingface.co/docs/peft/main/conceptual_guides/lora)
13. [TRL SFTTrainer Tool Calling](https://huggingface.co/docs/trl/main/sft_trainer)
14. [TRL DPOTrainer Tool Calling](https://huggingface.co/docs/trl/dpo_trainer)
15. [vLLM LoRA Adapters](https://docs.vllm.ai/en/latest/features/lora/)

---

## 24. 协议结论

Cyrene 当前已经具备形成研究项目的工程基础，但正式论文不能停留在“拥有知识库、知识图谱、权限、记忆和 Trace”。论文需要证明：

1. 双通道知识机制确实优于单一检索；
2. 宿主身份能力投影确实降低越权且保持可用性；
3. 结构化用户记忆确实提升跨会话表现且不会污染其他作用域；
4. 经验证 Trace 训练出的 LoRA 确实提升未见任务的操作能力且不会造成安全退化。

项目下一阶段的核心不是继续横向增加功能，而是围绕 RQ1-RQ4 补齐机制、基准和证据链。
