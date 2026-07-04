# TODO5：项目接口发现与对接（Project API Discovery）

## 0. 背景与目标

Harness 定位是面向 AI 应用开发的通用框架。一款有特色的 AI 应用往往需要对接宿主项目/兄弟项目的内部接口，而不只是外部工具（web search、MCP）。本次新增一套「发现内部接口 → 生成声明式配置 → 人工确认 → 运行时调用」的完整链路，让 Agent 具备对接内部系统的能力，同时不引入不必要的复杂度。

**核心设计原则（贯穿全文档，来自前期讨论的多轮取舍）：**

1. 启动流程永不阻塞——不做控制台交互，不做 GUI 弹窗，不依赖谁能摸到终端/屏幕。
2. 发现结果需经人工确认才能生效，不允许 LLM 扫描即生效。人工确认方式为：扫描完成后展示结果，用户确认后生成配置文件；后续维护直接编辑 JSON 文件。
3. 产出物是声明式 JSON 配置 + 通用执行器，不生成真实 MCP server——同进程内调用没有协议要跨的边界。
4. 优先用户身份透传（跟随调用者权限做数据筛选），bot 身份作为兜底。
5. 代码检索能力仅在发现任务中临时存在，不进入主 Agent 常驻工具集；发现后不再自动接触代码库，仅显式触发重新扫描。
6. 凭证隔离——sub-agent 调用外部工具时必须隔离 credentials，防止意外泄露。

---

## 1. 范围与非目标

**本次范围：**
- 有 OpenAPI/Swagger spec 的项目：确定性解析。
- 无 spec 的项目：专属 sub-agent + 通用代码检索原语（glob/grep/read）兜底扫描。
- 声明式接口配置的生成、审核、发布、运行时调用。
- 两种鉴权模式（bot / user_passthrough）及其凭证透传机制。

**明确不做（Non-goals）：**
- 不做真实 MCP server 生成（理由见 §7 末尾说明）。
- 不做控制台交互向导、不做 Java GUI 弹窗、不恢复 CLI。
- 不做凭证的服务端加密存储或 OAuth token exchange（MVP 只做请求级透传）。
- 不做自动化的定时/文件监听式重新扫描。
- 不做 UI 自动化/RPA（操作没有 API 的内部系统的网页）——风险模型完全不同，留作独立方向，不并入本次。

---

## 2. 触发方式：Web UI + 环境变量开关

### 2.1 环境变量

```bash
HARNESS_PROJECT_DISCOVERY_ENABLED=true     # 功能总开关，默认 true
```

功能开关决定：
1. 接口发现功能是否可用（Web UI + 扫描端点）
2. 发现工具（`code_glob`、`code_grep`、`read_class_hierarchy`）是否注册到主 `ToolRegistry`，可在普通对话中使用

Web UI 的自动打开逻辑见 §2.2。

### 2.2 启动流程（首次启动引导）

Web UI 是面向开发者的完整工作台，包含对话、知识库管理、审计管理、配置更改等功能。接口发现是首次启动时的前置配置流程。

**首次启动检测逻辑：**

```java
// Main.java，Javalin app.start() 成功返回之后
app.start(port);
log.info("Server started on port {}", port);

// 首次启动引导：检测是否已配置过项目接口
if (cfg.getBoolean(EnvKey.PROJECT_DISCOVERY_ENABLED, true)) {
    Path apisConfig = Path.of(cfg.getString(EnvKey.PROJECT_APIS_CONFIG_FILE, "./project-apis.json"));
    if (!Files.exists(apisConfig) && isLocalEnvironment()) {
        // 首次启动，没有配置文件，且是本地开发环境，尝试引导
        tryOpenBrowser("http://localhost:" + port);
    }
}

private static boolean isLocalEnvironment() {
    // 检测是否本地开发环境（非容器/K8s）
    // 1. 检查 Desktop 是否可用
    // 2. 检查是否在容器中（/.dockerenv 文件、KUBERNETES_SERVICE_HOST 环境变量等）
    return Desktop.isDesktopSupported()
        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        && !Files.exists(Path.of("/.dockerenv"))
        && System.getenv("KUBERNETES_SERVICE_HOST") == null;
}

private static void tryOpenBrowser(String url) {
    try {
        Desktop.getDesktop().browse(new URI(url));
    } catch (Exception e) {
        // 任何异常只记日志，绝不影响服务本身
        log.debug("Auto-open browser failed (non-fatal): {}", e.getMessage());
    }
}
```

