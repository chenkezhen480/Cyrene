# Cyrene 与独立 Spring AI Alibaba：已有系统业务接入实测

实验于 2026-10-13 完成，业务基准日期固定为 2026-10-12。使用当前 Cyrene 0.6.6 工作区源码（包含此前修复），独立 Spring AI Alibaba Agent Framework 1.1.2.0 / Spring AI 1.1.2。Alibaba 没有引用 Cyrene 的工具、解析器、权限实现或 ReAct 循环；共用的是合成 CRM、任务、模型配置及等价评分算法。

**本轮支持的结论：两组都完成了相同业务目标；Alibaba 在本样本的耗时和模型用量更低。Cyrene 的接入便利性体现为复用 OpenAPI 导入与可信凭证传递，但尚未证明一般开发者的接入工时节省比例。**

## 结果

11 个业务题，每组重复 3 次，共 66 次；另有各 1 次烟测，排除于正式统计。

| 指标 | Cyrene 当前 ReAct | Alibaba ReactAgent |
|---|---:|---:|
| 业务目标、返回 ID、最终状态规则通过 | 33/33 | 33/33 |
| 行为轨迹核对通过 | 33/33 | 33/33 |
| 完整回复事实复核通过 | **31/33** | **32/33** |
| 平均任务耗时 | 3.499 秒 | 2.915 秒 |
| p50 / p95 | 3.596 / 6.123 秒 | 2.562 / 5.556 秒 |
| 平均 LLM 输入 Token | 5745.6 | 3486.0 |
| 平均 LLM 输出 Token | 367.9 | 319.1 |
| 模型调用合计 | 102 | 90 |
| 输入、输出完整观测 | 33/33 | 33/33 |

相对当前 Cyrene 条件，Alibaba 的平均任务耗时低约 16.7%，输入 Token 低约 39.3%。这是本轮观察值，不等于 Alibaba 框架本身固定快这么多：两组保留各自提示扩展、工具 Schema、错误处理、Trace/会话实现，供应商缓存也未完全受控。两组观测到的缓存输入分别为 164736/189605 与 93051/115038；未换算费用，没有账单或价格核验。

三轮平均耗时分别为 Cyrene 3.541 / 3.496 / 3.461 秒，Alibaba 2.990 / 3.006 / 2.751 秒。顺序为 Cyrene→Alibaba、Alibaba→Cyrene、Cyrene→Alibaba；三轮是相同 11 题的重复，不能解释成 33 个独立业务场景或显著性检验。

## 事实复核

完整保留自动业务评分，同时另外计算完整回答的事实评分；不编辑原始行。

| 合并原始行 | 条件 | 问题 | 可核对依据 |
|---:|---|---|---|
| 35 | Cyrene，第 2 轮 | 说三家客户到期“距今约 2–3 周” | 固定日期 10-12，到期差为 C101 8 天、C105 17 天、C107 2 天 |
| 46 | Cyrene，第 3 轮 | 说三家都将在“两周内”到期 | C105 10-29，到期差 17 天 |
| 56 | Alibaba，第 3 轮 | 说查询共“7 条” | 实际三页为 2、2、1，表格和 ID 列表均为 5 条 |

这些回答的关键 ID 和业务状态正确，但补充叙述有明确事实错误，所以完整回复复核不能写成 100%。Cyrene 还在若干完成回复中提出“改截止日期”等后续能力；当前宿主更新工具只支持 status/expectedVersion，没有执行改期操作。这类未来能力暗示单独记为边界问题，未伪报为实际错误写入。

## 行为证据

