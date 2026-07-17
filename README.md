<p align="right"><a href="./README_EN.md">English</a></p>

# Cyrene Agent — 开箱即用的 AI 应用开发框架

> **直接指向你的项目目录，Cyrene Agent 自动扫描 REST API 接口、生成工具 Schema、接入对话 —— 你的 AI Agent 即刻拥有了与宿主系统交互的能力。**

基于 **Harness 编排架构** 的 Java AI Agent 框架。无需手动编写 API 对接代码，内置项目接口自动发现、7 种模型类型、RAG 知识库、会话记忆与自主决策路由，让开发者专注于业务逻辑而非底层 plumbing。

## 核心：一键对接项目接口

传统 AI Agent 对接已有系统需要手动编写每个 API 的调用代码。Cyrene Agent 把这个过程自动化了：

```
指定项目目录 → 自动扫描 Controller → 解析 DTO/VO 继承结构 → 生成 project-apis.json → 工具自动注册到 Agent
```

**工作原理：**

1. **Glob** 扫描项目中的 Controller 文件（支持 Spring Boot、Express、Flask 等主流框架）
2. **Grep** 提取路由注解和上下文（`@GetMapping`、`@PostMapping`、`router.get()` 等）
3. **ReadClassHierarchy** 递归解析输入/输出参数类的继承链（最大深度 2），获取完整字段结构
4. 生成结构化的 `project-apis.json`，每个接口对应一个工具定义（名称、描述、参数 Schema）
5. 工具自动注册到 Agent 的工具集，对话中即可调用，身份认证接受拓展context上下文，透传token认证。

**效果：** 启动服务 → 在 Web UI 指定项目目录 → 扫描完成 → Agent 已具备调用你项目接口的能力。无需写一行对接代码。

```
用户: "帮我查询资产管理系统中所有状态为在用的设备"
Agent: [自动调用 project_discovered 的 GET /api/assets?status=in_use 接口]
Agent: "查询到 156 台在用设备，以下是列表..."
```

## 自主决策路由

每个请求自动判断需要哪些能力，避免不必要的开销：

| 场景 | 思考 | 知识库 | 联网 | 查询改写 |
|------|------|--------|------|----------|
| "你好" | ✗ | ✗ | ✗ | ✗ |
| "公司报销政策是什么" | ✗ | ✓ | ✗ | ✓ (multi-query) |
| "今天上海天气怎么样" | ✗ | ✗ | ✓ | ✗ |
| "分析这个需求的可行性" | ✓ | ✓ | ✗ | ✗ |

**三层漏斗（优先级递减）：**

| 层级 | 机制 | 延迟 | 说明 |
|------|------|------|------|
| Tier 0 | 显式覆盖 | 0ms | 请求上下文直接指定 |
| Tier 1 | 规则引擎 | <1ms | 正则/关键词匹配（问候语拦截、时效性问题联网等） |
| Tier 2 | LLM 分类 | ~200ms | 轻量 Classifier 模型分析剩余字段 |

通过 `HARNESS_GAP_ANALYSIS_ENABLED=true` 开启。

## 开箱即用的基础能力

### 7 种独立模型类型

每种模型可独立配置 Provider、API Key 和端点，自由混搭：

| 类型 | 用途 | Provider |
|------|------|----------|
| Chat | 对话 + 工具调用 | OpenAI、Anthropic、Ollama、DashScope 等 |
| Vision | 图片/视频理解 | OpenAI、Anthropic |
| Voice | 语音识别 + 语音合成 | OpenAI |
| Embedding | 向量化 | OpenAI、Ollama |
| Rerank | 检索结果重排序 | OpenAI 兼容接口 |
| Classifier | 意图分类 | OpenAI 兼容接口 |
| Realtime | 实时多模态（预留） | — |

### RAG 知识库

上传文档（PDF、DOCX、XLSX、TXT、Markdown 等）→ 自动提取、分块、Embedding、存储。支持 PostgreSQL pgvector 和 Milvus 两种向量后端，通过 `HARNESS_RAG_PROVIDER` 切换。

内置查询改写（HyDE / Multi-Query / Step-Back）、语义上下文增强、可选 Rerank 重排序。大文件自动采用"切片 → 合并 → 并行摘要"策略，1MB 文件仅需 5-8 次 LLM 调用。

### 会话记忆

- **短期记忆**：按会话 LRU 缓存，支持 Redis 分布式缓存
- **长期记忆**：AI 从已结束会话中提取用户偏好，自动注入 System Prompt
- **智能压缩**：小压缩去除工具调用块（零成本），大压缩 AI 提炼旧消息（时间衰减加权）

### 5 层流水线

```
Input → Session Lifecycle → Preprocess → ReAct Loop (AI ↔ Tool ↔ Inspection) → Post-process → Audit
```

每层独立可观测、可配置、可追踪。ReAct 引擎内置启发式 Inspection（PASS / TOOL_ERROR / WRONG_TOOL / INSUFFICIENT），工具调用失败自动重试，循环检测自动中断。

### 六层容错

