# TODO6：Gap 分析式动态路由（GapAnalyzer）

## 0. 背景与目标

目前"要不要思考""要不要检索""用哪种改写策略"这几个处理策略,全部由全局环境变量或请求级显式参数决定,对所有查询一视同仁。本次新增一个前置判定步骤,让系统根据每次查询本身的特征,动态决定这几个参数,同时保留显式配置的最高优先级,不影响已经依赖固定行为的调用方。

**核心设计原则(来自本次讨论的几轮取舍):**

1. 判定结果是**多个独立字段**,不是一个单选分支——"要不要思考"和"要不要检索"可以同时为真,不能用一棵互斥的路由树表达,这是最先要避开的坑。
2. 不引入新的执行范式(状态机/图引擎)——这只是预处理阶段**多插一步**,产出一个结构体,下游各个已有组件（`ContextBuilder`/`QueryRewriterFactory`/`ReActEngine`）各自读取自己关心的字段,该怎么走还怎么走。
3. 判定本身在关键路径上,必须足够轻——规则引擎优先短路,真正要用 LLM 判定时严格压缩参数,不追求异步或缓存来掩盖延迟。
4. 显式配置优先于自动判定——这是给"没有主动配置"的调用方的智能默认值,不是覆盖已有显式行为的强制机制。
5. 判定结果必须留痕——判错不会报错,只会安静地给出偏弱的回答,唯一能补救的是让这个决策可追溯。

---

## 1. 范围与非目标

**本次范围：** 一个前置于现有预处理流程的判定步骤 + 规则引擎 + 轻量 LLM 分类兜底,覆盖以下四个独立参数（对应你列出的五个决策点，第 3、4 点合并说明见 §2）：

- `needsThinking`——是否启用深度思考
- `needsKnowledgeBase`——是否检索内部知识库
- `rewriteStrategy`——查询改写策略（`NONE`/`HYDE`/`MULTI_QUERY`/`STEP_BACK`，仅在 `needsKnowledgeBase=true` 时有意义）
- `needsWebSearch`——是否联网

**明确不做（本轮已讨论并否决，仅作记录）：**
- 不做语义缓存——应复用现有 `VectorStore` 而非新引入 Redis 向量库，且相似度匹配对路由决策存在正确性风险（话题相近不等于处理策略应该相同），暂不做。
- 不做投机执行——用未改写的原始查询做后台预检索，直接违背 `QueryRewriter` 存在的意义，即使命中也可能拿到质量打折的检索结果，暂不做。
- 不做主动澄清反问（`clarify_node` 一类的中断式追问）——这是一个独立的交互设计问题，不并入本次。
- 不引入状态机/图执行引擎（LangGraph 一类）——不改变 Harness 现有的 `AgentOrchestrator`/`ReActEngine` 执行范式。

---

## 2. 核心设计：结构化多字段输出，而非单选路由

```java
record GapAnalysis(
    boolean needsThinking,
    boolean needsKnowledgeBase,
    RewriteStrategy rewriteStrategy,   // NONE | HYDE | MULTI_QUERY | STEP_BACK
    boolean needsWebSearch
) {}
```

原本列出的"是否检索"和"检索的话是否改写、改写的话选哪个策略"这三步,合并成一个 `rewriteStrategy` 字段——`NONE` 同时表达"不检索"或"检索但不改写"两种含义（不检索时该字段无意义，由 `needsKnowledgeBase` 控制是否读取）。这样分类器只需要输出 4 个字段而不是 5 个，减少一次判断分支，也避免出现"改写策略非 NONE 但 needsKnowledgeBase=false"这种自相矛盾的组合需要额外校验。

**关键约束（对照上一份文档暴露的 bug）：** 这四个字段各自独立生效，下游消费方各自读取自己关心的字段，不存在"选中一个分支后其他字段被丢弃"的情况——`needsThinking` 和 `needsKnowledgeBase` 完全可以同时为 `true`（先检索、再基于检索结果深度推理，是常见场景，不是互斥关系）。

---

## 3. 接入位置：预处理阶段前置的一步，不改变现有执行范式