- **分页：** 两组共 18 次分页题，客户到期列表每次 2/2/1 条，联合过滤 2/1 条，待办列表 1/1 条；请求 cursor 对应前页 nextCursor，末页 hasMore=false，最终 ID 全部匹配。
- **幂等：** 共 6 次幂等题，每次至少两次成功提交完全相同 body/key，均返回 T0004、version=1；最终只新增一条任务。Cyrene 的参数失败调用没有计作成功重放。
- **越权读取：** 两组各 3 次都实际查询 C201，收到宿主 HTTP 404，没有返回私有客户名。
- **越权更新：** Alibaba 3 次实际 PUT /tasks/T0003→404；Cyrene 3 次查询当前用户任务后条件拒绝，没有发起该 PUT。其中第 2 轮曾误将待办号作为 customerId，收到 404 后纠正。题目允许找不到后报告，因此业务目标通过；**不能据此声称 Cyrene 实测了更新端点的拒绝行为。**
- **缺参：** 共 6 次只询问客户/日期等信息，没有业务写入。
- **非法参数恢复：** Cyrene 的 8 次任务中共 14 次工具参数未能解析成 JSON 对象，当前执行前校验拒绝，结果记录为 0ms 失败；模型后续修正，最终状态全部正确。这也带来额外模型调用。Alibaba 本轮没有观测到同类拒绝；工具参数形状和描述并不相同，不能仅凭这一差异定位根因。

没有把宿主的事务、幂等键、用户数据范围归功于 Agent 框架；这轮证明的是工具接入后，Agent 能按宿主契约操作、读取错误并完成或拒绝请求。业务写入版本与其他任务不变的检查仍以宿主状态快照为准。

## 接入工作量与初始化

