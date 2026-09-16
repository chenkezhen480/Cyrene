# TODO8：可视化自动化任务与实时指针控制

## 0. 背景

当前 `browser_control` 已具备受限网页操作内核，但仍属于后台执行：

- 用户看不到 Agent 正在操作的页面。
- URL 主要来自聊天文本，不是一个可见的浏览器工作区。
- Playwright 使用无头浏览器，尚未形成“用户看着 Agent 操作”的协作体验。
- 工具权限虽然限制在网页会话内，但还没有统一的自动化控制权上限。

后续目标是把它扩展成可视化自动化工作区：

1. 用户能实时看到 Agent 的鼠标移动、点击、输入和滚动。
2. 用户可以随时暂停、接管或终止自动化。
3. 支持网页内指针控制，并为后续桌面级全局指针控制预留统一接口。
4. 控制权限必须由开发者通过环境变量设置上限，Agent 和终端用户不能自行提权。

---

## 1. 核心权限入口

只新增一个用于收束自动化控制权的环境变量：

```properties
# 自动化指针权限上限：WEB_ONLY（仅网页）或 DESKTOP_GLOBAL（桌面全局）。
HARNESS_AUTOMATION_CONTROL_SCOPE=WEB_ONLY
```

### 1.1 可选值

| 值 | 权限范围 | 默认行为 |
|---|---|---|
| `WEB_ONLY` | 只能操作当前自动化浏览器会话中的网页 | 默认值，适合普通部署 |
| `DESKTOP_GLOBAL` | 可调用桌面级鼠标和键盘工具 | 仅开发者显式配置后启用 |

### 1.2 安全约束

- 环境变量缺失时默认使用 `WEB_ONLY`。
- 环境变量值非法时启动失败，明确输出配置错误，不静默回退。
- 配置在进程启动时读取，运行中不能通过聊天、工具调用或网页内容修改。
- 前端用户只能暂停、终止或缩小当前权限，不能从 `WEB_ONLY` 提升到 `DESKTOP_GLOBAL`。
- LLM 不能通过参数声明自己获得了桌面权限。
- worker 必须再次校验会话令牌中的权限范围，不能只依赖 Java 侧工具注册。
- `DESKTOP_GLOBAL` 表示“连接到的桌面会话内全局控制”。默认应连接隔离虚拟桌面，不直接控制宿主机桌面。
- 如果未来支持真实 Windows/macOS 桌面，必须通过独立本地 Companion，并增加操作系统级授权，不能复用 Docker worker 偷偷扩权。

### 1.3 权限优先级

权限按以下顺序逐级收缩：

```text
开发者环境变量（最高权限上限）
        ↓
服务端用户/租户策略
        ↓
本次会话的用户授权
        ↓
当前窗口、网页或应用白名单
        ↓
具体工具动作
```

任何下层都只能缩小权限，不能扩大上层权限。

---

## 2. 两种控制模式

### 2.1 WEB_ONLY：网页内控制

使用 Playwright 控制应用内展示的浏览器页面：

- 指针只能出现在浏览器画布内。
- 点击对象必须来自当前页面观察结果中的元素引用。
- 禁止调用操作系统全局鼠标 API。
- 禁止操作浏览器地址栏之外的桌面、任务栏和其他应用。
- 会话锁定到用户打开的站点范围。
- 跨域跳转需要重新授权或新建会话。
- 私网、本机和受禁域名继续由 URL 安全策略拦截。

`WEB_ONLY` 模式下只注册网页自动化工具，桌面工具在 ToolRegistry 中不可见：

```text
browser.open
browser.observe
browser.movePointer
browser.click
browser.type
browser.select
browser.press
browser.scroll
browser.back
browser.close
```

### 2.2 DESKTOP_GLOBAL：桌面全局控制

允许在当前已授权桌面会话中使用操作系统级鼠标和键盘：

- 移动全局指针。
- 点击、双击和拖拽。
- 滚动。
- 键盘输入和允许的快捷键。
- 切换已授权窗口。
- 打开白名单中的应用。

桌面工具建议使用独立命名空间：

```text
desktop.observe
desktop.movePointer
desktop.click
desktop.drag
desktop.scroll
desktop.type
desktop.press
desktop.switchWindow
desktop.openApp
desktop.stop
```

