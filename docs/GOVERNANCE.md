# 贡献、设计责任与维护

本说明基于 0.6.6 源码与公开记录，核对日期为 2026-10-14。项目围绕“让 Agent 懂已有系统、会操作已有系统”提供快速接入与运行设施。

## 上游与 Cyrene 的边界

| 来源 | 提供的能力 | Cyrene 增加或承担的工作 |
|---|---|---|
| LangChain4j | Java 模型接口、消息与工具协议、模型供应商适配和基础工具调用设施 | 自有 ReAct 循环策略、输入准备、请求级目录、错误检查、结构化输出校验，以及宿主接入和运行管理 |
| Javalin、Vue、Marked、Cytoscape.js | HTTP / WebSocket 服务、界面运行、Markdown 解析和图谱展示 | 管理 API、SSE 事件映射、模型配置、知识 / 图谱管理、权限界面与 Trace 展示 |
| MySQL、Milvus、Neo4j、Redis | 事务数据库、向量检索、图数据与缓存 | 知识权威与投影分离、租户 / 用户范围、版本生命周期、索引 outbox、图谱修改协调和会话持久化 |
| MarkItDown、Playwright、SearXNG | 文档转换、渲染网页读取、外部检索 | 转换服务接入、URL 访问范围、分页读取、检索结果协议和 Agent 工具边界 |
| TypeSafe System One / JEV 与模型供应商 | 外部模型推理、结构化判断或音视频能力 | 独立路由维度、配置、协议映射、并发 / 生命周期管理与 Trace 记录；不把供应商模型或指标归为自研 |
| 谱伴等宿主应用 | 领域数据、专业目标、业务 API、真实用户认证和业务授权 | 通用 Agent 设施；领域规则和应用效果仍由宿主负责 |

上述新增能力是可在源码中核对的工程贡献，不等于宣称同类框架均无这些功能，或 Cyrene 在所有场景中性能更优。独立对照的实际结果见[实验记录](experiments/2026-10-13-alibaba-results.md)。

## 实际设计取舍及验证入口

| 取舍 | 原因 | 代码或测试证据 |
|---|---|---|
| 独立部署 Agent，通过 HTTP / SSE 接入宿主 | 让已有项目复用 Agent 设施，无需采用相同语言与开发栈 | [服务入口](../harness-server/src/main/java/com/harness/server/Main.java)、[项目接口发现](../harness-agent/src/main/java/com/harness/agent/ProjectDiscoveryService.java) |
| 兼容默认单租户与可信多租户范围 | 适配不同业务系统，同时继承已有用户身份与权限 | [AgentContext](../harness-core/src/main/java/com/harness/core/model/AgentContext.java)、[身份解析](../harness-server/src/main/java/com/harness/server/security/RequestPrincipalResolver.java) |
| 核心使用自主 ReAct；结构化输出约束最终业务响应 | 保持模型自主选择工具，同时让接入方校验最终返回格式 | [ReActEngine](../harness-react/src/main/java/com/harness/react/ReActEngine.java)、[结构化输出测试](../harness-react/src/test/java/com/harness/react/ReActEngineStructuredOutputTest.java) |
| 每次运行使用不可变工具快照，子 Agent 范围继续收窄 | 防止运行中热加载改变工具定义与执行权限的对应关系 | [RunToolCatalog](../harness-tool/src/main/java/com/harness/tool/RunToolCatalog.java)、[范围测试](../harness-agent/src/test/java/com/harness/agent/SubAgentRunScopeTest.java) |
| MySQL 作为知识权威，Milvus 作为检索投影，图事实保留在 Neo4j | 检索结果不能绕过当前权限、版本和生命周期校验 | [KnowledgeReadTool](../harness-agent/src/main/java/com/harness/agent/knowledge/KnowledgeReadTool.java)、[读取授权测试](../harness-agent/src/test/java/com/harness/agent/knowledge/KnowledgeReadToolTest.java) |
| 按任务相关性组织知识与记忆 | 发挥已有项目的数据价值，减少无关上下文对判断的影响 | [加权 RRF](../harness-tool/src/main/java/com/harness/tool/knowledge/index/ReciprocalRankFusion.java)、[知识路由](../harness-agent/src/main/java/com/harness/agent/knowledge/KnowledgeDiscoveryRouter.java) |
| 宿主继续执行业务授权；框架绑定可信身份并透传请求凭证 | 复用已有系统的数据范围，避免共享管理员身份代替用户权限 | [RequestPrincipalResolver](../harness-server/src/main/java/com/harness/server/security/RequestPrincipalResolver.java)、[HttpApiTool](../harness-tool/src/main/java/com/harness/tool/HttpApiTool.java) |
| 用事务、outbox 与持久化任务协调跨存储操作 | 在异常、重试和恢复后保持权威状态与投影一致 | [知识投影测试](../harness-tool/src/test/java/com/harness/tool/knowledge/index/KnowledgeProjectionFlowTest.java)、[图谱 Saga 测试](../harness-tool/src/test/java/com/harness/graph/build/GraphMutationSagaServiceTest.java) |
| 保留不利结果、失败试验和事实复核 | 让完成声明、性能范围与原始证据可追溯 | [Alibaba 对照](experiments/2026-10-13-alibaba-results.md)、[Wiki 实验](experiments/2026-10-13-results.md) |