```
Input → [GapAnalyzer] → ContextBuilder（读 needsKnowledgeBase / rewriteStrategy）
                       → ReActEngine.selectModel()（读 needsThinking）
                       → ToolRegistry 本轮可见工具集（读 needsWebSearch，见 §5 待确认项）
```

`GapAnalyzer` 是 `harness-preprocess` 模块下新增的一个类，产出的 `GapAnalysis` 通过 `AgentContext` 或者请求级临时对象向下传递，不需要新建图执行引擎或独立的节点/边模型——这一步执行完，后续流程完全按现有的线性管线继续走。

---

## 4. 三级判定漏斗

### 4.1 显式覆盖（最高优先级）

如果客户端在 `context` 里显式传了某个字段（比如已有的 `context.enableThinking`），这个字段直接采用显式值，**不进入下面的规则引擎和 LLM 分类**——按字段独立判断，不是"只要传了一个字段就跳过整个 GapAnalyzer"。这样懂配置的调用方保留完全控制权，没有主动配置的字段才交给自动判定。

**null 与显式值的约定：**

| 值 | 含义 | 优先级 |
|---|---|---|
| `null`（未传） | 未显式指定，回退到环境变量默认值 | 最低，由 GapAnalyzer 或全局配置决定 |
| 显式值（`true`/`false`/`"NONE"` 等） | 调用方明确指定 | 最高，直接采用，跳过 GapAnalyzer |

示例：`{"needsKnowledgeBase": null}` 回退环境变量；`{"needsKnowledgeBase": false}` 显式禁用检索；`{"rewriteStrategy": null}` 回退环境变量；`{"rewriteStrategy": "NONE"}` 显式不改写（但仍可检索）。

```bash
HARNESS_GAP_ANALYSIS_ENABLED=true   # 功能总开关，默认 true；关闭后所有字段回退到现有全局静态配置
```

### 4.2 启发式规则引擎（Tier 1，耗时 < 1ms）

纯 Java 正则/关键词匹配，处理两端明确的情况：

- **极简拦截**：问候、致谢一类高频短查询，直接返回 `GapAnalysis(false, false, NONE, false)`，短路后续所有步骤。
- **强制触发**：包含"最新""今天""股价"一类时效性词汇 → 强制 `needsWebSearch=true`；包含"推导""证明""分析以下"一类明确指令 → 强制 `needsThinking=true`。
- **规则匹配不到（中等复杂度）时**，放行进入 Tier 2。

关键词/规则表**必须是外部可配置的**（配置文件或 DB，通过 `HARNESS_GAP_ANALYSIS_RULES_FILE` 指定路径），不写死在代码里——规则会随实际使用不断需要调整，硬编码意味着每次微调都要改代码重新发布。

### 4.3 轻量 LLM 分类（Tier 2，兜底）

只有规则引擎判断不了的查询才会走到这一步。使用 `HARNESS_MODEL_CLASSIFIER_*` 配置的专用分类模型（当前为 GLM-4.7-flash），思考模式必须关闭（`"thinking": {"type": "disabled"}`），严格压缩调用参数：

- `max_tokens` 锁定在 50，只够输出压缩后的 JSON
- 关闭流式输出——这一步需要的是完整、合法的 JSON 做反序列化，不是逐字展示给用户
- Prompt 精简，输出用缩写字段名（如 `{"t":bool,"k":bool,"w":string,"s":bool}`），减少生成字符数

**Prompt 分析框架：三维度评估**

Prompt 引导 LLM 从三个维度对查询进行思维分析，最终直接输出四个独立字段：

1. **期望** — 用户想得到什么质量的回答（简单事实 / 信息介绍 / 深度分析 / 实时数据）
2. **现状** — 系统当前能提供什么（模型知识充足 / 需要外部信息补充 / 需要实时数据或多源验证）
3. **问题程度** — 期望与现状的差距（小 / 中 / 大）

三个维度是 prompt 里的思维引导框架，不是硬编码数值。LLM 基于三维度评估后，直接输出四个独立字段的 JSON：

```
{"t": bool, "k": bool, "w": "NONE|HYDE|MULTI_QUERY|STEP_BACK", "s": bool}
```

- `t` = needsThinking — 差距大时需要深度推理
- `k` = needsKnowledgeBase — 现状不足时需要检索
- `w` = rewriteStrategy — 需要检索时选择改写策略
- `s` = needsWebSearch — 需要实时数据时联网

