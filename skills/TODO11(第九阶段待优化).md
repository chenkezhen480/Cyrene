# TODO11：通用 Agent 输出契约、工具事件与检索质量优化

## 0. 文档定位

本计划面向 Cyrene Agent 作为“接入已有业务系统的通用 Agent 开发框架”的核心目标，处理一组互相关联的小问题和架构缺口。

原始需求清单存在两个编号 `3`，本文将其整理为 11 项。每项同时标记决策状态，避免把讨论题误写成实施结论：

1. 独立的结构化输出 API。`已确认要解决`
2. 工具调用文本偶发泄漏为普通消息。`已确认要解决`
3. 知识库检索命中问题文本而非有效内容。`已确认要分析并修复`
4. 会话上下文缓存与模型 Prompt Cache 的指标及优化空间。`基础埋点已完成，真实基线与优化待定`
5. 子 Agent 增加可选完成契约，分别约束必需成功工具和必需产物。`已确认方案`
6. 知识图谱内部使用 DTO，工具输出统一为严格 JSON。`已确认方案`
7. 修复切块后取消启发式 Chunk 回溯，改为确定性标题上下文和 Agent 显式读取。`已确认方案`
8. 知识库控制台按文件名搜索。`已确认要解决`
9. Token 估算和 Markdown Chunk 边界修复。`已确认要解决`
10. 上游 OpenAI-compatible Provider 可选使用 Responses API，不提供入站 `/v1/responses`。`已确认方案`
11. SearXNG 错误引擎和权重优化。`已确认要解决`

本轮计划优先修复协议正确性、可观测性和数据边界，不以增加更多隐式兜底为目标。

### 0.1 决策状态规则

| 状态 | 含义 | 是否可直接进入实施阶段 |
|---|---|---|
| 已确认要解决 | 用户已经明确要求修复或新增 | 可以 |
| 已确认要分析并修复 | 问题已确认，但根因和具体方案需先用测试定位 | 只能先做复现与分析，根因确认后实施 |
| 已确认方案 | 架构方向已讨论确认 | 可以按计划实施和验证 |
| 已确认先补观测 | 已确认补齐指标，具体优化动作仍需基线数据 | 可以增加观测；不能提前实施未验证的优化 |

### 0.2 已确认架构决策

#### A. 子 Agent 使用可选完成契约

决策：保留 `allowedTools` 作为权限边界，并新增可选 `completionContract`。契约分别声明 `requiredSuccessfulTools`、`requiredArtifacts` 和可选 `outputSchema`；未传契约的普通子任务保持现有自由执行方式。必需文件必须根据 ArtifactStore 实际记录校验，不能相信模型自由文本中的路径。

#### B. Neo4j 内部 DTO + 严格 JSON 工具输出

决策：图谱查询在内部先形成 DTO；`knowledge_graph_search` 的所有成功和空结果统一序列化为严格 JSON Envelope，不再拼接自然语言标题或半结构化文本。管理 API 可以从相同 DTO 映射自己的响应类型，不复用面向模型的字符串 formatter。

#### C. 修复切块后取消启发式 Chunk 回溯

决策：先完成 Markdown Block 切块和稳定文档元数据，再移除基于标点、大小写和连接词的自动回溯。检索命中携带确定性的标题层级上下文；需要正文前后窗口时，由 Agent 调用受限的独立工具 `knowledge_context_read`。删除自动回溯前必须用固定语料比较质量、调用次数、延迟和 token，防止迁移造成无证据回退。

#### D. Responses API 只作为上游 Provider 可选协议

决策：`openai` Provider 允许在 Chat Completions 与 Responses 间配置选择。Cyrene 的对外 `/api/chat` 和 `/api/structured-output` 契约不变，本轮不新增入站 OpenAI-compatible `/v1/responses`。首期继续由 Cyrene 管理会话状态并向上游发送完整输入，不自动启用 `previousResponseId` 或 Provider 托管会话。

### 0.3 Cache 观测边界

会话上下文缓存和模型 Prompt Cache 是两个独立观测对象：前者采集 hit/miss、数据来源、加载耗时、回填和淘汰；后者采集 cached input token、命中率、延迟和稳定前缀变化原因。没有基线数据前不承诺重排 Prompt、引入 cache key 或改变会话存储。

---

## 1. 实施前代码基线（历史快照）

本节保留立项时的代码现状，只用于解释决策来源，不代表当前实现。实际完成状态以第 14、15、16 节和当前源码、测试结果为准。

### 1.1 结构化输出

- `harness-server/src/main/java/com/harness/server/ChatHandler.java` 目前只有 `/api/chat` 请求模型，没有独立结构化输出端点。
- `ChatHandler.ChatRequest` 不包含输出 Schema。
- `ReActEngine` 每轮使用 `ChatRequestParameters` 发送思考参数和工具定义，最终回答没有独立的输出契约。
- 当前 LangChain4j 1.15 已提供 `ResponseFormat`、`JsonSchema`、`OpenAiResponsesChatModel` 和对应流式模型，可复用但不能让 `harness-react` 绑定 OpenAI 专有类型。

结论：结构化输出不能只在 Prompt 中要求“返回 JSON”，必须成为请求级最终输出契约，并且只在工具阶段结束后的最终模型调用中启用。

### 1.2 工具块渲染

- 普通流式聊天会在模型响应是否包含工具调用尚未确定时，通过 `onPartialResponse` 直接发送 `TOKEN`。
- 只有语音输出会启用 `guardedFinalStreaming` 和独立最终回答阶段。
- SSE 工具事件只有 `toolName`，没有稳定的 `toolCallId`。
- 前端通过 `findLast(toolName)` 匹配 queued/running/done；同名并行或连续调用时可能匹配错误。
- 前端 SSE 解析器依赖 `event: `、`data: ` 和单行 JSON，没有独立事件对象归一化层。

结论：问题不是单纯 CSS 渲染错误，而是模型流、工具事件和前端状态三层都缺少稳定边界。

### 1.3 知识库检索

- `knowledge_base_search` 目前只有一个必填 `query`，并强制要求完整独立问题。
- 隐式升级会生成 3 个改写查询、1 个 step-back 查询和 1 个 HyDE 段落。
- 改写文本仅用于检索，不会被 `ContextBuilder` 直接作为结果返回。
- 真正返回的是命中 Chunk 经回溯、Rerank 后拼接出的自然语言块。
- 当前结果没有稳定 JSON 字段，Agent 无法可靠区分 `chunkId`、文件名、Chunk 序号、分数和正文。
- 当前 Chunk 质量可能制造标题/问题式短块；这是“命中问题而非内容”的优先排查方向，不能先假定为查询改写泄漏。