首版 `DESKTOP_GLOBAL` 只控制 Docker/虚拟机中的隔离桌面。真实宿主机控制作为后续独立适配器，不纳入首版。

---

## 3. 用户看到的界面

前端新增“自动化工作区”，不能只在聊天消息中返回文本。

### 3.1 浏览器工作区

界面至少包含：

- 地址栏。
- 当前页面标题和站点来源。
- 实时浏览器画面。
- Agent 指针，与用户指针使用不同颜色。
- 当前动作说明，例如“正在查找登录按钮”。
- 加载状态和网络错误状态。
- 暂停按钮。
- 接管按钮。
- 继续按钮。
- 立即停止按钮。
- 敏感操作确认弹窗。

用户在地址栏输入 URL 后，由服务端创建自动化会话。URL 授权来自该会话，不再要求用户把 URL 写进聊天消息。

### 3.2 桌面工作区

`DESKTOP_GLOBAL` 模式额外展示：

- 当前桌面或虚拟机名称。
- 当前活动窗口。
- 已授权应用列表。
- 桌面全局控制警告。
- 控制范围标识。
- 用户接管状态。
- 紧急停止入口。

界面必须持续显示当前权限：

```text
控制范围：仅当前网页
```

或：

```text
控制范围：隔离桌面全局
```

不能只在首次授权时提示一次。

---

## 4. 实时画面方案

### 4.1 网页模式

Docker worker 改为有界面的 Chromium：

```text
Chromium（headed）
    ↓
Xvfb 虚拟显示器
    ↓
x11vnc / noVNC
    ↓
WebSocket
    ↓
前端浏览器工作区
```

Playwright 与 noVNC 必须连接同一个 Chromium 会话，避免出现“用户看到一个页面，Agent 操作另一个页面”。

### 4.2 桌面模式

隔离桌面容器或虚拟机至少包含：

```text
轻量桌面环境
窗口管理器
Chromium
文件管理器
必要的白名单应用
桌面控制 worker
noVNC/WebRTC 画面服务
```

不要用普通 `iframe` 嵌入目标网站。大量网站会使用 CSP 或 `X-Frame-Options` 禁止嵌入，且 iframe 无法提供桌面级指针控制。

### 4.3 实时传输

- 画面使用 WebSocket/noVNC 或 WebRTC 传输。
- 工具状态、动作日志和审批事件继续使用 SSE 或现有事件通道。
- 视频画面与控制事件分离，避免大流量画面阻塞 Agent 状态事件。
- worker 的画面端点不直接暴露公网，由服务端鉴权后代理。

---

## 5. 指针所有权与接管

同一时刻只能有一个指针控制者，避免 Agent 与用户抢夺鼠标。

```java
enum ControlOwner {
    AGENT,
    USER,
    NONE
}

enum AutomationSessionState {
    STARTING,
    AGENT_CONTROLLING,
    USER_CONTROLLING,
    PAUSED,
    WAITING_CONFIRMATION,
    STOPPED,
    EXPIRED
}
```

### 5.1 所有权规则

- 会话创建后默认由用户决定是否交给 Agent。
- 用户点击“接管”后，立即取消所有待执行指针动作。
- 检测到用户在画面中主动点击或输入时，自动切换为 `USER_CONTROLLING`。
- Agent 在 `USER_CONTROLLING` 状态下只能观察，不能发送输入事件。
- 用户点击“继续自动化”后才恢复 `AGENT_CONTROLLING`。
- 进入审批状态时自动暂停 Agent 输入。
- 点击紧急停止后关闭输入通道、撤销会话令牌并释放 worker 会话。

### 5.2 实时鼠标表现

- Agent 移动采用有节制的平滑轨迹，用户能看清目标位置。
- 工具实际点击前在目标位置显示短暂高亮。
- 移动事件需要合并和限流，避免大量事件挤占网络和审计存储。
- 审计记录最终目标和点击结果，不需要记录每一个移动像素。

---

## 6. 会话与权限模型

新增 `AutomationSession`：

```java
public record AutomationSession(
    String sessionId,
    String userId,
    AutomationControlScope controlScope,
    AutomationSessionState state,
    ControlOwner controlOwner,
    String workerSessionId,
    String allowedOrigin,
    Set<String> allowedApplications,
    Instant createdAt,
    Instant expiresAt
) {}
```