**关键约束：**
- 弹浏览器必须在端口监听成功**之后**执行
- 任何失败只降级为 debug 日志，不得影响服务启动结果
- 容器/K8s 环境下不会弹出（`isLocalEnvironment()` 返回 false）
- 已有配置文件后不再弹出（`Files.exists(apisConfig)` 返回 true）

### 2.3 Web UI 结构

不引入前端构建工具链。页面为不带框架的静态 HTML + 原生 JS，由 Javalin 直接托管为静态资源。

**整体布局：**
```
┌─────────────────────────────────────────────────────────────┐
│  Harness Agent                                    [用户信息] │
├──────────┬──────────────────────────────────────────────────┤
│          │                                                  │
│  侧边栏   │                   主内容区                        │
│          │                                                  │
│  • 对话   │   对话界面 / 知识库管理 / 审计管理 / 配置更改       │
│  • 知识库  │                                                  │
│  • 审计   │                                                  │
│  • 配置   │                                                  │
│          │                                                  │
└──────────┴──────────────────────────────────────────────────┘
```

**页面路由：**
- `/` — 主入口，检测是否需要前置配置
- `/chat` — 对话界面（支持文件上传、语音输入）
- `/knowledge` — 知识库管理
- `/audit` — 审计管理
- `/config` — 配置更改（展示 project-apis.json 可编辑内容）

**前置配置流程（首次进入时）：**

```
用户访问 /chat
  ↓
检测 project-apis.json 是否存在
  ↓
不存在 → 弹出前置配置界面（模态框或独立页面）
  ↓
用户输入本地项目目录 → 点击"开始扫描"
  ↓
同步扫描（显示 loading 提示"首次扫描需要时间，请耐心等待"）
  ↓
扫描完成 → 展示扫描结果（接口列表）
  ↓
用户确认 → 生成 project-apis.json → 进入对话界面
用户取消 → 进入对话界面，但下次进入还会弹出前置配置
```

**配置更改页面：**
- 展示 project-apis.json 的完整内容（可编辑）
- 提供"保存"按钮（调用 API 更新文件）
- 提供"重新扫描"按钮（触发新的发现流程）
- 文件路径提示（方便开发者用编辑器直接打开修改）

页面路由需要认证（复用现有 JWT 机制），不得在无鉴权情况下暴露——触发扫描和配置修改都是会改变 Agent 实际权限范围的操作，不是普通查询接口。

### 2.4 Web UI 设计风格

设计灵感源自「昔涟 Cyrene」角色美学。她贯穿了漫长的时间循环，始终立于世界的"背面"，用自己的方式记录他人的旅程——不被人捧起，只是在历史循环的角落中默默付出，乃至最后以留在过去的牺牲换取他人奔向明天的黎明。

**情感基调：三重交织**
- **粉色少女的活泼感** — 她标志性的句尾"♪"，柔和灵动，不冷硬
- **史诗叙述的沉甸感** — 古希腊神话、大理石、命运与神谕的意象
- **角落英雄的忧伤感** — 默默记录、无人见证、留在过去换他人明天

忧伤感不是悲痛，而是一种安静的、被接受的牺牲——像涟漪散去后水面归于平静，像记忆中褪色但依然温暖的光。

**配色方案（Design Tokens）：**