### 1.4 缓存（先观测再决定）

- 当前存在会话消息缓存：内存或 Redis `SessionMessageCache`。
- `AgentMemoryRuntime.loadMessages` 命中后会直接返回缓存消息，未命中才读取 `MessageStore` 并回填；它优化的是上下文读取，不会减少发送给模型的历史 token。
- 当前会话缓存没有记录 hit/miss、memory/Redis/database 来源、加载耗时、回填次数和分类淘汰原因。
- 当前没有记录模型 Prompt Cache 的 `cachedTokens`、命中率或 cache write 信息。
- `ReActEngine.logTokenUsage` 只记录 input/output token 总数。
- LangChain4j 的 OpenAI 元数据实际可以暴露 `OpenAiTokenUsage.inputTokensDetails().cachedTokens()`，但当前没有采集。
- System Prompt 前部较稳定，尾部会根据知识库、图谱、联网路由、Skills 和长期记忆动态变化。
- Tool Catalog 已按工具名稳定排序，这一点有利于前缀缓存，应保持。

当前判断：先把“会话对象缓存”和“模型 Prompt Cache”分开观测。是否优化以及采用哪种优化，需要拿到指标后再讨论，不凭请求耗时猜测缓存是否命中。

### 1.5 子 Agent（已确认可选完成契约）

- `tools` 当前只表示允许使用的工具集合，是权限边界。
- `SubAgentTask` 没有必需成功的工具、输出 Schema 或必需产物类型。
- `SubAgentResult.output` 是自由文本，未携带 `artifacts`。
- `await_subagents` / `get_subagents` 虽返回 JSON，但内部 `output` 仍是自由文本，步骤 observation 还会截断。
- 未知 task ID 在 `resolveTaskRecords` 中被静默跳过。

实施结论：允许使用、必须成功执行、必须交付产物是三种不同语义，必须拆分字段。完成契约保持可选；未声明契约时不额外强制工具和产物。

### 1.6 知识图谱工具（已确认 DTO + 严格 JSON）

- `listGraphSpaces` 返回“自然语言标题 + JSON”。
- `findNodes` 返回“自然语言节点/关系 + JSON pageInfo”。
- `findNeighborhood` 返回半结构化文本。
- 空结果返回自然语言句子。

实施结论：内部查询结果先形成 DTO，模型工具侧统一序列化为严格 JSON；管理 API 从 DTO 独立映射响应，不再共享混合文本 formatter。

### 1.7 Chunk 回溯（已确认分阶段移除）

- `ContextBuilder` 会无条件调用 `SemanticContextRetriever`。
- 它用标点、英文小写开头和连接词猜测 Chunk 是否完整，并最多向前回溯 2 块。
- `VectorStore` 为此暴露 `getPrevChunkId` / `fetchById`，PG 额外维护 prev/next 链接。
- 回溯是隐藏行为，Agent 不知道正文为何扩大，也无法主动读取后文。

实施方向：先修复切块并补齐稳定 `documentId + chunkIndex + headingContext`，再移除启发式自动回溯。检索结果返回稳定锚点，由 Agent 在授权范围内通过独立 `knowledge_context_read` 工具显式读取前后窗口。

### 1.8 知识库管理界面

- `GET /api/knowledge/{collection}` 当前一次返回整个集合。
- `VectorStore.listByCollection` 没有分页或过滤参数。
- PG 使用全量 `ORDER BY id`；Milvus 固定 `limit(1000)`。
- 两个存储实现都存在查询失败后返回空集合的路径，会把错误伪装成“没有数据”。
- 前端严格读取 `data.documents`，目前没有 filename、cursor、loading-more 状态。

结论：文件名搜索必须和游标分页一起实现，响应改为共享 `PageResponse<T>`，不能只在前端对已加载的 1000 条做过滤。

### 1.9 Token 与 Markdown Chunk

- `TextChunker.estimateTokens` 当前为 `text.length() / 3`。
- 中英文、代码、Emoji 和 Markdown 标记的估算误差都不可控。
- 当前流程先按标题、空行、分割线拆分，再把相邻块二次贪心合并。
- 注释仍声称“标题或分割线是硬边界”，但实现已不再保护它们，存在注释和行为不一致。
- 分割线可能成为独立 Chunk，标题也可能与正文分离。

结论：Embedding 向量维度和 Token 容量没有直接关系。合并条件应为相邻语义块的真实/近似 token 总数不超过 `chunkTokenSize`，不能与 `embeddingProvider.dimension()` 比较。

### 1.10 Responses API（已确认仅上游 Provider 可选协议）

- OpenAI Provider 当前创建的是 `OpenAiChatModel` / `OpenAiStreamingChatModel`，即 Chat Completions 路径。
- 当前依赖版本已经包含 `OpenAiResponsesChatModel` / `OpenAiResponsesStreamingChatModel`。
- 内部运行时仍应使用 provider-neutral 的 `ChatModelProvider`、`ReActRequest` 和 `ToolResult`。

实施结论：只做“上游模型 Provider 协议可选”，不把内部 ReAct 模型改造成 OpenAI Responses 对象，本轮明确不提供入站 `/v1/responses` 仿真接口。

### 1.11 SearXNG

- `docker/searxng/settings.yml` 使用 `use_default_settings: true`。
- 该语义会加载全部默认引擎，再按名称合并本地 `engines` 配置。
- 因此当前显式配置 Google、Bing、DuckDuckGo、Wikipedia、Brave 并不会移除 Wikidata、Ahmia、Torch 等默认引擎。
- 所有已列引擎默认权重相同，没有体现 Bing 优先。
- `WebSearchTool` 只读取 `results`，没有记录 `unresponsive_engines` 等部分失败诊断。

最终结论：引擎选择以 `HARNESS_TOOL_WEB_SEARCH_ENGINES` 为唯一来源，并随每次搜索请求传入；SearXNG YAML 只保留权重等服务端策略，不再复制选择清单。不要靠隐藏错误消息掩盖不可用引擎。

---

## 2. 总体目标与非目标

### 2.1 已确认目标

