# 0.6.6 功能与验证状态

核对日期：2026-10-14。版本来源：根 [pom.xml](../pom.xml) 的 `revision=0.6.6`。代码核对基线：`87b261ac5a8389ec679c9bd39a4aadefa93e1db1`。下方运行记录保留各自的提交与环境，文档更新不代表新版本发布。

## 如何判断完成度

“源码与回归测试存在”表示该能力已经实现并有针对性检查；“CI 通过”表示相应命令在该提交成功；“真实模型实验”仅覆盖记录中的模型、任务和环境。三者均不自动等同于生产验收、完整存储集成或专业效果验证。

## 已实现的框架能力

| 能力 | 实现与回归入口 | 当前验证范围 |
|---|---|---|
| 自主 ReAct、取消与结构化输出 | [ReActEngine](../harness-react/src/main/java/com/harness/react/ReActEngine.java)、[循环测试](../harness-react/src/test/java/com/harness/react)、[会话协调测试](../harness-agent/src/test/java/com/harness/agent/runtime/AgentRunCoordinatorSessionTest.java) | Java 回归；业务实验覆盖部分行为 |
| OpenAPI 解析、业务 HTTP 工具 | [OpenApiSpecParser](../harness-tool/src/main/java/com/harness/tool/discovery/OpenApiSpecParser.java)、[HttpApiToolQueryTest](../harness-tool/src/test/java/com/harness/tool/HttpApiToolQueryTest.java) | 五接口合成 CRM 独立对照，不能推广为所有 OpenAPI 形式均支持 |
| 请求级工具目录与子 Agent 范围 | [RunToolCatalog](../harness-tool/src/main/java/com/harness/tool/RunToolCatalog.java)、[子 Agent 测试](../harness-agent/src/test/java/com/harness/agent/SubAgentRunScopeTest.java) | Java 单元 / 组件回归 |
| Wiki 发现与签名知识读取 | [KnowledgeReadTool](../harness-agent/src/main/java/com/harness/agent/knowledge/KnowledgeReadTool.java)、[读取测试](../harness-agent/src/test/java/com/harness/agent/knowledge/KnowledgeReadToolTest.java) | 授权与修订回归；四份文档的小样本模型实验 |
| 图谱修改草稿、确认与持久化协调 | [GraphChangeDraftService](../harness-tool/src/main/java/com/harness/graph/build/GraphChangeDraftService.java)、[Saga 测试](../harness-tool/src/test/java/com/harness/graph/build/GraphMutationSagaServiceTest.java) | 草稿、冲突与协调逻辑回归；本次 CI 不代表真实 Neo4j / MySQL 全链路验收 |
| 控制台认证、接口与资源授权 | [RequestPrincipalResolver](../harness-server/src/main/java/com/harness/server/security/RequestPrincipalResolver.java)、[授权测试](../harness-server/src/test/java/com/harness/server/security/InternalApiAuthorizationTest.java) | Java 认证与资源边界回归；宿主仍负责业务权限 |
| 独立服务、单租户与多租户范围 | [服务入口](../harness-server/src/main/java/com/harness/server/Main.java)、[AgentContext](../harness-core/src/main/java/com/harness/core/model/AgentContext.java)、[可信身份解析](../harness-server/src/main/java/com/harness/server/security/RequestPrincipalResolver.java) | HTTP / SSE 接入；默认范围和认证主体的租户、用户、身份绑定；接入方配置可信身份映射 |
| 知识与记忆相关性选择 | [加权 RRF](../harness-tool/src/main/java/com/harness/tool/knowledge/index/ReciprocalRankFusion.java)、[知识路由](../harness-agent/src/main/java/com/harness/agent/knowledge/KnowledgeDiscoveryRouter.java) | 偏好激活、范围过滤、语义与关键词融合及按配置重排；不将检索权重混同为模型参数 |
| ASR / TTS 与 Qwen Realtime | [VoiceConversationService](../harness-agent/src/main/java/com/harness/agent/voice/VoiceConversationService.java)、[Realtime Handler 测试](../harness-server/src/test/java/com/harness/server/RealtimeSessionHandlerTest.java)、[Provider 测试](../harness-provider/src/test/java/com/harness/provider/impl/QwenRealtimeModelProviderTest.java) | 实现与协议回归存在；未据此声称实时音视频、打断和工具调用完成真实供应商 E2E |
| Trace、记忆与后台任务恢复 | [Trace 模块](../harness-trace/src/main/java/com/harness/trace)、[子 Agent 恢复测试](../harness-agent/src/test/java/com/harness/agent/SubAgentPersistenceRecoveryTest.java) | Java 组件回归与记录中的实验观测；不等于自动训练发布 |
| Trace 样本的业务微调应用 | [执行记录](../harness-trace/src/main/java/com/harness/trace/TraceCollector.java)、[反馈绑定](../harness-server/src/main/java/com/harness/server/TraceFeedbackService.java)、[谱伴案例](CASE_STUDY_PUBAN.md) | 维护者确认当前将审核后的执行数据用于业务微调与评价；训练准入与模型使用由应用及维护方负责 |