| 角色 | 色值 | 命名 | 说明 |
|------|------|------|------|
| 深渊底色 | `#0d0915` | `--abyss` | 主背景，比纯黑多一丝紫，像黎明前最深的夜 |
| 卡片背景 | `#1a1228` | `--surface` | 容器/卡片，带玻璃态效果 |
| 柔粉强调 | `#e8a0bf` | `--rose` | 交互高亮、按钮、重点信息 |
| 暮色粉 | `#b07a9a` | `--dusk` | **忧伤感载体**——比 rose 更暗、更灰，用于次要状态、已读消息、历史记录，像褪色的记忆 |
| 香槟金 | `#c9a96e` | `--gold` | 分割线、图标描边，呼应"黄金裔"意象 |
| 冰晶蓝紫 | `#8b7ec8` | `--iris` | 辅助强调、链接 |
| 云雾白 | `#e8e2f0` | `--mist` | 主文字色 |
| 次要文字 | `#6a6080` | `--ash` | 次要信息、占位符，比常规灰色更偏紫，带一丝冷意 |

**字体方案：**
- **Display**：`Playfair Display`（衬线字体）— 标题、Logo，传递史诗叙事感。斜体用于引言、诗意文案，呼应她"记录者"的身份
- **Body**：`Inter`（现代无衬线）— 正文、UI 元素，保证可读性与轻盈感

**视觉母题（签名元素）：**

- **涟漪**：同心圆扩散动效，用于页面切换、加载状态。关键细节——涟漪在扩散到边缘时不是突然消失，而是**渐隐消散**，像记忆在时间中淡去
- **新月**：弧形几何装饰元素，细线勾勒的月牙图标。新月是"不完整的圆"——呼应她未能收获圆满的宿命
- **星点**：低密度星点缀，营造深空感。部分星点设计为**微微闪烁后渐暗**，像正在熄灭的光
- **褪色花瓣**：在空状态页面（无对话、无数据）使用，几片半透明花瓣缓缓飘落又消散——她留下的痕迹

**交互规范：**
- 页面切换/加载：同心圆涟漪扩散动画，边缘渐隐消散
- 强调反馈：柔光呼吸效果（渐隐渐显，避免生硬闪烁）
- 卡片进入：轻微渐变位移 + 透明度过渡，模拟"薄纱飘动"
- 按钮 hover：柔粉光晕扩散 + 微妙上移
- 空状态：褪色花瓣飘落动效 + 诗意提示文案（见下）
- 历史/已完成状态：使用 `--dusk` 色调，视觉上"退一步"，暗示已成为过去的记忆

**空状态文案（体现忧伤感）：**
- 对话为空："涟漪尚未荡起，等待第一个音符♪"
- 知识库为空："记忆的种子尚未播下"
- 审计为空："旅途尚未开始，无痕可寻"
- 配置未生成："她还在等待——在世界的背面，等你迈出第一步"

**布局规范：**
- 卡片/容器使用柔和圆角（`border-radius: 12px`），避免锐利直角
- 背景叠加低透明度水波纹理或大理石纹理
- 关键区域用金色细线勾边，强化"神圣感"而不显厚重
- 充足留白，呼应角色气质中的"空灵感"
- 侧边栏底部可放置一行小字，如"在时间的涟漪中"作为品牌签名

**参考资源：** `skills/昔涟Cyrene_风格与背景设计资料.md`

---

## 3. 接口发现流程

### 3.1 OpenAPI 优先

发现任务启动时，先按约定路径/常见位置探测目标项目是否已有 `openapi.json` / `swagger.yaml` 等 spec 文件。若存在，直接确定性解析，不调用 LLM，不产生幻觉风险，标记 `source: "openapi"`。这是首选路径。

### 3.2 专属发现 Sub-Agent（无 spec 时兜底）

无 spec 时，启动一个**专属、任务级**的发现流程，产出草稿标记为 `source: "code_scan"`。

**实现要点：** `ProjectDiscoveryService` 构建一个**独立的、仅包含发现三工具的临时 `ToolRegistry`**（`code_glob` + `code_grep` + `read_class_hierarchy`），配一个独立的 `ReActEngine` 实例来跑这次任务，任务结束后整个丢弃，不注册进主注册表。发现流程使用全局 `maxIterations` 设置，不单独限制。

**LLM 引导式工作流（4 步）：**
1. `code_glob("**/*Controller.java")` — 定位所有控制器文件
2. `code_grep(regex="@GetMapping|@PostMapping|...", glob="**/*Controller.java")` — 搜索所有路由注解（返回 ±7 行上下文，无匹配数限制）
3. 从 grep 结果中提取 DTO/VO 类名，对每个类调用 `read_class_hierarchy` 获取完整字段结构（含继承父类字段 + JSON Schema）
4. 整合输出结构化接口定义