- 文本回答与结构化输出拥有清晰、独立的 API 契约。
- 工具规划文本永远不作为最终回答 token 发送。
- 工具事件使用稳定 ID，可正确渲染同名和并行调用。
- 定位知识库命中问题式文本的真实来源，并针对确认的根因修复。
- Markdown 切块保持结构边界，并尽可能填满配置的 token budget。
- 管理列表支持文件名过滤和稳定游标分页。
- 能观测 Prompt Cache 命中率，并形成是否值得优化的结论。
- SearXNG 只启用经过选择的普通搜索引擎，并优先 Bing。

### 2.2 已确认架构方案与实施前置条件

- 知识图谱工具结果内部结构化，并向模型返回严格 JSON。
- 修复切块后，检索结果返回稳定锚点和标题上下文，并由 Agent 显式扩展正文窗口。
- 子 Agent 的权限、执行义务和交付物分别声明和校验，完成契约保持可选。
- OpenAI-compatible Provider 可以选择 Chat Completions 或 Responses 上游协议。
- 根据 Cache 基线数据决定是否重排稳定前缀或引入 cache key。

### 2.3 非目标

- 不把 `responseFormat` 直接塞进现有 `/api/chat`，造成聊天接口兼有两种返回类型。
- 不使用“请务必返回 JSON”的 Prompt 伪装严格结构化输出。
- 不让输出 Schema 影响工具规划轮次。
- 不把 Neo4j 结果送入向量 Rerank。
- 在新切块、稳定锚点和 `knowledge_context_read` 尚未通过回归前，不提前删除现有隐式 Chunk 回溯。
- 不使用 offset 分页。
- 不把模型缓存命中和 Redis 会话缓存混为一个指标。
- 不因为 Provider 不支持严格 JSON Schema 就静默降级为自由文本。
- 不提供入站 OpenAI-compatible `/v1/responses`，也不让 OpenAI output item 成为核心领域模型。
- 不通过关闭 SearXNG 错误展示掩盖失效引擎。

---

## 3. 核心架构调整

```text
HTTP Adapter
  ├─ /api/chat                -> FinalOutputContract.Text
  └─ /api/structured-output   -> FinalOutputContract.JsonSchema
                                      │
                                      ▼
AgentRunCommand / ReActRequest
  -> Tool planning loop（工具可见，禁止输出最终正文）
  -> Tool execution + typed tool events
  -> FinalResponseGenerator（工具不可见）
       ├─ Text：流式文本
       └─ JsonSchema：Provider 严格结构化输出 + 本地校验
                                      │
                                      ▼
Endpoint-specific response mapper
```

新增公共概念建议放在 `harness-core`：

```java
public sealed interface FinalOutputContract {
    record Text() implements FinalOutputContract {}

    record JsonSchema(
            String name,
            JsonNode schema,
            boolean strict
    ) implements FinalOutputContract {}
}
```

`harness-core` 只保存 JSON Schema 数据和通用契约，不依赖 LangChain4j OpenAI 类型。Schema 到 Provider 参数的转换放在 `harness-provider`。

### 3.1 Responses 协议边界与改动规模

首期属于 Provider 层的中等规模适配，不是 ReAct 核心重写：

```text
ReActEngine / AgentOrchestrator
        │  provider-neutral ChatModel / StreamingChatModel
        ▼
ChatModelProvider
        ├─ chat_completions -> OpenAiChatModel
        └─ responses        -> OpenAiResponsesChatModel
```

需要修改：

- `harness-core`：在 `EnvKey` 增加 `HARNESS_MODEL_CHAT_API_FORMAT`，默认 `chat_completions`，非法值启动失败。
- `harness-provider`：OpenAI Provider 根据格式创建普通/流式 Chat Completions 或 Responses 实现，并把协议特有 usage 映射为通用 `ModelUsage`。
- `.env.example`：补充可选协议配置和取值说明。
- Provider 测试：覆盖文本、流式 token、工具调用/结果续接、结构化最终输出、取消、错误和 usage。

首期不修改：

- `ReActEngine` 的轮次、检查、反思和工具执行状态机。
- `RunToolCatalog`、`ToolExecutor`、业务工具及子 Agent 契约。
- Cyrene 对外 `/api/chat`、SSE 和 `/api/structured-output` 的协议。
- 本地会话、Memory 和 Trace 的事实来源地位。

只有后续决定启用 `previousResponseId`、reasoning item 延续或 Provider 托管会话时，才需要增加会话级 Provider continuation 状态；该能力不随本次协议开关自动启用。

---

## 4. 独立结构化输出 API

### 4.1 端点

新增：

```text
POST /api/structured-output
```

首期采用非流式响应，便于在返回前完成 JSON 解析和 Schema 校验。现有 `/api/chat` 请求与 SSE 事件保持兼容。

请求示例：

```json
{
  "input": "分析该客户是否满足升级条件",
  "attachments": [],
  "context": {
    "userId": "u-1001"
  },
  "outputSchema": {
    "name": "customerUpgradeDecision",
    "strict": true,
    "schema": {
      "type": "object",
      "properties": {
        "eligible": { "type": "boolean" },
        "reason": { "type": "string" }
      },
      "required": ["eligible", "reason"],
      "additionalProperties": false
    }
  }
}
```

成功响应：

```json
{
  "data": {
    "eligible": true,
    "reason": "近 12 个月交易额达到升级标准"
  },
  "meta": {
    "sessionId": "...",
    "traceId": "..."
  }
}
```

### 4.2 执行规则

1. 工具规划阶段不携带 `responseFormat`。
2. 工具全部结束后进入 `FinalResponseGenerator`。
3. 最终调用移除全部工具定义，并附加 `FinalOutputContract`。
4. Provider 将通用 Schema 转换为自身支持的严格输出参数。
5. 服务端再次解析并校验返回 JSON。
6. 校验失败抛出明确 `ApiError`，不返回看似成功的字符串。

### 4.3 Schema 安全

- 限制 Schema 总字节数、最大嵌套深度、属性数量和枚举数量。
- 首期禁止远程 `$ref`，避免服务端代替请求方访问任意 URL。
- Schema name 使用受限字符集。
- Provider 不支持严格 Schema 时返回 `STRUCTURED_OUTPUT_UNSUPPORTED`。
- 模型拒绝、截断、空输出和非法 JSON 分别记录明确错误详情。

### 4.4 分层