| 层级 | 策略 |
|------|------|
| Tool 调用 | 最多重试 3 次，错误传递给 LLM 决策 |
| LLM API | 指数退避（1s→2s→4s），最多 3 次 |
| MCP 断线 | 重建连接并重试 1 次 |
| 消息写入 | 重试 3 次 → 同步写 → 死信队列 |
| Refinement 卡住 | 定时扫描，CAS 重置 |
| 知识库批量 | 事务回滚 + 清理孤立文件 |

## 快速开始

### 环境要求

- Java 21+
- Maven 3.8+
- PostgreSQL + pgvector（RAG，可选）
- MySQL 8+（审计 + 会话记忆，可选）
- Redis（分布式缓存，可选）

### 构建 & 运行

```bash
# 构建
mvn clean package -DskipTests

# 配置
cp .env.example .env
# 编辑 .env，至少配置：
#   HARNESS_MODEL_CHAT_API_KEY
#   HARNESS_MODEL_CHAT_PROVIDER
#   HARNESS_MODEL_CHAT_BASE_URL
#   HARNESS_MODEL_CHAT_MODEL

# 启动（默认端口 8080）
java -jar harness-server/target/harness-server-${revision}.jar
```

启动后访问 Web UI，首次进入会引导你完成项目接口扫描。

### 环境变量分级

| 级别 | 变量 | 说明 |
|------|------|------|
| **必填** | `HARNESS_MODEL_CHAT_API_KEY` | 对话模型密钥 |
| **必填** | `HARNESS_MODEL_CHAT_BASE_URL` | API 地址（非 OpenAI 官方必须配置） |
| **必填** | `HARNESS_MODEL_CHAT_MODEL` | 模型名称（默认 gpt-4o） |
| 功能必填 | `HARNESS_SERVER_ENABLED` / `HARNESS_CLI_ENABLED` | 至少开一个 |
| 功能必填 | `HARNESS_AUTH_TOKEN` | auth_mode=token 时必填 |
| 功能必填 | `HARNESS_RAG_*` | 需要 RAG 知识库时必填 |
| 功能必填 | `HARNESS_AUDIT_DB_*` | 需要审计持久化时必填 |
| 功能必填 | `HARNESS_MODEL_EMBEDDING_*` | 需要知识库上传/检索时必填 |
| 可选 | 其余变量 | 有合理默认值或功能可关闭 |

完整变量列表见 [.env.example](.env.example)。

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/chat` | 发送消息（SSE 流式） |
| `DELETE` | `/api/chat/{sessionId}` | 取消进行中的请求 |
| `POST` | `/api/sessions` | 创建会话 |
| `GET` | `/api/sessions` | 列出会话（游标分页） |
| `GET` | `/api/sessions/{sessionId}/messages` | 消息历史 |
| `POST` | `/api/knowledge/upload` | 上传文档到知识库 |
| `POST` | `/api/project-discovery/scan` | 触发项目接口扫描 |
| `GET` | `/api/project-discovery/config` | 获取接口配置 |
| `PUT` | `/api/project-discovery/config` | 更新接口配置 |
| `POST` | `/api/project-discovery/reload` | 热加载接口配置 |
| `GET` | `/api/health` | 健康检查 |

### 对话请求示例

```json
{
  "text": "帮我查询所有在用设备",
  "context": {
    "outputMode": "streaming",
    "userId": "user-001",
    "enableThinking": true
  }
}
```

## 模块架构

```
harness-env        ← 环境变量 + 连接池
harness-core       ← 核心模型（AgentMessage、AgentTrace、ReActStep、ToolSpec 等）
├── harness-input       ← 认证 + 多模态解析 + 大文件处理
├── harness-preprocess  ← RAG + 查询改写 + 语义上下文 + 记忆管理
├── harness-tool        ← 工具接口 + MCP 适配 + Skill 加载 + 代码发现工具
├── harness-audit       ← Trace 采集与存储
└── harness-ai          ← LangChain4j + 7 种模型 + ReAct 引擎
harness-agent      ← 编排器 + 子代理 + 项目接口发现
harness-server     ← HTTP API + Web UI
```

## 扩展指南

### 添加 LLM Provider

1. 在 `com.harness.ai.model.impl` 实现对应 Provider 接口
2. 在 `ModelProviderFactory` 中注册
3. 在 `EnvKey.java` 中添加环境变量键

### 添加工具

1. 实现 `com.harness.tool.Tool`（`spec()` + `execute()`）
2. 在 `AgentOrchestrator.registerBuiltinTools()` 注册，或通过 MCP 自动发现

### 添加向量存储后端

1. 实现 `VectorStore`（`retrieve()` + `insertBatch()` + `deleteByFile()`）
2. 在 `VectorStoreFactory` 注册
3. 设置 `HARNESS_RAG_PROVIDER=your-backend`

## 技术栈

Java 21 · LangChain4j 1.15.0 · Javalin 6.1.3 · PostgreSQL pgvector · MySQL · Redis · Jackson · OkHttp · Apache POI + PDFBox · dotenv-java

## 许可证

Apache License 2.0