### 3.3 检索工具（glob / grep / read_class_hierarchy）

不为每种框架（Spring/Express/Flask...）单独写解析器，而是给 LLM 一套通用原语，由 LLM 自己判断"这是不是一个路由注册"：

| 工具 | 作用 |
|---|---|
| `CodeGlobTool` | 按文件名/扩展名模式定位候选文件，返回最多 50 个匹配路径 |
| `CodeGrepTool` | 按正则搜索文件内容，返回**所有匹配**（无数量限制），每个匹配 ±7 行上下文 |
| `ReadClassHierarchyTool` | 读取类及其父类（最多 2 层），返回合并字段列表 + 紧凑 JSON Schema。支持 Java/C#/C++/Python/JS/TS/PHP/Rust/Go。自动检测 `.git` 定位仓库根目录，支持跨模块父类查找 |

~~`CodeReadTool`~~ — 已移除，功能被 `code_grep`（±7 行上下文）和 `read_class_hierarchy`（完整类结构）替代。

具体框架识别逻辑（认出这是 Spring 还是 Express）留给 LLM 的推理能力，不写死在工具代码里。

### 3.4 路径边界

- 根目录即用户显式提供的路径；工具内部做路径规范化 + 边界检查，拒绝任何 `..` 或符号链接逃逸到根目录之外。
- `ClassHierarchyReader` 自动向上查找 `.git` 目录定位仓库根目录，支持跨模块父类查找（如 `ruoyi-modules/zhiduyuan` 中的类继承自 `ruoyi-common` 中的 `BaseEntity`）。

### 3.5 生命周期

发现任务使用全局 `maxIterations` 设置，不单独限制工具调用次数或超时。任务完成后结果直接返回，不保留中间状态。

---

## 4. 声明式配置产出（project-apis.json）

### 4.1 Schema

选用 JSON（而非 YAML/MCP 协议包装），理由：与现有 `HARNESS_MCP_CONFIG_FILE` 的 JSON 约定一致；LLM 输出结构化 JSON 的可靠性高于 YAML；参数本就用 JSON Schema 描述，与 `ToolSpec.spec()` 现有惯例一致；Web 审核页面用原生 `JSON.parse`/`stringify` 即可双向读写。

```json
{
  "discoveredAt": "2026-07-03T10:00:00Z",
  "sourceRoot": "/path/to/sibling-project",
  "endpoints": [
    {
      "id": "ep_0001",
      "name": "getOrderDetail",
      "description": "查询订单详情",
      "method": "GET",
      "path": "/internal/orders/{id}",
      "baseUrl": "http://order-service:8080",
      "source": "code_scan",
      "authMode": "user_passthrough",
      "credentialKey": "orderService",
      "tokenInjection": { "location": "header", "name": "Authorization", "prefix": "Bearer " },
      "parameters": { "...": "JSON Schema" },
      "confirmed": false,
      "riskAcknowledged": false
    }
  ]
}
```

### 4.2 字段说明

| 字段 | 说明 |
|---|---|
| `source` | `openapi` \| `code_scan`，审核页面据此标注置信度（code_scan 提示"AI 生成，请核对"） |
| `authMode` | `bot` \| `user_passthrough`，**人工审核时必填，不由 LLM 自动填**（业务语义只有人知道） |
| `credentialKey` | 指向 `AgentContext.credentials` 里的 key（见 §5.2） |
| `tokenInjection` | 描述 token 注入位置（header/query/cookie），执行器据此拼装请求，纯确定性逻辑 |
| `confirmed` | 唯一决定该条目是否真正生效的开关（见 §6.3） |
| `riskAcknowledged` | 高风险组合（非 GET + bot 模式）的强制二次确认标记（见 §6.2） |

**JSON Schema 输出优化：** `ReadClassHierarchyTool` 生成的 JSON Schema 采用紧凑格式（无 `x-sourceType` 注解、无 pretty-print 缩进），减少 token 消耗。类型映射信息已在字段列表中展示，不在 Schema 中重复。