- `harness-core`：`FinalOutputContract`、结构化输出结果模型。
- `harness-provider`：能力声明、Schema 转换、Provider 请求参数。
- `harness-react`：最终回答阶段，不负责 HTTP DTO。
- `harness-agent`：将请求契约贯穿到 ReAct 和 Trace。
- `harness-server`：请求校验、认证、错误映射、JSON 响应。

---

## 5. 工具调用流与前端工具块修复

### 5.1 后端事件协议

所有工具事件新增并透传稳定的 `toolCallId`：

```json
{
  "toolCallId": "call_123",
  "toolName": "knowledge_base_search",
  "status": "RUNNING",
  "arguments": {}
}
```

统一事件状态：

```text
CREATED -> RUNNING -> AWAITING_CONFIRMATION -> RUNNING -> SUCCEEDED|FAILED|CANCELLED
```

要求：

- `ReActListener`、`StreamEvent`、`ChatHandler` 和前端全部按 ID 传递。
- `TOOL_CALL_DONE` 携带标准状态和错误摘要，不再只传 boolean。
- 确认事件绑定同一个 `toolCallId`。
- 同一事件重复到达时前端幂等更新，不新增重复块。

### 5.2 模型文本隔离

将现有语音模式的“工具阶段/最终回答阶段”抽成通用 `FinalResponseGenerator`：

- 只要本轮存在可见工具，工具规划阶段的 partial text 不发送为 `TOKEN`。
- 工具调用意图只能通过结构化 ToolExecutionRequest 进入工具事件通道。
- 工具阶段结束后，使用无工具的最终调用生成用户可见文本。
- 文本聊天在最终阶段继续流式输出；结构化端点在最终阶段收集、校验后一次返回。

这会增加一次最终模型调用，但能同时解决工具文本泄漏、结构化输出最后一步约束和 TTS 只读取最终回答三个问题。实施前后记录 LLM 调用数、首 token 延迟、总延迟和 cached token 比例。

### 5.3 前端

- 用 `Map<toolCallId, ToolCallViewModel>` 管理状态，不再 `findLast(toolName)`。
- SSE 解析提取为可复用函数，按空行提交一个完整事件，兼容 CRLF 和多行 `data:`。
- 仅 `token` 事件进入 Markdown 渲染。
- 工具 arguments 默认折叠，渲染前转义，不注入 `v-html`。
- 桌面端和窄屏下工具块均不溢出消息宽度。

---

## 6. 工具 JSON 结果协议

Neo4j 已确认采用“内部 DTO + 模型工具侧严格 JSON”。知识库命中也使用同一 Envelope 表达稳定字段，但检索质量仍须先定位根因，不能把格式调整冒充召回修复。

在 `harness-tool` 提供可复用序列化模型：

```json
{
  "status": "SUCCESS",
  "data": {},
  "pageInfo": null,
  "meta": {}
}
```

约束：

- `status` 只表达 `SUCCESS` 或 `EMPTY`；执行错误继续抛 `ToolExecutionException`，由 `ToolExecutor` 形成失败结果。
- 所有成功工具输出必须是一个合法 JSON 文档。
- 不在 JSON 前后追加标题、Markdown 或解释句。
- 分页动作统一使用共享 `PageInfo` 字段。
- `meta` 只放诊断信息，不放正文副本和敏感字段。

### 6.1 知识库搜索结果

返回：

```json
{
  "status": "SUCCESS",
  "data": {
    "hits": [
      {
        "chunkId": "...",
        "documentId": "...",
        "fileName": "manual.md",
        "chunkIndex": 4,
        "headingPath": ["上传文件", "大小限制"],
        "score": 0.86,
        "content": "具体相关内容"
      }
    ]
  },
  "pageInfo": null,
  "meta": {
    "queryCount": 1,
    "provider": "milvus",
    "rerankMs": 28
  }
}
```

检索质量修复顺序：

1. 记录原始 query、rewrite 类型、命中 chunkId、文件名、chunkIndex、候选分数和 rerank 分数。
2. 用固定问题集复现“问题文本命中”，确认命中内容来自语料切块还是查询链路。
3. 修复标题/问题式孤立短块和分割线 Chunk。
4. 对纯标题、纯分隔符、极短低信息块在入库前合并，不在查询时临时丢弃正文。
5. 保留 query rewrite 的原始问题作为 rerankText；生成的 HyDE 只能用于召回，不作为最终正文。
6. 增加重复内容去重，依据稳定 content hash，不只依赖存储 ID。

### 6.2 知识图谱结果

返回：

```json
{
  "status": "SUCCESS",
  "data": {
    "graphId": "...",
    "schemaId": "...",
    "nodes": [],
    "relations": [],
    "paths": []
  },
  "pageInfo": {
    "limit": 50,
    "nextCursor": "",
    "hasMore": false
  },
  "meta": {
    "truncated": false
  }
}
```

要求：

- `DefaultGraphResultFormatter` 改为 JSON DTO 过滤器/序列化器。
- 继续按 Schema 去除 sensitive property。
- 字符和条数限制在对象构建阶段执行，并用 `truncated` 明示；禁止从 JSON 字符串中间 `substring` 截断。
- `listGraphSpaces`、`findNodes`、`findNeighborhood`、空结果使用同一 Envelope。
- Graph 仍保持独立检索路径，不进入向量 Rerank。

---

## 7. 修复切块后取消自动回溯并显式读取上下文

本节为已确认方向，但必须按顺序实施：先完成 Markdown Block 切块、稳定标题上下文和窗口读取回归，再删除启发式回溯，不能先删后用兜底补质量。

### 7.1 工具动作

将检索和上下文扩展拆为两个独立工具，以便模型清楚看到能力边界：

```text
knowledge_base_search   按 query 检索并返回带锚点的命中 Chunk
knowledge_context_read  按 documentId + anchorChunkIndex 读取有限前后窗口
```

`knowledge_base_search` 参数：

```json
{
  "query": "文件上传大小限制",
  "limit": 5
}
```

`knowledge_context_read` 参数：

```json
{
  "documentId": "doc_123",
  "anchorChunkIndex": 4,
  "before": 1,
  "after": 1
}
```

约束：

- `before` / `after` 有环境级上限，模型不能扩大租户、集合或文档授权范围。
- 服务端可信 `knowledgeRequestContext` 覆盖模型传入的集合范围，规则与图谱 trusted context 一致。
- 返回窗口按 `documentId, chunkIndex` 稳定排序。
- `headingPath` 由 Markdown Block 解析阶段维护的标题栈确定，不通过相邻 Chunk 猜测或运行时回溯生成。
- Agent 只有在命中块明显缺少定义、前提或后续步骤时才调用 `knowledge_context_read`。
- 相同参数重复调用仍走当前重复调用检测策略。