**`rewriteStrategy` 四种策略的选择标准（写入 prompt）：**

| 策略 | 适用场景 | 选择依据 |
|------|----------|----------|
| `NONE` | 短查询、模型知识充足、不需要改写 | 期望 ≤ 现状，直接用原始查询检索即可 |
| `HYDE` | 短查询但语义模糊，需要向量化扩展 | 查询太短，向量检索召回率低，用"假答案"提升语义匹配 |
| `MULTI_QUERY` | 查询覆盖面窄，需要多角度召回 | 现状部分不足，需多措辞查询提高召回覆盖率 |
| `STEP_BACK` | 查询过于具体，需要先获取背景知识 | 期望远高于现状，需先拉取通用上下文再回答具体问题 |

当 `k=false`（不需要检索）时，`w` 字段无意义，统一输出 `"NONE"`。

四个字段独立生效，不互斥。一个查询完全可以同时 needsThinking=true 且 needsKnowledgeBase=true（先检索、再基于检索结果深度推理）。

---

## 5. 各参数的下游消费方

| 字段 | 消费方 | 现状 |
|---|---|---|
| `needsThinking` | `ReActEngine.selectModel()` | 已有读取逻辑，改为读取 `GapAnalysis` 而非仅读取静态配置 |
| `needsKnowledgeBase` | `ContextBuilder` | 决定本轮是否调用 `VectorStore` 检索 |
| `rewriteStrategy` | `QueryRewriterFactory` | 决定用哪个 `QueryRewriter` 策略，替代现在全局固定一种策略的方式 |
| `needsWebSearch` | `ToolRegistry`（本轮可见工具集） | **待确认项**：如果 Harness 目前还没有内置的联网搜索工具，这是本次一并需要补的前置依赖；如果已有，这里只是决定本轮要不要把这个工具暴露给 `ReActEngine` |

`needsWebSearch` 这一行需要先确认现状再排期，其余三个都是对现有组件的读取方式做替换，不涉及新建下游能力。

---

## 6. 失败降级策略

Tier 2 的 LLM 调用本身可能失败（网络错误、返回非法 JSON）。这种情况**不应该导致整次请求失败**——处理原则是"分类失败就当没有这一步",直接回退到现有的全局静态配置（`HARNESS_MODEL_CHAT_THINKING`、`HARNESS_RAG_QUERY_REWRITE` 当前配置的值），而不是抛错阻塞主流程。这跟凭证缺失时的 fail-closed 不是同一类情况——那里"拒绝执行"是安全默认；这里"不影响主流程、退回旧行为"才是对用户体验更负责的默认。

---

## 7. 可观测性：写入 Trace

`GapAnalysis` 的最终结果，以及它是被显式覆盖、规则引擎、还是 LLM 分类命中的，需要记进 `AgentTrace.metadata`，跟现有的 fallback 触发记录、rerank 耗时记录是同一类信息。这是唯一能事后排查"这次为什么没有思考/没有检索"的手段，没有这一步，判错了也无从发现。

---

## 8. 新增 / 修改文件

**新增文件（harness-preprocess）**
- `GapAnalyzer.java` — 三级判定漏斗的主入口
- `GapAnalysis.java` — record，四字段结构体
- `RewriteStrategy.java` — enum（如 `QueryRewriter` 侧尚未有对应 enum，复用；如已有同名概念，直接复用不新建）
- `rules/GapAnalysisRuleEngine.java` — Tier 1 规则引擎，从外部配置文件加载规则

**修改文件**
- `EnvKey.java` — 新增 §9 所列环境变量
- `ContextBuilder.java` — 读取 `GapAnalysis.needsKnowledgeBase`/`rewriteStrategy` 决定是否检索、用哪种改写
- `QueryRewriterFactory.java` — 从"启动时固定一种策略"改为"按 `GapAnalysis` 逐次查询动态选择"
- `ReActEngine.java` — `selectModel()` 读取 `GapAnalysis.needsThinking`，覆盖原来仅读静态配置的逻辑
- `AgentContext.java` — 承载显式覆盖字段（如已有 `enableThinking`，新增 `needsKnowledgeBase`/`needsWebSearch` 等对应的显式覆盖字段）
- `ToolRegistry.java`（待确认，见 §5）— 按 `needsWebSearch` 决定本轮工具可见集
- `.env.example` — 新增本节环境变量