---

## 5. 鉴权模式

### 5.1 bot vs user_passthrough

- **bot**：框架持有服务级凭证，所有调用者共用同一身份。仅用于目标系统本身没有"代表某用户调用"这个概念的纯服务间接口。
- **user_passthrough**（优先选择）：跟随触发这次请求的实际用户身份调用，内部系统自己的权限逻辑照常生效，天然带来按身份的数据筛选——不需要 Agent 重新实现一遍"谁能看什么"。

`impersonationSupported` 需在审核阶段人工确认（LLM 读代码判断不出整条认证链路是否真支持透传）。

### 5.2 Credential 透传（AgentContext.credentials）

多数系统的客户端本就在每次请求时持有目标系统的 token，直接透传即可，不需要 Harness 自建凭证存储/换取体系：

```json
// POST /api/chat 请求体
{
  "context": {
    "userId": "xxx",
    "credentials": {
      "orderService": "eyJhbGc...",
      "inventorySystem": "abc123token"
    }
  }
}
```

`HttpApiTool` 执行时按 `credentialKey` 从 `AgentContext.credentials` 取值，按 `tokenInjection` 拼进请求。**若 user_passthrough 端点在运行时找不到对应 `credentialKey`，直接判定为 `TOOL_ERROR`（缺少凭证），不得静默改用 bot 身份或跳过鉴权**——失败要显式失败（fail closed）。

Token 不落盘、不加密存储，生命周期仅限当次请求转发，MVP 阶段不做过期刷新——过期由下游 401 触发普通 `TOOL_ERROR`，走现有 Inspector 错误处理路径即可。

### 5.3 Trace 脱敏

`HttpApiTool` 记录 `ReActStep`/`AgentTrace.metadata` 时，必须对 `tokenInjection` 描述位置的值做 redact，只记录「使用了 `credentialKey=orderService`」，绝不记录 token 原文。这条约束需要在执行器实现的第一版就落地，不能后补——现有审计链路默认保留 30 天（`HARNESS_AUDIT_RETENTION_DAYS`），一旦记了原文，等同把用户在别处系统的活体凭证存进了 Harness 自己的数据库。

### 5.4 Sub-Agent 身份传播与凭证隔离

**身份传播：**
`SubAgentOrchestrator` 派生的子代理必须继承父任务 `AgentContext` 里的 `credentials`，不得另起 bot 身份调用同一接口——否则子代理反而拥有比父任务更大的权限，是容易被忽略的越权路径。

**凭证隔离：**
sub-agent 调用外部工具（如 `web_search`、MCP 工具等非 HttpApiTool）时，必须**清空 credentials**，防止内部系统的凭证意外泄露到外部服务。具体实现：

```java
// SubAgentOrchestrator 派生子任务时
AgentContext subAgentContext = parentContext.clone();
subAgentContext.setCredentials(new HashMap<>());  // 清空，子代理按需从父任务继承

// HttpApiTool 执行时，从 AgentContext.credentials 取值
// 其他工具执行时，credentials 为空，无法访问内部系统凭证
```

**隔离边界：**
- `HttpApiTool`：可以访问 `AgentContext.credentials[credentialKey]`
- 其他所有工具（web_search、MCP、builtin 等）：`AgentContext.credentials` 为空，无法访问内部凭证
- 这样确保内部系统的 token 只在调用内部接口时使用，不会泄露到外部服务

---

## 6. 确认与生效流程

### 6.1 扫描结果展示

扫描完成后，在前置配置界面展示发现的接口列表：

| 字段 | 展示内容 |
|---|---|
| 来源 | `openapi`（高置信度）/ `code_scan`（AI 生成，请核对） |
| 接口名 | `name` 字段 |
| 方法+路径 | `method` + `path` |
| 描述 | `description` 字段 |

用户可以：
- **确认生成**：将扫描结果写入 `project-apis.json`，所有条目默认 `confirmed: true`
- **取消**：不生成配置文件，进入对话界面（下次进入还会弹出前置配置）

### 6.2 后续维护方式