### 7.2 删除内容

- 从 `ContextBuilder` 移除 `SemanticContextRetriever.enhance`。
- 删除 `RAG_CONTEXT_LOOKBACK_MAX` 及对应无用配置。
- 删除只为自动回溯存在的启发式判断。
- `getPrevChunkId` / `fetchById` 若仍被管理接口使用则重命名为明确查询；否则删除。
- PG 不再为运行时启发式强制维护 prev/next 链接；保留 `documentId + chunkIndex` 即可表达窗口。

### 7.3 兼容迁移

- 新入库数据必须写入稳定 `documentId`。
- 老数据读取时如果没有 `documentId`，明确返回“需要重新入库”或使用一次性迁移工具；不把同名文件静默视为同一文档。
- 删除自动回溯前先补齐 pgvector 与 Milvus 的窗口读取一致性测试。

---

## 8. 子 Agent 可选完成契约

完成契约为已确认的可选能力。普通任务可以不传；一旦传入，权限、必需成功工具、必需产物和结构化摘要必须分别校验。

### 8.1 分离三个概念

```java
public record SubAgentCompletionContract(
        Set<String> requiredSuccessfulTools,
        List<RequiredArtifact> requiredArtifacts,
        JsonNode outputSchema
) {}

public record RequiredArtifact(
        String artifactType,
        Set<String> allowedMimeTypes,
        int minCount
) {}
```

- `tools`：允许调用，安全边界。
- `requiredSuccessfulTools`：至少成功执行一次，完成条件。
- `requiredArtifacts`：必须由 ArtifactStore 确认产生，交付条件。
- `outputSchema`：子 Agent 最终摘要的结构约束。

### 8.2 校验规则

- required tool 必须同时存在于 allowed tools 和父级 `RunToolCatalog`。
- 不允许 required tool 指向 `spawn_subagent` 等子 Agent 编排工具。
- 文件要求按实际 Artifact 的 type/mimeType 校验，不相信自由文本中的路径。
- 任务结束但未满足契约时状态为 `INCOMPLETE` 或 `FAILED_CONTRACT`，不能标记 `SUCCEEDED`。
- 工具失败时把明确失败原因返回父 Agent，不无限强制重试。
- 未知依赖 task ID 和查询 task ID 必须显式报错，不再静默跳过。

### 8.3 返回格式

`SubAgentResult` 增加：

```text
artifacts
toolExecutionSummary
contractValidation
structuredOutput
```

父 Agent 工具结果统一为 JSON，不把完整 `ReActStep` 和长 observation 重复塞入上下文。完整步骤只保存在子 Trace，通过 `subTraceId` 关联。

---

## 9. 知识库文件名搜索与分页

### 9.1 API

调整为：

```text
GET /api/knowledge/{collection}?fileName=manual&limit=50&cursor=...
```

响应严格使用：

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

`items` 使用明确的 `KnowledgeChunkSummary` DTO，不直接序列化含 embedding 的 `VectorStore.Document`。

### 9.2 存储接口

新增 provider-neutral 方法：

```java
PageResponse<KnowledgeChunkSummary> listKnowledgeChunks(
        String collection,
        String fileName,
        int limit,
        String cursor
);
```

约束：

- `fileName` 为服务端参数化查询，禁止拼接 SQL 或 Milvus filter。
- 使用稳定 `ORDER BY` 和 `limit + 1`。
- Cursor 为不透明编码，至少绑定 collection、filter 和最后排序键，防止跨查询复用。
- 查询异常向上抛出，由 Handler 映射 `ApiError`，不返回空数组。
- `listCollections` 若未来可能增长，也迁移到同一分页契约。

### 9.3 前端

- 增加文件名搜索框、清除按钮、加载状态和错误状态。
- 输入防抖后从第一页重新请求；不得只过滤当前页。
- `加载更多` 使用 `pageInfo.nextCursor`。
- 严格读取 `res.items` 和 `res.pageInfo`，删除旧 `res.documents` 契约。
- 窄屏下搜索框、集合选择和操作按钮纵向排列；表格可切换为卡片布局。
- 删除仍需确认，加载期间禁用重复删除和重复翻页。

---

## 10. Token 估算与 Markdown Chunker 重构

### 10.1 Token 估算接口

在低层定义 provider-neutral 接口，并由组合层注入：

```java
public interface TextTokenEstimator {
    int estimate(String text);
    String strategyName();
}
```

实现策略：

- OpenAI-compatible embedding model 优先使用 `OpenAiTokenCountEstimator`。
- 其他 Provider 若有官方 tokenizer，提供对应适配器。
- 没有 tokenizer 时使用经过中英文、代码和 Emoji 校准的 Unicode-aware estimator，并在日志/metadata 标明是估算值。
- `EmbeddingModelProvider` 暴露 token estimator 或 token 计数能力；`KnowledgeIngestService` 通过注入使用，不在 `TextChunker` 内部读取 Provider。

### 10.2 Markdown Block 模型

先解析为块，再打包：

```text
HEADING
PARAGRAPH
LIST
BLOCKQUOTE
TABLE
FENCED_CODE
HORIZONTAL_RULE
```

打包规则：

1. 标题默认与其后的第一个正文块绑定。
2. 分割线附着到相邻章节边界，不生成独立向量 Chunk。
3. 表格行、代码围栏和列表结构不能被普通句号规则切开。
4. 相邻块合并后 token 数 `<= chunkTokenSize` 就继续合并。
5. 超大块按“表格行/代码行/句子/token window”的顺序降级切分。
6. 每个 Chunk 保存 `documentId`、`chunkIndex`、起止 block index、heading path 和 token count。
7. 不再执行一个语义拆分器加一个行为不一致的 `mergeSmallChunks`；合并由单个 Block Packer 完成。

### 10.3 边界测试矩阵

- 中文、英文、中英混排。
- Emoji、URL、UUID、JSON、Java、SQL。
- 标题后空行、连续标题、标题后列表。
- `---`、`***`、`===` 分隔线。
- fenced code 内出现标题符号和分隔线。
- Markdown 表格和超长表格行。
- 单个块刚好等于、少于和超过 token budget。
- 上一块 + 下一块小于预算时必须合并。
- 合并后任何 Chunk 不得为空、只含分隔线或丢失原文顺序。