## 可复核的验收记录

| 记录 | 结果 | 限制 |
|---|---|---|
| [当前代码基线 CI：38364068366](https://github.com/chenkezhen480/Cyrene/actions/runs/38364068366)，提交 `87b261a` | 主分支 push 工作流完成，`build-and-test` 通过 | 该次安全任务按工作流条件跳过；构建通过不等于安全扫描或实际部署验收 |
| [历史主分支 CI：38353590283](https://github.com/chenkezhen480/Cyrene/actions/runs/38353590283)，提交 `7f04457` | `mvn -B verify` 成功；远程八模块汇总 1243 项，0 失败、0 错误、14 项跳过 | 默认 Surefire 选择 `*Test.java`；不包含 `integration` profile 的 `*IT.java`，也不自动执行独立 JS / Python 测试 |
| [独立 Spring AI Alibaba 对照](experiments/2026-10-13-alibaba-results.md) | 11 题 × 3 次 × 2 框架，共 66 次；业务目标各 33/33，完整回复事实复核 31/33 与 32/33 | 合成 CRM、单模型；Alibaba 在本样本更快且输入更少；未测完整人工接入工时 |
| [Wiki 消融与原生循环实验](experiments/2026-10-13-results.md) | 16 题 × 3 条件，共 48 次；四道纯知识题 Wiki 开 / 关均 4/4，输入观察差 8.66% | 四份合成文档、每条件一次；未观察到提速，不能外推生产收益 |
| [谱伴案例](CASE_STUDY_PUBAN.md) | 已在线下机构试点，约 100+ 用户，已有绘本与互动页面材料 | 应用状态与规模由维护者于 2026-10-14 确认，非 CI 或性能实验的统计结果 |
| [定时安全扫描：38240556933](https://github.com/chenkezhen480/Cyrene/actions/runs/38240556933/job/114781811027) | 未完成：NVD 数据更新返回 403 / 404，随后 `NoDataException` | 不能记为依赖扫描通过，也不能据此认定已经发现漏洞 |

实验文档中的本地全量记录为 1243 项、21 项跳过；上表远程 CI 为 14 项跳过，保留各自运行环境的结果，不混用数字。

## 已知限制与待验收项

1. `HttpApiTool` 成功响应按 4000 字符截断，可能破坏 JSON 和分页游标；POST / PUT / PATCH 的普通 query 参数和普通 header / cookie 参数装配尚不完整。现有测试通过不能覆盖这些缺口。
2. [Markdown 渲染入口](../harness-server/src/main/resources/public/js/app.js)当前直接使用 `marked.parse()` 与 `v-html`，输出清洗需要加强。默认管理员凭证、对外端口及 Docker socket 的部署边界也需要实际部署验收。
3. 当前 [Main](../harness-server/src/main/java/com/harness/server/Main.java) 为固定服务 Token 使用 `context -> null` 的用户解析器；用户级接口要求可信用户身份。接入方应采用有完整身份声明的 JWT 或接入可信适配器，不能以请求体字段或关闭授权替代认证。
4. 前端依赖外部 CDN；完整 Compose 冷启动、离线控制台、真实模型多模态、并发与长会话尚无本页对应的完整验收记录。
5. Trace 数据的业务微调应用与框架自动训练发布是不同层次。样本准入、专业评价和模型发布由应用及维护方组织，运行记录的验证结果不能代替模型效果评价。

## 发布与后续验证

当前代码版本为 0.6.6，尚无本页对应的 GitHub Release 制品及全新部署验收。正式发布需固定提交，补齐版本变更说明、包指纹、部署步骤、必要回归与示例验收记录；构建成功、跳过测试的打包和发布验收分别记录。

后续优先级见[维护路线](GOVERNANCE.md#维护路线)。本地 TODO 文件不参与后续 Git 跟踪，保留历史提交；公开状态以源码、对应测试及本页列出的运行记录为准。
