# 贡献指南

欢迎提交可复现的缺陷、接入案例、文档修正和范围明确的 PR。维护入口为 [@chenkezhen480](https://github.com/chenkezhen480)，协作遵循[行为准则](CODE_OF_CONDUCT.md)。

## 报告问题或提出需求

先查看 [Issues](https://github.com/chenkezhen480/Cyrene/issues) 是否已有相同问题。缺陷报告包含版本 / 提交、运行环境、最小复现、预期与实际结果。需求说明具体使用场景、当前缺口和最小可用结果；大范围架构或依赖变更先讨论边界。

只提供脱敏或合成的输入、配置与日志。不得提交 `.env`、模型密钥、用户 Token、真实儿童档案、IEP 或其他个人资料。安全问题使用 [SECURITY.md](SECURITY.md) 的流程。

## 开发与验证

1. 从当前主分支创建工作分支，先检查 `git status --short`，保留已有改动。
2. 阅读仓库开发说明、相关实现、调用者和测试。当前源码与实际返回契约优先于历史规划。
3. 修改必要的调用者、配置与测试，清除被替换的死代码。保持分页、事务、可信身份和请求级工具目录的约束。
4. 执行与改动匹配的验证，记录准确命令和结果。仅文档改动检查链接、事实及差异，不要求重复整个代码套件。

Java 单元 / 组件检查：

```bash
mvn -B verify
mvn test -pl harness-agent,harness-server -am -DskipITs
```

真实存储集成检查使用 `mvn test -Pintegration`，必须先准备并确认隔离的数据库 / 容器目标；默认 `verify` 不代表 `*IT.java` 已执行。不要使用生产数据执行写入、删除或重置测试。

前端改动在 JavaScript 测试目录执行 `node --test`，并检查语法：

```bash
node --check harness-server/src/main/resources/public/js/app.js
node --check harness-server/src/main/resources/public/js/api.js
node --check harness-server/src/main/resources/public/js/i18n.js
git diff --check
```

PowerShell 中给复杂或逗号分隔的 `-D...` 参数加引号。构建、单元测试、真实模型 E2E、UI 验收及依赖扫描分别记录，不用一项成功替代其他项。

## 提交 PR

PR 说明问题触发条件、最终行为、验证证据和兼容影响。附脱敏复现或截图，保持一个 PR 解决一个明确主题。AI 辅助开发时说明其范围及人工复核内容；生成内容需要同样的审查与验证。

提交消息准确描述改动，不附加 AI / Agent 标记。版本号、发布说明、数据库升级及外部发布按明确的变更范围处理。维护方负责审查和合并；CI 成功并不自动授权发布。

贡献者应有权提交内容，并按仓库 [Apache License 2.0](LICENSE) 的贡献条款提供；第三方代码、图片或文档保留原许可证和必要署名。当前不额外要求 CLA 或提交签署标记。

正式功能与验收范围见[版本状态](docs/PROJECT_STATUS.md)，设计与维护责任见[治理说明](docs/GOVERNANCE.md)。本地 TODO 规划文件不纳入后续 Git 版本。