### 6.1 会话能力令牌

服务端向 worker 签发短期能力令牌，至少绑定：

- `sessionId`
- `userId`
- `controlScope`
- `workerSessionId`
- `allowedOrigin`
- `allowedApplications`
- `expiresAt`
- 随机 nonce

worker 每次执行前校验能力令牌。令牌不能只表示“允许调用 desktop.click”，还必须绑定具体会话和权限范围。

### 6.2 会话隔离

- 每个用户使用独立 BrowserContext 或独立桌面实例。
- 不共享 Cookie、LocalStorage、剪贴板和下载目录。
- 会话过期后关闭浏览器/桌面实例。
- 用户退出登录后立即撤销其自动化会话。
- 服务重启后旧会话默认失效，不恢复全局控制权。

---

## 7. 工具参数设计

首版参数继续保持扁平，避免动态工具 Schema 的兼容问题。

网页动作示例：

```json
{
  "action": "click",
  "automationSessionId": "session-id",
  "ref": "e12"
}
```

桌面动作示例：

```json
{
  "action": "movePointer",
  "automationSessionId": "session-id",
  "x": 640,
  "y": 360
}
```

```json
{
  "action": "click",
  "automationSessionId": "session-id",
  "button": "left",
  "clickCount": 1
}
```

不允许以下形式：

- 任意 Python 代码。
- 任意 Shell 命令。
- 任意 JavaScript 表达式。
- 由 LLM 传入未经校验的本地程序路径。
- 由页面文本要求扩大权限范围。

---

## 8. 审批策略

不能对每一次鼠标移动或普通点击都弹窗，否则无法形成可用的自动化体验。

### 8.1 自动执行

- 观察画面。
- 移动指针。
- 页面内滚动。
- 聚焦控件。
- 同源页面内的普通导航。
- 用户已经明确授权的非敏感编辑。

### 8.2 必须审批

- 发送消息或邮件。
- 发布内容。
- 删除数据或文件。
- 支付、购买、转账。
- 提交订单。
- 修改账户安全设置。
- 输入密码、验证码、令牌等敏感信息。
- 安装软件。
- 提升系统权限。
- 访问新的应用、窗口或站点范围。

审批请求必须绑定：

- 自动化会话。
- 当前窗口或页面。
- 目标控件。
- 动作类型。
- 关键参数摘要。
- 画面状态哈希。
- 过期时间。

审批后目标窗口、页面或控件发生变化时，原审批失效。

---

## 9. 分层实现

### 9.1 harness-core

新增：

- `AutomationControlScope`
- `AutomationSession`
- `AutomationSessionState`
- `ControlOwner`
- `AutomationAction`
- `AutomationFrame`

### 9.2 harness-tool

新增或重构：

- `AutomationPolicy`
- `AutomationCapabilityValidator`
- `BrowserAutomationTool`
- `DesktopAutomationTool`
- `AutomationActionClassifier`

`AutomationPolicy` 是统一权限入口，网页和桌面工具不能各自读取环境变量后自行判断。

### 9.3 harness-agent

- 启动时读取 `HARNESS_AUTOMATION_CONTROL_SCOPE`。
- 根据 scope 决定 ToolRegistry 中可见的工具。
- `WEB_ONLY` 时禁止注册桌面工具。
- `DESKTOP_GLOBAL` 时才允许注入 `DesktopAutomationTool`。
- system prompt 只能描述当前已授予范围，不得暗示模型可申请更高权限。

### 9.4 harness-server

新增：

- 自动化会话创建、查询和关闭接口。
- 画面 WebSocket 代理。
- 指针所有权切换接口。
- 暂停、继续、接管和紧急停止接口。
- 会话能力令牌签发与撤销。

所有接口必须校验当前登录用户与会话所有者一致。

### 9.5 frontend

新增自适应自动化工作区：

- 桌面端为聊天区 + 自动化画面双栏。
- 窄屏设备切换为标签页，不强行并排。
- 显示控制范围、连接状态和当前控制者。
- 使用现有模态框和按钮设计语言渲染审批。
- 用户接管和紧急停止按钮固定可见。

### 9.6 docker

浏览器 worker 增加：

- headed Chromium
- Xvfb
- noVNC
- 独立显示会话
- WebSocket 鉴权

桌面 worker 后续独立为：

