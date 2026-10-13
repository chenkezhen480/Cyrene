# 独立 Spring AI Alibaba 业务接入实验计划

> 执行方式：superpowers:executing-plans；用户已授权继续当前源码与隔离容器实验。本轮不提交、不发布。

**目标：** 将当前 Cyrene 与独立 Spring AI Alibaba ReactAgent 接入同一合成 CRM，完成 11 个业务任务的三轮对照。

**设计：** 共享宿主、任务、模型配置和结果判定，Alibaba 不引用任何 Cyrene 工具、OpenAPI 解析器或循环实现。Alibaba 使用官方 ToolCallback/ToolContext 方式，以 Java HTTP 客户端连接业务 API；身份只在可信运行上下文中注入。Cyrene 使用已有 OpenAPI 导入及当前 ReAct，只暴露五个业务工具，关闭知识、外部搜索和 Jev 调用。

**技术：** Java 21；Spring AI Alibaba Agent Framework 1.1.2.0；Spring AI OpenAI 1.1.2；现有 Python/SQLite 合成 CRM 与评分器。

**依据：** 用户要求继续真实跨框架实验；[官方快速开始](https://java2ai.com/docs/quick-start/)提供 ReactAgent、ToolCallback、运行上下文和调用上限示例。依赖以发布版源码核实，生产 POM 不改变。

## 约束与判定

- Jev 不测；原始 model.conf、.env 不写；仅隔离实验数据库可重置。
- 每题重置宿主并创建独立会话；业务日期固定 2026-10-12；3 次重复。轮间交替先运行哪一框架，记录组内顺序，供应商缓存仍属于未完全受控因素。
- 同模型、温度、输出上限和 thinking=off；记录实际 API 用量，未知不当零。框架附加提示和参数形状保留各自原生行为。
- 计时分为依赖准备、初始化、实际模型任务；接入代码与配置单独统计。自动化实现耗时不是开发者工时实验，不推导通用节省比例。
- 11 题涵盖业务读写、缺参和授权；不包含 4 个知识题与 1 个混合题，不据此评价 Wiki、RAG 或整个产品。
- 核对分页链、两次相同幂等提交、越权 HTTP 返回、完成声明与最终状态；不能仅凭字面规则报告成功率。

## 执行步骤

- [x] 独立 Maven 实验工程；可信凭证、查询编码、原始 JSON body、权限错误、非法参数检查通过。早期 RED 包含缺类/导入与依赖准备失败；不把这些编译失败当作业务缺陷证明。
- [x] 现有 Cyrene 实验入口增加业务条件；Tier0 检查由期望 false、实际 true 的失败转为通过；知识与 Jev 不调用。
- [x] 独立工程 10 项检查通过；各 1 题烟测后，完成两组各 11 题 × 3 次的正式记录。
- [x] 复核行为、全部答案、源码指纹、用量覆盖和原始配置哈希；生成结果与接入代码范围。
- [ ] 确认本轮容器已停止：收尾时 Docker 引擎 named pipe 不存在，compose stop 无法执行。记录状态为 completed_engine_unavailable，未删除卷或持久化目录。

## 审查重点

未传身份必须明确失败；模型不可设置令牌或任意 URL；查询空值不可发送为 null；HTTP 404/409 不可伪装成功；模型缺失用量必须保持未知。

## 执行记录

实际结果见 [2026-10-13-alibaba-results.md](2026-10-13-alibaba-results.md)。66 行业务与行为规则均通过；完整回复事实复核 Cyrene 31/33、Alibaba 32/33。独立只读审查核验了原始合并、统计、任务契约和源码指纹；发现授权审计可能将目标 PUT 500 误判条件拒绝，已按 RED→GREEN 修正，不改变当前实测评分。准备环境快照与正式环境分开保存，避免旧三组条件/规范化前哈希混淆。