---

## 11. 会话上下文缓存与 Prompt Cache 可观测

两类缓存分别采集指标和形成基线报告。观测属于本轮范围；Prompt 重排、cache key、会话缓存容量和后端调整必须在报告完成后再决定。

执行状态：基础埋点与 Trace 字段已完成；真实流量基线、指标导出方式和缓存优化动作整体标记为待定，后续取得可归因数据并确认收益后再恢复实施。

### 11.1 会话上下文缓存指标

每次构建会话上下文记录：

```text
sessionCacheHit
sessionCacheBackend = memory|redis
contextLoadSource = cache|database
contextLoadLatencyMs
loadedMessageCount
cacheRefillCount
```

进程级聚合指标记录：

```text
sessionCacheLookupTotal{backend,outcome=hit|miss|error}
sessionCacheLoadLatencyMs{source=cache|database}
sessionCacheRefillTotal{backend}
sessionCacheEvictionTotal{backend,reason=ttl|userCount|userBytes|globalBytes|explicit}
sessionCacheActiveSessions{backend}
sessionCacheEstimatedBytes{backend}
```

指标标签不携带 `sessionId`、`userId` 或 tenantId，避免敏感信息和无界基数。单次 Trace 可以保存本次是否命中、来源和耗时，但不保存缓存正文。

### 11.2 模型 Prompt Cache 指标

每次 LLM 调用记录：

```text
inputTokens
cachedInputTokens
cacheWriteTokens（Provider 可用时）
outputTokens
reasoningTokens（Provider 可用时）
cacheHitRatio = cachedInputTokens / inputTokens
promptPrefixFingerprint
toolCatalogVersion
llmLatencyMs
```

Trace 只保存计数和不可逆 fingerprint，不记录完整 Prompt、凭证或敏感工具参数。会话缓存命中不能推导 Prompt Cache 命中，两个 hit ratio 不合并。

### 11.3 Provider usage 适配

- 通用 `ModelUsage` 放在 `harness-core`。
- OpenAI Chat/Responses 元数据转换为 `ModelUsage`。
- Anthropic、Ollama 等不支持的字段保持 `null`，不填 0 冒充已观测。
- Run 级统计区分总 input token、cached input token 和实际未缓存 input token。

### 11.4 优化顺序

1. 建立至少一组多轮、工具型和无工具型基准请求。
2. 分别统计会话缓存 hit ratio、数据库加载耗时、Prompt Cache hit ratio 和前缀变化原因。
3. 只在会话缓存数据证明有瓶颈时调整 TTL、容量、淘汰或 memory/Redis 选择。
4. 固定基础 System Prompt、工具 Schema 顺序和序列化顺序。
5. 把会话级 Skills、长期记忆、路由提示放在稳定前缀之后。
6. Responses Provider 可配置稳定的 `promptCacheKey`，key 必须按租户/应用隔离，不能跨不可信边界复用。
7. 对比两阶段最终回答引入的额外 token 是否被前缀缓存抵消。

不新增“缓存开关”或改变缓存后端，直到对应指标证明有需要。

---

## 12. OpenAI Responses API 上游 Provider 兼容

本节为已确认范围：兼容 Cyrene 调用上游模型的协议选择，同时保持框架核心中立。

### 12.1 首期范围：上游 Provider 协议

新增配置：

```properties
HARNESS_MODEL_CHAT_API_FORMAT=chat_completions
```

允许值：

```text
chat_completions
responses
```

非法值启动失败。只有 OpenAI-compatible Provider 可以选择 `responses`；其他 Provider 配置该值时明确启动失败，不静默忽略或回退。`provider=none` 的既有语义不变。

OpenAI Provider 工厂根据配置注入：

- `OpenAiChatModel` / `OpenAiStreamingChatModel`
- 或 `OpenAiResponsesChatModel` / `OpenAiResponsesStreamingChatModel`

内部 `ChatModelProvider`、Tool Catalog、ReActStep、Trace 和业务工具不感知具体协议。

### 12.2 状态策略

首期仍以 Cyrene 自己的会话消息和 Trace 为事实来源：

- 可以先用 Responses API 的无状态完整输入方式验证协议兼容。
- `previousResponseId`、Provider 托管存储、reasoning item 延续作为后续可选优化。
- 若启用 Provider continuation，response ID 必须绑定 session、tenant、provider、model 和凭证范围。
- Provider response 不替代本地业务审计和会话持久化。

### 12.3 明确排除入站 `/v1/responses`

本轮不实现以下入站端点：

```text
POST /v1/responses
GET /v1/responses/{id}
DELETE /v1/responses/{id}
```

如果将来出现“让 OpenAI SDK 直接把 Cyrene 当作兼容服务调用”的明确场景，应另立计划；该适配层需要完整映射 input items、output items、function call、function output、stream event 和 usage，不能只改路径名。

### 12.4 改动判断

首期不需要大改核心。当前 `ChatModelProvider` 已向上提供通用 `ChatModel` / `StreamingChatModel`，LangChain4j 的 Chat Completions 与 Responses 实现可以在 Provider 组合边界替换。主要工作量位于配置校验、模型构造、参数/usage 映射和协议一致性测试。

风险集中在流式工具事件、工具结果回传、结构化输出参数和供应商兼容端点差异，需要完整测试，但这些差异不应穿透到 ReAct 状态机。只有启用 `previousResponseId`、reasoning item 延续或 Provider 托管状态，才会扩大到会话运行时设计。

---

## 13. SearXNG 引擎与搜索结果优化

### 13.1 配置修复

引擎选择只保留一个事实来源：

```properties
HARNESS_TOOL_WEB_SEARCH_ENGINES=bing,duckduckgo,brave,google,wikipedia
```

`WebSearchTool` 在每次 `/search` 请求中显式发送 `engines` 参数；`settings.yml` 不再维护一份重复的 `keep_only` 清单。这样调整引擎时不会出现 Java 配置、Compose 和 SearXNG YAML 三处漂移。SearXNG 可以加载默认引擎定义，但 Cyrene 发出的搜索请求只会调用上述配置清单。

Bing 的服务端权重保持显式：

```yaml
engines:
  - name: bing
    weight: 2.0
```

首轮权重建议以 Bing 为基准最高权重，其余引擎逐级降低。权重最终值通过固定中英文查询集评估，不只按主观偏好决定。