配置文件生成后，后续维护**直接编辑 JSON 文件**，不在 Web UI 上做复杂审核：
- Web UI 的"配置更改"页面展示可编辑的 JSON 内容
- 开发者也可以用编辑器直接打开 `project-apis.json` 修改
- 修改后需要重启服务或调用热加载 API 生效

### 6.3 confirmed 字段与生效机制

`HttpApiTool` 启动时加载 `project-apis.json`，**只注册 `confirmed: true` 的条目**进 `ToolRegistry`；未确认的条目留在文件里但不生效。

热加载支持：修改配置文件后，调用 `POST /api/project-discovery/reload` 端点，无需重启服务即可重新加载配置。`ToolRegistry` 的注册/反注册操作需保证线程安全（可能与运行中的 ReAct 循环并发）。

---

## 7. 运行时执行：HttpApiTool

单一通用执行器，implements `com.harness.tool.Tool`，读配置动态执行 HTTP 调用，不为每个接口生成专属代码：

```java
public class HttpApiTool implements Tool {
    // 构造时绑定一条 ApiEndpoint 声明
    // execute(JsonNode args):
    //   1. 按 parameters schema 校验/组装请求
    //   2. 按 authMode 决定鉴权来源（bot: 配置的服务凭证 / user_passthrough: AgentContext.credentials[credentialKey]）
    //   3. 按 tokenInjection 拼装请求（header/query/cookie）
    //   4. 发起 HTTP 调用（复用现有 OkHttp 依赖）
    //   5. 记录 trace（凭证字段脱敏，见 §5.3）
}
```

这跟 `McpToolAdapter` 适配远程 MCP 工具、`LoadSkillTool` 按需加载 Skill 内容，本质上是同一套「运行时读配置、动态转工具」的模式。

**为什么不生成真实 MCP server：** MCP 协议解决的是"跨进程/跨团队边界的工具发现"问题——需要独立进程、JSON-RPC 握手。这里调用方（ReActEngine）和执行方（`HttpApiTool`）是同一 JVM 进程里的同一段代码，没有边界要跨；套协议只会平白多出一个真实进程要管理（启动、健康检查、崩溃重启），却没有换来任何收益。若未来确有需要把这些接口开放给外部 MCP client 消费，可以在 `HttpApiTool` 之上单独包一层反向适配，是独立的、按需再做的功能，不倒逼现在的实现。

---

## 8. 生命周期：静态快照，仅显式重新扫描

`project-apis.json` 是静态快照，不设任何自动重新扫描机制（无 cron、无文件监听、无"服务重启时顺便扫一下"）。

**发现是一次性的：**
- 首次启动时通过前置配置界面完成扫描，生成配置文件
- 后续维护直接编辑 JSON 文件，不需要重新扫描
- 配置文件一旦生成，不再自动触发扫描流程

**重新扫描的触发方式：**
- Web UI 配置更改页面提供"重新扫描"按钮（显式触发）
- 对话中用户明确表达意图后触发（复用同一条发现流程 §3）
- 重新扫描会覆盖现有配置文件，建议先备份

`discoveredAt` 字段只用于让页面诚实展示"这份配置有多旧"，不触发任何自动化判断；配置过期后的调用失败交给现有 `TOOL_ERROR` + Inspector 路径处理，不做专门的健康检查子系统。

---

## 9. 新增 / 修改文件

**新增文件（harness-core，模型）**
- `ApiEndpoint.java` — record，对应 §4.1 单条接口声明
- `ProjectApiConfig.java` — record，对应整份配置文件
- `AuthMode.java` — enum：BOT / USER_PASSTHROUGH
- `TokenInjection.java` — record：location / name / prefix

**新增文件（harness-tool）**
- `HttpApiTool.java` — 通用执行器（§7）
- `discovery/CodeGlobTool.java` — glob 模式文件搜索
- `discovery/CodeGrepTool.java` — 正则内容搜索（±7 行上下文，无匹配限制）
- `discovery/ReadClassHierarchyTool.java` — 读取类继承链 + 合并字段 + 生成紧凑 JSON Schema
- `discovery/ClassHierarchyReader.java` — 多语言类结构解析器（Java/C#/C++/Python/JS/TS/PHP/Rust/Go），支持跨模块父类查找（基于 `.git` 检测仓库根目录）
- `discovery/OpenApiSpecParser.java` — 确定性解析 openapi/swagger