这张表记录项目实际采用的设计选择。它不能单凭提交作者或代码行数证明某个选择由哪一个人独立提出；个人的提出、实现与审核归属应由维护者据实声明，并给出相应记录。

## 人与 AI 的职责

项目使用 AI 编码代理辅助实现、排查、测试与材料整理，仓库提供 `AGENTS.md`、`CLAUDE.md`、`.agents/` 和 `.codex/` 的开发说明或配置。这些文件不用于证明人工贡献比例，也不作为功能或测试完成的证据。

人工设计与维护责任包括：确定接入场景及需求范围，决定架构取舍和授权边界，审核 AI 生成的实现与第三方内容，核对实验和失败结果，决定业务材料的专业适配与发布范围。代码是否合格以审查和实际验证为准，AI 的完成声明不能替代这些工作。

维护者确认的产品设计目标包括：以独立 Agent 服务快速接入已有项目；同时支持单租户和多租户范围；将接口工具化与身份权限继承作为业务操作基础；通过知识与记忆的相关性选择利用已有数据；将审核后的 Trace 用于业务模型微调，优化对应业务中的选择与决策权重。这些目标与实现审核、业务验证共同构成人工设计责任。

贡献者在 PR 中说明自己的设计与实现范围、重要取舍、验证方式，以及 AI 辅助的环节。当前未统计人工 / AI 代码比例，不据此声称全部代码手写，也不以 AI 文件的存在否定人的设计责任。

## 维护责任与协作

当前公开维护入口为仓库所有者 [@chenkezhen480](https://github.com/chenkezhen480)。问题分流、变更审查、合并及发布决定由维护方负责；实际贡献以 PR、审查和提交记录为依据。项目未声明基金会或企业提供持续维护保障，也未承诺固定响应时限。

- 普通缺陷与需求通过 [Issues](https://github.com/chenkezhen480/Cyrene/issues) 提交，保持一个主题对应一个可核对的问题。
- PR 使用[贡献指南](../CONTRIBUTING.md)说明改动、证据和兼容影响；涉及宿主协议或存储结构的变更需明确升级路径。
- 安全问题按[安全报告说明](../SECURITY.md)处理，避免公开凭证、个人资料或尚未修复漏洞的可利用细节。
- 发布以固定提交及根 POM 版本为准。新 Release 记录制品、校验值、部署方法、测试范围、已知限制和第三方声明；未来计划不预先记为交付。

## 上游依赖与许可证

Cyrene 当前依赖 LangChain4j 1.15.0，Provider 和 ReAct 等模块直接使用其类型。切换模型供应商不等于可无成本替换底层框架。维护策略是固定版本、按具体需求升级，回归模型协议、流式输出、工具调用、取消与结构化输出；升级结论记录在 PR 和版本验收中。

Cyrene 使用 [Apache License 2.0](../LICENSE)。下表是关键组件的上游声明入口，不是完整依赖清单或整个部署栈的统一许可证结论。

| 组件 | 声明与范围 |
|---|---|
| LangChain4j | [Apache 2.0](https://github.com/langchain4j/langchain4j/blob/main/LICENSE)；具体发布依赖以根 POM / BOM 为准 |
| Vue | [MIT](https://github.com/vuejs/core/blob/main/LICENSE) |
| Marked | [上游 LICENSE](https://github.com/markedjs/marked/blob/master/LICENSE)包含 Marked 的 MIT 条款和 Markdown 的独立声明 |
| Cytoscape.js | [MIT](https://github.com/cytoscape/cytoscape.js/blob/unstable/LICENSE) |
| 存储、容器、转换与浏览器组件 | 以 [Compose](../docker/docker-compose.yml)、各 Dockerfile 与锁定依赖对应版本的许可证、NOTICE 及发行条款分别核对 |
| 外部模型、TypeSafe / JEV、API 服务 | 服务条款、模型权重条款与计费规则独立于本仓库源码协议 |
| 应用案例及图像 | 保留来源与展示范围；素材授权与仓库源码协议分别适用 |

发布包应保留第三方许可证与必要署名；不将依赖、模型权重、宿主业务数据或全部展示素材重新声明为 Apache 2.0。当前没有完成全栈依赖合规或 SBOM 验收的声明。

## 维护路线

| 顺序 | 工作 | 完成条件 |
|---|---|---|
| 当前优先 | 修复通用 HTTP 参数 / 响应完整性、加强 Markdown 渲染与默认部署边界 | 针对原问题的回归通过，文档与实际协议一致 |
| 发布准备 | 全新 Compose 部署、示例验收、安全扫描恢复、固定发布制品 | 固定版本、保留环境和执行记录，逐项说明未覆盖范围 |
| 应用接入 | 持续完善已在线下机构试点的谱伴业务链路，并拓展其他宿主 | 保留业务状态与 Trace 对应关系，验证接口兼容性和身份范围 |
| 性能与检索 | 定位额外输入和参数失败，扩大 Wiki 文档与任务样本 | 保持正确性与授权边界，用配对实验记录性能及摄取成本 |
| 数据与模型优化 | 在已有 Trace 业务微调应用上完善样本质量、效果评价与版本管理 | 明确授权与脱敏范围，保留样本、评价和模型使用记录 |

计划按证据与维护能力调整，不承诺未经评估的发布日期。当前验证范围和限制统一记录在[版本状态](PROJECT_STATUS.md)。