建议评测维度：

- 成功率。
- P50/P95 延迟。
- CAPTCHA/403/429 比例。
- 前 5 条结果相关性。
- 重复域名比例。
- 中文和英文查询覆盖。

Ahmia、Torch 等 Tor 搜索引擎，以及未用于通用网页搜索的 Wikidata 不会出现在 Cyrene 的请求级引擎清单中。

### 13.2 WebSearchTool

- 解析并记录 SearXNG 返回的部分失败引擎信息。
- 有有效 results 时返回成功，同时把不可用引擎放入 `meta.unresponsiveEngines`。
- 全部引擎失败时抛出明确异常；零命中但引擎正常时返回 `EMPTY`。
- 返回结果改为统一 JSON，包含 title、url、snippet、engine、score/category（上游有值时）。
- 去重规范化 URL，保留最优排名，不按标题文本简单去重。
- 结果上限继续由配置控制，避免硬编码在格式化循环。

### 13.3 镜像稳定性

Compose 已固定到 `searxng/searxng:2026.8.21-bbb3c7d82`，避免默认引擎和配置 Schema 随 latest 漂移。容器内外统一监听 `8888`。`server.secret_key` 不再写死在仓库；优先读取 `SEARXNG_SECRET`，未配置时由 Compose 在创建容器时生成随机值。版本更新单独走兼容测试。

---

## 14. 实施顺序

### Phase 1：测试基线与可观测性

- [x] 为普通流式工具调用增加复现测试，捕获工具文本泄漏。
- [x] 建立知识库固定查询/语料基准，保存命中 Chunk 和分数。
- [x] 记录会话缓存 hit/miss、backend、context source、加载耗时和回填。
- [x] 记录会话缓存按原因淘汰、活动会话数和估算内存。
- [x] 扩展 `ModelUsage` 和 Trace，记录 cached token。
- [x] 建立 Markdown Chunk golden tests。
- [x] 建立 SearXNG 中英文查询基准和错误引擎统计。

### Phase 2：最终输出阶段与工具事件

- [x] 抽取通用 `FinalResponseGenerator`。
- [x] 工具规划阶段停止发送普通文本 token。
- [x] `ReActListener` / `StreamEvent` 全链路加入 `toolCallId`。
- [x] 前端按 ID 渲染工具状态。
- [x] 提取并测试 SSE parser。
- [x] 删除被新事件状态机替代的名称匹配死代码。

### Phase 3：结构化输出

- [x] 新增 `FinalOutputContract`。
- [x] 新增 `/api/structured-output` Handler 和 DTO。
- [x] 加入 Schema 安全校验。
- [x] Provider 映射严格 `ResponseFormat`。
- [x] 最终 JSON 二次校验和明确错误映射。
- [x] 增加工具调用后结构化输出的端到端测试。

### Phase 4：工具 JSON 协议（已确认）

- [x] 新增通用 Tool Envelope 和知识图谱结果 DTO。
- [x] `listGraphSpaces`、`findNodes`、`findNeighborhood` 和 EMPTY 统一输出严格 JSON。
- [x] 在 DTO 构建阶段完成敏感属性过滤、条数限制和 `truncated` 标记。
- [x] 知识库命中返回稳定 JSON 字段，但召回问题仍按基准证据独立修复。
- [x] 管理 API 从 DTO 独立映射响应。
- [x] 删除被 DTO 和 JSON 序列化替代的混合文本 formatter。

### Phase 5：Token 与 Markdown Chunk（已确认）

- [x] 注入 `TextTokenEstimator`。
- [x] Markdown Block parser + packer 替换双阶段 split/merge。
- [x] 新入库数据写稳定 documentId 和 block/chunk metadata。
- [x] 用知识库基准验证问题式孤立 Chunk 是否消失。

### Phase 6：显式知识上下文（已确认，切块修复后执行）

- [x] 基于固定语料记录迁移前后的召回完整度、额外调用次数、延迟和 token。
- [x] 检索命中增加稳定 `documentId + chunkIndex + headingContext`。
- [x] 新增独立 `knowledge_context_read` 工具和窗口查询接口，`before/after` 默认均为 1。
- [x] 移除 `SemanticContextRetriever` 自动回溯。
- [x] 删除 prev/next 专用死代码和废弃配置。
- [x] pgvector、Milvus 对齐窗口读取语义。

### Phase 7：知识库管理列表（已确认）

- [x] `VectorStore` 管理查询改为游标分页。
- [x] 实现服务端 filename 过滤。
- [x] 修复存储层吞异常返回空集合的问题。
- [x] 前端严格迁移到 `items/pageInfo`。
- [x] 增加响应式搜索和加载更多交互。

### Phase 8：子 Agent 可选完成契约（已确认）

- [x] `SubAgentTask` 增加可选 `completionContract`，未传时保持普通任务语义。
- [x] 分别校验 `allowedTools`、`requiredSuccessfulTools`、`requiredArtifacts` 和 `outputSchema`。
- [x] 按 ArtifactStore 实际记录校验文件类型和数量。
- [x] `SubAgentResult` 返回 contract validation、artifact 和 tool execution summary。
- [x] 未满足契约时返回明确 `INCOMPLETE` / `FAILED_CONTRACT`，不无限重试。

### Phase 9：Cache 基线分析（先观测）

- [x] 完成会话缓存 hit/miss、context source、加载延迟、回填和淘汰原因的基础埋点。
- [x] 完成 Prompt Cache cached token、hit ratio、延迟和 prefix fingerprint 的 Trace 埋点。
- [ ] 分别输出两类缓存的基线报告和可归因问题。
- [ ] 与用户讨论是否值得优化。
- [x] 未确认前不调整会话缓存容量/后端，不重排 Prompt、不引入 cache key。

### Phase 10：Responses 上游 Provider 可选协议（已确认）

- [x] 新增并严格校验 `HARNESS_MODEL_CHAT_API_FORMAT`。
- [x] OpenAI-compatible Provider 按配置注入 Chat Completions 或 Responses 普通/流式模型。
- [x] 完成 Chat Completions 与 Responses 的协议正确性和 usage 映射测试。
- [ ] 真实 Provider 的延迟、token 和 cache hit ratio 对比纳入 Phase 9 基线。
- [x] 保持本地完整输入和会话持久化，不启用 `previousResponseId`。
- [x] 确认 ReAct、Tool Catalog、ToolExecutor 和对外接口没有协议专有类型。
- [x] 不注册入站 `/v1/responses` 路由。