Cyrene 路线为获取 OpenAPI→现有生产解析器→审核五个允许的接口→HttpApiTool，复用统一凭证注入与执行器。Alibaba 路线采用官方注解工具与 ToolCallbacks，将五个接口映射到独立 Java HTTP 适配，身份通过 RunnableConfig/ToolContext 注入。[Alibaba 快速开始](https://java2ai.com/docs/quick-start/)、[Spring AI 工具上下文](https://docs.spring.io/spring-ai/reference/api/tools.html)支持该用法。

| 本次实际新增/复用的范围 | 计量 |
|---|---|
| Cyrene 实验中的 registerBusinessTools 方法 | 18 非空物理行，复用已有解析器/HTTP 实现；另需发现调用、宿主凭证绑定和 Agent 初始化 |
| Alibaba 独立 CrmTools.java | 83 非空物理行，包含导入、五个工具及 HTTP/错误/审计适配 |
| Alibaba 模型与 ReactAgent 组装 AlibabaRuntime.java | 54 非空物理行 |
| Alibaba 用量观测 / 实验入口 | 45 / 113 非空物理行，属于基准设施，不能计作业务接入必要代码 |
| Cyrene 自动 API 读取与解析 | 64 / 45 / 51ms；不包括人工接口审核 |
| Cyrene 运行组件初始化 | 4353 / 3979 / 3754ms |
| Alibaba 模型与 Agent 初始化 | 504 / 487 / 481ms |

上述代码范围不完全等价，不能据此算“节省 X% 代码”。Cyrene 的生产中间层预先存在；Alibaba 用户也可以采用 MCP 或独立 OpenAPI 适配器，本轮没有验证这些路线。初始化计时包含的运行组件也不同，且均不含首次依赖下载或开发工时，排除于任务耗时。

首次独立 Maven 组合暴露 Jackson 家族版本冲突（databind 2.19.4 / core 2.17.2 / annotations 2.17.0），产生 NoSuchMethodError；实验工程通过 Jackson BOM 2.19.2 统一版本，生产 POM 未改。原始构建日志和发布 Jar 指纹保留，不能把这次接入困难当作所有 Spring 应用都会出现的问题。

## 控制和复现

- 当前同一 OpenAI 兼容配置：deepseek-flash，temperature=0.7，maxTokens=16384，chat_completions，thinking=off，自动 HTTP 重试关闭。模拟端点验证了 Alibaba 实际发送的参数与隐藏凭证边界。
- 两组只暴露五个业务工具；没有知识摄取、Wiki、记忆写工具、图谱、搜索或 Jev 请求。框架自身补充提示保留原样，因此这是产品默认运行接入路线比较，不是相同提示下的纯循环微基准。
- 每题宿主事务重置；66 个初始快照完全一致，66 个独立 session。原始 model.conf、.env 哈希未变；测量前冻结的 37 项源文件哈希全部匹配。
- 正式六个 block 的私有配置指纹一致：`59b6b0d5f7d6a189104172db16ee969b7b9f3ef066b9081e598a2f54aa255846`。初始准备脚本的知识实验条件和规范化前配置指纹另存 environment-preparation.json；正式环境以 environment.json 为准。
- Java 独立工程 10 项检查通过；Python 28 项通过；Cyrene 业务上下文、取消和预检检查通过；git diff --check 通过。没有新增全量套件、发布包或 UI 验收。
- 独立只读审查确认合并原始行与六个 block 原始文件逐行一致、统计精确重算一致、任务与初始状态一致。审查发现授权审计遗漏“目标 PUT 返回 500”情形，新增离线测试复现失败后修正；500/409 等目标请求错误不能再记作条件拒绝，当前 66 行行为评分不变。
- 收尾检查时 Docker 引擎 named pipe 不存在，未检测到 Docker Desktop/backend 进程，compose stop 失败；状态记录为 completed_engine_unavailable。试验已完成，但不能声称本轮容器经过 stop 验收；存储没有删除或迁移。

复现准备：`python experiments/prepare_experiment.py --run-id <新ID> --start`；编译独立工程：`mvn -f experiments/alibaba/pom.xml test dependency:build-classpath -Dmdep.outputFile=target/classpath.txt`；执行：`python experiments/run_business_comparison.py docs/experiments/.runtime/<新ID>/settings.json --smoke`，再去掉 --smoke 执行 3 次重复。不得复用已有结果目录覆盖证据。

## 原始记录与适用范围

### 后续 PR 的测试上下文修正

[PR #3 的首次 CI](https://github.com/chenkezhen480/Cyrene/actions/runs/38351200784)在新增的 `NativeLangChain4jExperimentTest` 失败：测试假设执行完知识访问上下文必须为空，但原生实验回调的契约是恢复调用前的上下文。完整套件中此前的测试已经设置上下文，恢复成功仍被错误断言判为失败。

本地显式设置外层 tenant/collection/document scope 后复现原断言失败；测试现分别覆盖初始上下文为空及非空，验证内层只读取本次授权范围、执行后准确恢复外层，并在 finally 还原测试前的 HTTP 凭证与知识上下文。相关 12 项定向检查通过；设置 `CYRENE_REQUIRE_RG=true` 后，本地完整 `mvn -B verify` 通过，汇总 1243 项测试，0 失败、0 错误、21 项跳过。此修正只调整测试契约和隔离，不改变生产代码、实验运行器、原始结果或性能数据；远程 CI 结果以 PR 检查为准。

目录：[20261013-business-compare](20261013-business-compare/)。合并原始行 SHA-256：`f912ac7eabadf1f4c31618e9a226188189c6227e753eeeaea17a6dcb94878530`。

- raw.jsonl：66 行，带源 block 文件/行号；各 block 原始行保留，合并只新增定位元数据。
- analysis.json/results.md：业务规则统计；review.json/analysis-reviewed.json/results-reviewed.md：完整回复事实复核。
- behavior-audit.json：分页链、幂等重放、授权行为与非法参数拒绝；verification.json：会话、初始状态、配置和源文件核验。
- execution-order.json：运行顺序和进程耗时；各 block/setup.json：初始化、工具和配置摘要；dependencies.json：发布依赖指纹；verification/：准备与验证日志。

这是一套合成 CRM、11 个任务、3 次重复的独立接入预实验。未测第二种宿主、真实开发者工时、Alibaba RAG、AgentScope、并发吞吐、长会话或整体运维费用。此前 Wiki 四份文档的结果保持独立，不与这组数据混算。现有证据适合向评委说明“已经与独立 ReactAgent 跑过相同宿主任务”，不适合宣称 Cyrene 全面优于 Alibaba。

后续优先实验：相同内容的提示与 Schema 消融，用于定位额外输入和非法参数来源；第二类已有系统的独立 OpenAPI 接入；无脚本提示的日期/数量核算与不存在工具能力约束；宿主版本冲突、短时错误和长会话下的恢复。Jev 继续不做独立速度测量。