**新增文件（harness-agent）**
- `ProjectDiscoveryService.java` — 编排发现任务：探测 spec → 无 spec 时构建独立 ToolRegistry + ReActEngine 跑发现三原语 → 产出草稿

**新增文件（harness-server）**
- `ProjectDiscoveryHandler.java` — Javalin handler，对应 §11 全部端点
- `resources/public/index.html` — 主入口页面（路由检测、前置配置引导）
- `resources/public/chat.html` — 对话界面（支持文件上传、语音输入）
- `resources/public/knowledge.html` — 知识库管理界面
- `resources/public/audit.html` — 审计管理界面
- `resources/public/config.html` — 配置更改界面（展示可编辑的 project-apis.json）
- `resources/public/css/style.css` — 全局样式
- `resources/public/js/app.js` — 主应用逻辑
- `resources/public/js/api.js` — API 调用封装

**修改文件**
- `EnvKey.java` — 新增 §10 所列环境变量
- `ToolRegistry.java` — 支持从 `project-apis.json` 加载 `confirmed=true` 条目 + 热加载/反注册的线程安全
- `AgentContext.java` — 新增 `credentials` 字段（Map），随 sub-agent 派生传播
- `SubAgentOrchestrator.java` — 派生子任务时继承父任务 `credentials` + 调用外部工具时清空 credentials
- `Main.java` — 启动成功后按 §2.2 逻辑检测首次启动并引导
- `.env.example` — 新增本节环境变量

---

## 10. 环境变量汇总

| 变量 | 默认值 | 说明 |
|---|---|---|
| `HARNESS_PROJECT_DISCOVERY_ENABLED` | `true` | 功能总开关（同时控制主工具注册表中的发现工具是否可用） |
| `HARNESS_PROJECT_APIS_CONFIG_FILE` | `./project-apis.json` | 声明式配置文件路径 |

已移除的变量：`HARNESS_PROJECT_DISCOVERY_MAX_TOOL_CALLS`、`HARNESS_PROJECT_DISCOVERY_TIMEOUT_MINUTES`、`HARNESS_PROJECT_DISCOVERY_EXCLUDE_PATTERNS`、`HARNESS_PROJECT_DISCOVERY_MAX_ITERATIONS`、`HARNESS_PROJECT_DISCOVERY_MAX_CONTEXT_CHARS`。发现任务使用全局设置，不单独限制。

---

## 11. HTTP 端点汇总

**项目接口发现端点：**

| Method | Path | 说明 |
|---|---|---|
| POST | `/api/project-discovery/scan` | 触发扫描，body: `{ sourceRoot }`，同步返回扫描结果 |
| POST | `/api/project-discovery/generate` | 将扫描结果写入 `project-apis.json`，body: `{ endpoints: [...] }` |
| GET | `/api/project-discovery/config` | 获取当前配置文件内容 |
| PUT | `/api/project-discovery/config` | 更新配置文件内容，body: 完整 JSON 内容 |
| POST | `/api/project-discovery/reload` | 热加载配置文件到 ToolRegistry（无需重启服务） |

**Web UI 页面（静态资源）：**

| Path | 说明 |
|---|---|
| `/` | 主入口，检测是否需要前置配置 |
| `/chat` | 对话界面 |
| `/knowledge` | 知识库管理界面 |
| `/audit` | 审计管理界面 |
| `/config` | 配置更改界面 |

所有端点复用现有 JWT 认证，不裸奔。

---

## 12. 实现顺序（Phase）

**Phase 1 — 配置模型 + 运行时执行器**
- `ApiEndpoint`/`ProjectApiConfig`/`AuthMode`/`TokenInjection` 模型
- `HttpApiTool` 执行器（含鉴权注入、trace 脱敏）
- `ToolRegistry` 支持加载 `confirmed=true` 条目 + 热加载
- `AgentContext.credentials` 字段
- 此阶段可先手写一份 `project-apis.json` 测试跑通，不依赖发现流程