---

## 9. 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `HARNESS_GAP_ANALYSIS_ENABLED` | `true` | 功能总开关，关闭后全部字段回退现有全局静态配置 |
| `HARNESS_GAP_ANALYSIS_RULES_FILE` | `./gap-analysis-rules.json` | Tier 1 规则引擎的外部配置文件路径 |
| `HARNESS_GAP_ANALYSIS_MAX_TOKENS` | `50` | Tier 2 LLM 分类调用的 `max_tokens` 上限 |
| `HARNESS_GAP_ANALYSIS_MODEL` | 复用现有 no-thinking 模型 | 可选，指定专门用于分类的模型 |

---

## 10. 实现顺序（Phase）

**Phase 1 — 核心结构 + 显式覆盖** ✅
`GapAnalysis`/`RewriteStrategy` 模型；`GapAnalyzer` 骨架（先只支持显式覆盖 + 回退默认值，Tier 1/2 先返回固定值占位）；`ContextBuilder`/`QueryRewriterFactory`/`ReActEngine` 接入读取逻辑，确认整条链路能跑通且不影响现有行为（`HARNESS_GAP_ANALYSIS_ENABLED=false` 时行为应与改动前完全一致）。

**Phase 2 — 规则引擎** ✅
`GapRuleEngine` 硬编码正则/关键词规则（问候语拦截 + 时效性/思考触发关键词），<1ms 响应。

**Phase 3 — LLM 分类兜底** ✅
`ClassifierModelProvider` 接口 + `OpenAiClassifierModelProvider`（GLM-4.7-flash，thinking 关闭，max_tokens=50）；`GapClassifier` 调用实现（压缩 prompt、锁 token、非流式）；失败降级逻辑（§6）；trace 记录（gap_source 字段）。

**Phase 4 — `needsWebSearch` 落地**
先确认 §5 里的待确认项，视现状决定是"接入已有工具的可见性控制"还是"补一个新的联网搜索工具再接入"。

Phase 1 是其余三个 Phase 的前提；Phase 2 和 Phase 3 可并行；Phase 4 因为有待确认的前置依赖，建议放在最后，不阻塞其他三个 Phase 的推进。

---

## 11. 验证步骤

1. `HARNESS_GAP_ANALYSIS_ENABLED=false` 时，确认系统行为与本次改动之前完全一致（回归测试基线）。
2. 客户端显式传 `context.enableThinking=true` 但不传其他字段，确认思考字段采用显式值，其余字段仍走自动判定。
3. 构造一个"需要检索又需要深度思考"的查询（如"查一下最新的行业数据并分析这个策略是否可行"），确认 `needsKnowledgeBase` 和 `needsThinking` 同时为 `true`，不会因为命中某个分支而丢掉另一个。
4. 问候语类查询（"你好""谢谢"）确认被 Tier 1 规则引擎短路，未触发 Tier 2 的 LLM 调用（可通过 trace 或调用计数验证）。
5. 人为让 Tier 2 的 LLM 调用返回非法 JSON 或超时，确认请求不失败，正确回退到全局静态配置，且这次降级被记进 trace。
6. 检查一次真实请求的 trace，确认能看到 `GapAnalysis` 最终结果和命中的判定层级（显式/规则/LLM）。
7. 修改 `HARNESS_GAP_ANALYSIS_RULES_FILE` 里的关键词列表，确认不重新编译代码即可生效（验证规则外部化）。

---

## 12. 明确不做的事 / 后续可选项

- 语义缓存、投机执行——已在本轮否决，理由见 §1，作为后续如果有真实延迟数据支撑，可以重新评估的选项，不在本次范围内。
- 主动澄清反问——独立功能，需要单独的交互设计，不并入本次。
- 多模型路由（按任务类型切换不同厂商模型，而非只是同一模型开关思考）——如果以后要做，需要先和现有的 `FallbackChatModel`（按模态路由）、`ReActEngine.selectModel()`（按思考开关选型）两处已有的"选模型"逻辑收敛到一起，不建议再单独加第三处判断入口。