### Phase 11：SearXNG（已确认）

- [x] 以 `HARNESS_TOOL_WEB_SEARCH_ENGINES` 为唯一选择源，并随请求传递 `engines`。
- [x] Bing 配置为最高权重。
- [x] 用固定中英文查询记录结果和错误引擎；后续换引擎时重新评估权重。
- [x] 记录部分失败引擎而不污染用户正文。
- [x] 固定通过测试的 SearXNG 镜像版本，并统一容器内外端口为 `8888`。

---

## 15. 测试与验证

### 15.1 单元测试

- `ReActEngine`：工具阶段 token 不外泄、最终阶段工具不可见、结构化参数只出现在最终调用。
- `StreamEvent`：同名并行工具使用不同 ID，状态转换合法。
- `KnowledgeBaseTool` / `KnowledgeContextReadTool`：固定语料召回、EMPTY、rewrite、重复调用和窗口限制。
- `KnowledgeGraphToolTest`：严格 JSON 验证三个动作和空结果。
- `TextChunkerTest`：替换为 Markdown block golden tests 和真实 estimator 测试。
- `SubAgentManager`：无契约兼容、required tool/artifact/output contract 成功与失败。
- `KnowledgeManagementHandler`：filename、limit、cursor、非法 cursor 和后端异常。
- `OpenAiChatModelProvider`：两种 API format 创建正确实现，非法 Provider/format 组合启动失败。
- `WebSearchTool`：部分引擎失败、全部失败、空结果、URL 去重。

### 15.2 集成测试

- pgvector 与 Milvus 使用相同语料得到一致管理分页和窗口顺序。
- Neo4j 工具结果均为严格 JSON，且敏感属性过滤不回退。
- 子 Agent 生成文件后 Artifact 可被父 Agent 正确识别和校验。
- `/api/structured-output` 经至少一次业务工具调用后仍符合 Schema。
- Responses Provider 完成完整输入的多轮工具调用、取消和 usage 采集。
- SearXNG `/search?format=json&engines=...` 只请求配置清单中的引擎。

### 15.3 前端验证

- 同名工具并行、串行、确认后继续、失败和取消均只更新对应块。
- 任何工具调用原始文本不会出现在 Markdown 消息区。
- 文件名搜索快速输入时旧请求结果不会覆盖新请求。
- 翻页追加无重复项，切换集合或关键字后 cursor 清空。
- 知识库页面在窄屏下可操作，错误消息来自后端 `ApiError`。

### 15.4 命令

```powershell
mvn test -pl harness-react,harness-agent,harness-tool,harness-server,harness-provider,harness-input -am -DskipITs
mvn clean test -DskipITs
git diff --check
node --check harness-server/src/main/resources/public/js/app.js
node --check harness-server/src/main/resources/public/js/api.js
node --check harness-server/src/main/resources/public/js/i18n.js
```

涉及 Neo4j、Milvus、pgvector 和 SearXNG 的测试在对应容器可用后单独执行，不把不可用依赖伪装为测试通过。

---

## 16. 最终验收清单

### 16.1 已确认项目

- [x] `/api/chat` 保持文本/SSE 契约，`/api/structured-output` 只返回已校验 JSON。
- [x] 输出 Schema 只作用于最后一次无工具模型调用。
- [x] Provider 不支持结构化输出时明确失败，不 Prompt 降级。
- [x] 工具规划文本不进入最终消息、TTS 或 Markdown 渲染。
- [x] 工具事件使用 `toolCallId`，同名调用不会串状态。
- [x] 知识库问题式命中的根因有复现证据，修复后固定语料回归通过。
- [x] 知识库命中的 chunkId、fileName、chunkIndex 和分数在 Trace 或诊断结果中可追溯。
- [x] 文件名搜索为服务端过滤，Chunk 与集合列表均使用稳定游标分页。
- [x] 存储查询错误不会伪装为空列表。
- [x] Markdown 标题、分割线、代码块、表格和列表边界测试通过。
- [x] 相邻语义块在 token budget 内会合并，不拿向量维度当 token 上限。
- [x] 会话缓存 hit/miss、来源、加载耗时、回填、淘汰和容量指标已进入基础埋点/Trace。
- [x] Prompt Cache hit ratio、cached tokens 和 Provider 能力在 Trace 中可见。
- [ ] 两类缓存的真实基线报告和是否优化结论待后续数据，不用一个指标代替另一类缓存。
- [x] 知识图谱工具成功和 EMPTY 结果均为严格 JSON，截断后仍可解析，敏感属性仍被过滤。
- [x] 自动 Chunk 回溯已删除，检索命中携带确定性标题上下文，Agent 可通过 `knowledge_context_read` 读取受限前后窗口。
- [x] 子 Agent 完成契约保持可选；声明后权限、必需工具、必需产物和输出分别校验。
- [x] OpenAI-compatible Provider 可选择 Chat Completions 或 Responses，框架核心保持协议中立。
- [x] Responses 首期使用本地完整会话输入，不启用 `previousResponseId`，不提供入站 `/v1/responses`。
- [x] SearXNG 请求只调用配置清单中的引擎，Bing 权重最高，部分失败只进入诊断元数据。
- [x] 删除已确认改造所替换的重复注释和死代码。

### 16.2 后续仍需二次决策的优化

- [ ] 是否根据会话缓存基线调整 TTL、容量、淘汰策略或 memory/Redis 后端。
- [ ] 是否根据 Prompt Cache 基线重排动态 Prompt 或引入 `promptCacheKey`。
- [ ] 是否启用 Responses `previousResponseId`、reasoning item 延续或 Provider 托管状态。
- [ ] 是否在出现明确 SDK 兼容场景后另立入站 `/v1/responses` 计划。

---

## 17. 外部协议依据

- OpenAI Responses API 创建、流式事件和 usage 字段：<https://developers.openai.com/api/reference/typescript/resources/beta/subresources/responses/methods/create>
- OpenAI 关于 Responses API 在多轮推理、缓存命中和工具调用方面的迁移说明：<https://developers.openai.com/api/docs/guides/latest-model>
- SearXNG `use_default_settings`、`remove` 和 `keep_only`：<https://docs.searxng.org/admin/settings/settings>
- SearXNG 引擎 `weight`、`disabled` 和错误行为：<https://docs.searxng.org/admin/settings/settings_engines.html>