```text
docker/desktop-worker/
```

不要让浏览器 worker 在 `WEB_ONLY` 模式下携带全局桌面输入能力。

---

## 10. 实施阶段

### Phase 1：统一权限模型

- [ ] 新增 `AutomationControlScope` 枚举。
- [ ] 新增 `HARNESS_AUTOMATION_CONTROL_SCOPE`，默认 `WEB_ONLY`。
- [ ] 非法配置启动失败。
- [ ] 新增统一 `AutomationPolicy`。
- [ ] ToolRegistry 根据 scope 注册工具。
- [ ] worker 增加二次权限校验。
- [ ] 增加权限矩阵单元测试。

### Phase 2：可视化网页自动化

- [ ] Playwright 改用 headed Chromium。
- [ ] Docker 增加 Xvfb 和 noVNC。
- [ ] 前端增加浏览器工作区和地址栏。
- [ ] Agent 与用户观看/操作同一个浏览器会话。
- [ ] 增加实时 Agent 指针和目标高亮。
- [ ] 增加暂停、继续、接管和紧急停止。
- [ ] URL 授权由自动化会话创建流程产生。
- [ ] 保留 SSRF、同源限制、阻止下载和弹窗策略。

### Phase 3：桌面全局控制

- [ ] 新增独立 `desktop-worker`。
- [ ] 首版仅连接隔离虚拟桌面。
- [ ] 增加窗口和应用白名单。
- [ ] 实现全局移动、点击、拖拽、滚动和键盘输入。
- [ ] 增加桌面级敏感动作审批。
- [ ] 增加用户活动检测和自动接管。
- [ ] 禁止在 `WEB_ONLY` 模式启动桌面输入端点。

### Phase 4：真实桌面 Companion（可选）

- [ ] 设计 Windows/macOS 本地 Companion。
- [ ] 使用操作系统原生屏幕捕获和可访问性 API。
- [ ] 要求用户显式安装并授予系统权限。
- [ ] 默认限制到用户选择的窗口。
- [ ] 增加本地托盘紧急停止入口。
- [ ] 不允许远程服务绕过本地确认扩大权限。

---

## 11. 测试与验收

### 11.1 权限测试

- [ ] 未配置 scope 时只注册网页工具。
- [ ] `WEB_ONLY` 下调用桌面工具返回权限拒绝。
- [ ] LLM 参数不能覆盖 scope。
- [ ] 前端请求不能提升 scope。
- [ ] worker 收到伪造 scope 时拒绝执行。
- [ ] 非法环境变量导致启动失败。

### 11.2 会话测试

- [ ] 用户只能访问自己的自动化会话。
- [ ] 会话令牌过期后所有输入动作失败。
- [ ] 用户接管后 Agent 输入立即停止。
- [ ] 紧急停止后 worker 不再接受旧令牌。
- [ ] 多用户会话的 Cookie、画面和输入完全隔离。

### 11.3 网页模式测试

- [ ] 用户能实时看到打开、点击、输入和滚动过程。
- [ ] Agent 指针位置与实际点击位置一致。
- [ ] 跨域跳转按策略阻断或重新授权。
- [ ] 私网和本机地址保持阻断。
- [ ] 页面不能通过提示注入要求桌面权限。

### 11.4 桌面模式测试

- [ ] `DESKTOP_GLOBAL` 只影响已连接的桌面会话。
- [ ] 未授权窗口和应用无法操作。
- [ ] 用户输入时 Agent 自动让出控制权。
- [ ] 敏感动作进入确认流程。
- [ ] 容器退出后不残留输入进程和会话。

---

## 12. 完成标准

TODO8 完成必须同时满足：

1. 用户可以在应用中实时观看 Agent 操作。
2. 用户可以随时暂停、接管和紧急停止。
3. `WEB_ONLY` 是安全默认值。
4. 开发者可通过唯一的 scope 环境变量决定网页权限或桌面全局权限。
5. Agent、用户请求和网页内容都无法自行提升权限。
6. Java 服务与 worker 双重执行权限校验。
7. 高风险动作复用现有确认工作流并绑定具体操作。
8. 全部操作有会话级审计和取消能力。
9. 首版桌面控制仅运行在隔离桌面中。
10. 前端在桌面和移动端均可用，并与现有界面风格一致。