**Phase 2 — 发现流程**
- `OpenApiSpecParser`（确定性解析）
- 发现三工具 `CodeGlobTool`/`CodeGrepTool`/`ReadClassHierarchyTool`（含路径边界检查）
- `ClassHierarchyReader`（多语言类结构解析，基于 `.git` 的跨模块父类查找）
- `ProjectDiscoveryService`（独立 ToolRegistry + ReActEngine，LLM 引导式 4 步工作流）

**Phase 3 — Server 端点 + Web UI**
- `ProjectDiscoveryHandler` 全部端点（扫描、生成、配置读写、热加载）
- Web UI 页面：
  - 主入口路由检测
  - 前置配置界面（扫描结果展示 + 确认生成）
  - 对话界面（基础框架，支持文件上传）
  - 配置更改界面（可编辑 JSON 内容）
- `Main.java` 首次启动检测逻辑

**Phase 4 — 凭证透传与隔离**
- `SubAgentOrchestrator` 身份传播 + 调用外部工具时清空 credentials
- 端到端联调（客户端传 token → HttpApiTool 注入 → trace 脱敏验证）
- 验证凭证隔离（sub-agent 调用外部工具时无法访问内部凭证）

Phase 1 与 Phase 2 可并行（互不依赖）；Phase 3 依赖 Phase 1+2 的产出；Phase 4 依赖 Phase 1 的执行器骨架但可与 Phase 2/3 并行推进。

---

## 13. 验证步骤

1. 无 spec 的示例项目跑一次发现，确认扫描结果正确标注 `source: code_scan`，且未越界读取根目录之外的文件。
2. 确认 `read_class_hierarchy` 能跨模块查找父类（如从 `ruoyi-modules/zhiduyuan` 找到 `ruoyi-common` 中的 `BaseEntity`）。
3. 首次启动时，确认本地环境下自动弹出前置配置界面；容器环境下不弹出。
4. 前置配置界面输入项目目录 → 同步扫描 → 展示结果 → 确认生成 → 确认 `project-apis.json` 文件正确生成。
5. 取消前置配置后进入对话界面，再次进入时确认前置配置界面仍然弹出（因为配置文件不存在）。
6. `authMode=user_passthrough` 的接口在请求 `context.credentials` 缺少对应 key 时，确认返回 `TOOL_ERROR` 而非降级为无鉴权调用。
7. 检查一次真实调用产生的 trace，确认 Authorization/token 值已脱敏，仅记录 `credentialKey`。
8. `confirmed=false` 的条目确认不会出现在 `ToolRegistry` 可调用工具列表中。
9. 容器化环境下确认服务仍能正常启动，不会尝试打开浏览器。
10. Sub-agent 派生任务下，确认其调用 `user_passthrough` 接口时使用的是父任务传下来的 credentials，而非另起身份。
11. Sub-agent 调用外部工具（如 `web_search`）时，确认 `AgentContext.credentials` 为空，无法访问内部凭证。
12. 配置更改页面展示可编辑的 JSON 内容，修改后调用热加载 API 确认配置生效。

---

## 14. 明确不做的事（重申）

- 不做 MCP 协议包装——同进程内没有边界要跨（详见 §7）。
- 不做控制台交互向导 / Java GUI 弹窗作为启动依赖——两者在容器/K8s/headless 环境下都会导致启动失败或行为不一致。
- 不恢复 CLI——发现触发走 Web UI，与框架现有交互方式（HTTP/SSE）保持同一套语言。
- 不做服务端凭证加密存储或 OAuth token exchange——MVP 阶段凭证只做请求级透传，避免把"内部接口发现"拖成一个身份联邦子项目。
- 不做自动/定时重新扫描——发现后不再接触代码库，除非用户显式触发。
- 不做 UI 自动化/RPA——风险模型（浏览器会话操作、prompt injection 攻击面）与结构化 API 调用完全不同，留作独立方向。
- 不做复杂的 Web UI 审核界面——配置维护直接编辑 JSON 文件，Web UI 只提供可编辑的展示，不做逐条审核、批量操作等复杂交互。
