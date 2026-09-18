<p align="right"><a href="./README_EN.md">English</a></p>

# Cyrene Agent — 面向已有项目的专用 Agent 开发框架

> 将已有业务系统、领域知识和权限体系接入自主 ReAct Agent，而不是重新开发一套孤立的 AI 应用。

Cyrene Agent 是一个使用 Java 21 构建的 Agent 开发框架。它面向垂直领域开发者，帮助已有系统快速获得自然语言入口、项目接口调用、企业知识检索、关系数据检索、可校验结构化输出、子 Agent 协作和全链路 Trace 能力。

## 为什么需要 Cyrene Agent

Cyrene Agent 解决的是另一类问题：**如何为已有系统构建专用 Agent**。

使用案例1：
<table>
  <tr>
    <td><img src="docs/assets/图片1.png" height="560" alt="场景1"></td>
    <td><img src="docs/assets/图片2.png" height="560" alt="场景2"></td>
  </tr>
</table>

## 快速开始

### 使用 Docker Compose

```bash
cp .env.example .env
# 编辑 .env，至少配置主 Chat Model
docker compose --env-file .env -f docker/docker-compose.yml up -d --build
```
### 本地构建

```bash
mvn clean package -pl harness-server -am -DskipTests
java -jar harness-server/target/harness-server.jar
```

- 服务默认监听 `8080`。 
- 打开 Web Agent端控制台后，可以完成模型配置，项目接口扫描、知识库上传、图谱管理和 Agent 测试对话。
- 推荐内网部署，连接项目后端。
- 对接的租户和身份，以及userID与Token认证参数是通过chat接口的上下文参数传入的，有默认值，可以根据自己系统情况进行设计，默认值面向无租户，无身份设计系统。

完整配置见 [.env.example](./.env.example)。

### 环境要求

- Java 21+
- Maven 3.8+
- Docker 与 Docker Compose

## 核心能力

### 1. LLM wiki+RAG

![wiki界面](docs/assets/wiki.png)
![知识图谱界面](docs/assets/graph.png)

结构：
```text
                              Agent
          │                     │                     │
          ┌─────────────────────┼─────────────────────┐
          ▼                     ▼                     ▼
  knowledge_search       knowledge_read          query_graph
          │                     │                     │
          ▼                     ▼                     ▼
        Wiki                 MySQL                 Neo4j
       Milvus               权威数据               图数据
          │
          ▼
          Document / Episode / Operation

          Wiki 中同时可以存在 GRAPH_SCHEMA capability card
          │
          └──> 提醒 Agent 可使用 query_graph
```
将记忆与知识拆分为： Agent操作记忆用于面向你们项目操作中踩过的坑记录；用户场景记忆用于召回上下文；向量文档用于存储垂类专业知识块；知识图谱用于存储项目实际业务对接关系存储；长期习惯偏好用于记录用户画像。

### 2. 租户与身份校验隔离下的工具列表
![工具权限](docs/assets/tool.png)
工具做分离式设计，比如你的用户是ToC，那么肯定不能让用户拥有控制代码的工具权限，但你又需要依赖工具生成对接项目的接口工具（租户与身份均有面向个人项目使用的默认值）

### 3. 一键对接项目接口
![接口对接](docs/assets/api-detail.png)
通过对接项目接口窗口会在首次启动时主动弹出，配置后在配置页可进行接口修改，接口文件会在后续启动时自动封装为Agent工具（非一次性注入，而是树形管理工具，由上游工具管理）


## 主要模块

整个架构围绕 **Provider → Input → ReAct Loop → Trace** 收束，Tool 作为 ReAct Loop 与业务系统之间的能力边界。

### Provider

Provider 统一模型能力的装配边界，围绕主 LLM 与工具调用模型，支持 Vision、ASR/TTS、Embedding、Rerank、Classifier 和 Realtime 等独立能力。图像、视频等生成能力通过 Provider 配置驱动的生成工具接入。各能力可以选择不同供应商、模型、端点和并发策略，便于企业按质量、成本和数据边界自由组合。

### Input

Input 不只是用户输入，还负责认证、多模态解析、短期与长期记忆注入、上下文构建以及请求级 JSON Context。接入方可以在 Context 中扩展可信参数，例如 `userId`、`tenantId`、输出模式、图谱范围和用户凭证，使 Agent 继承已有系统的身份、租户与业务作用域。

### ReAct Loop

ReAct Loop 执行“模型决策 → 工具调用 → 工具结果检查 → 模型继续决策或输出”的循环。Inspector 与自适应反思机制根据错误、空结果、低相关性和重复调用等信号引导下一步行为。

工具还能通过结构化结果状态推动后续调用进化。例如知识库检索第一次使用低成本的原始查询；当结果处于可挽救的相关度区间时，下一轮隐式升级为查询改写、多查询、Step-back 与 HyDE 组合检索；完全无关或已经升级过的结果不会重复消耗改写成本。

### Trace

Trace 覆盖整个运行过程，包括输入、模型轮次、工具调用与结果、检查和反思状态、运行耗时、Token 消耗、确认决策及最终输出。子 Agent 拥有独立 Trace，并通过 `parent_trace_id` 与主 Agent 关联，从而还原完整任务树。

![审计界面](docs/assets/trace.png)

## 许可证

[Apache License 2.0](./LICENSE)。项目依赖的第三方组件继续适用其各自许可证。
