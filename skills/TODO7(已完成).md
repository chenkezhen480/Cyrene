# TODO7: 文件生成功能 — 沙盒 + 独立工具 + Artifact 系统

## 背景

当前 Cyrene Agent 是纯文本输入/输出系统。需要扩展为支持生成任意文件（代码、Office、图片、视频）。

## 核心设计决策

### 工具拆分原则：沙盒 vs 独立工具

**判断标准：是否需要 Python 库执行**

| 场景 | 工具 | 原因 |
|------|------|------|
| Excel/Word/PPT 生成 | python_sandbox | 需要 openpyxl/python-docx/python-pptx |
| 图表/数据可视化 | python_sandbox | 需要 matplotlib |
| 代码文件生成 | python_sandbox | 文件写入 |
| 图片后处理 | python_sandbox | 需要 Pillow |
| AI 图片生成 | image_generation (独立) | DALL-E API 调用，不需要 Python |
| AI 视频生成 | video_generation (独立) | 外部 API + 异步回调 |

- **Python 沙盒 (Docker)** — 用于需要 Python 库的生成任务，`--network=none` 安全隔离
- **独立工具** — 用于 API 调用类任务，不进沙盒

### 视频异步方案：提交 + 后台回调

视频生成 API 通常是异步的（提交 → 轮询 → 下载），可能需要几分钟，无法用同步工具模型。

```
submit_video_generation → 返回 taskId + "正在生成中"
  ↓ 后台线程每 10s 轮询 API
完成 → 下载视频 → ArtifactCallback → SSE push artifact 事件
  ↓
前端收到 event: artifact → 渲染视频播放器
```

---

## 实施步骤

### Phase 1: Artifact 基础设施

#### 1.1 Artifact 模型
- **新增:** `harness-core/src/main/java/com/harness/core/model/Artifact.java`
```java
public record Artifact(
    String id,           // UUID
    String sessionId,
    String name,         // 文件名
    ArtifactType type,   // IMAGE, DOCUMENT, CODE, VIDEO, AUDIO, OTHER
    String mimeType,
    long sizeBytes,
    String filePath,     // 磁盘路径
    Instant createdAt,
    Instant expiresAt
) {
    public enum ArtifactType { IMAGE, DOCUMENT, CODE, VIDEO, AUDIO, OTHER }
    public String downloadUrl() { return "/api/artifacts/" + id; }
    public String previewUrl()  { return "/api/artifacts/" + id + "/preview"; }
}
```

#### 1.2 ArtifactStore 接口 + 文件系统实现
- **新增:** `harness-preprocess/src/main/java/com/harness/preprocess/artifact/ArtifactStore.java`
  - `save(Artifact)`, `get(String id)`, `delete(String id)`, `listBySession(String sessionId)`
- **新增:** `harness-preprocess/.../artifact/FilesystemArtifactStore.java`
  - 元数据: `{artifactDir}/{id}.meta.json`
  - 文件: `{artifactDir}/{id}/{filename}`
  - 过期清理: `evictExpired()` 定期扫描

#### 1.3 ArtifactStorageService
- **新增:** `harness-preprocess/.../artifact/ArtifactStorageService.java`
  - `store(byte[] data, String name, String mimeType, String sessionId)` → `Artifact`
  - `storeFromPath(Path source, String name, String mimeType, String sessionId)` → `Artifact`
  - MIME 自动推断（从文件扩展名）、大小限制检查

#### 1.4 StreamEvent 扩展
- **修改:** `harness-core/.../StreamEvent.java`
  - 新增 `Type.ARTIFACT`
  - 新增 `static StreamEvent artifact(Artifact artifact)` 方法

#### 1.5 AgentResult 扩展
- **修改:** `harness-core/.../AgentResult.java`
  - 新增字段 `List<Artifact> artifacts`（默认 `List.of()`，向后兼容）
  - 新增 `AgentResult.success(output, trace, steps, artifacts)` 重载

#### 1.6 下载端点
- **新增:** `harness-server/.../ArtifactHandler.java`
  - `GET /api/artifacts/{id}` — 下载文件（Content-Disposition: attachment）
  - `GET /api/artifacts/{id}/preview` — 内联预览（图片/视频/PDF，Content-Disposition: inline）
  - 权限校验：artifact 必须属于当前用户
- **修改:** `harness-server/.../Main.java` — 注册路由

---

### Phase 2: Python 沙盒工具

#### 2.1 Docker 镜像
- **新增:** `docker/sandbox/Dockerfile`
- **新增:** `docker/sandbox/requirements.txt` — 锁定所有依赖版本
```dockerfile
FROM python:3.14-slim
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
WORKDIR /workspace
```
```
# requirements.txt（版本锁定，避免镜像重建时因上游升级导致兼容性问题）
python-docx==1.2.0
openpyxl==3.1.5
python-pptx==1.0.2
Pillow==12.3.0
matplotlib==3.11.0
seaborn==0.13.2
pandas==3.0.3
numpy==2.5.1
reportlab==5.0.0
markdown==3.10.2
beautifulsoup4==4.15.0
```
- 不装 moviepy（视频走独立 API 工具）
- 不需要网络访问
- Python 版本选择 `3.14-slim`（2026 年 7 月最新稳定线，最新补丁 3.14.6），用具体版本号而非浮动的 `python:slim`，保证可复现性；升级是主动决定，不是构建时悄悄变化

**依赖版本说明：**
- `pandas` 从 2.x 升级到 3.0.3、`numpy` 从 2.2.x 升级到 2.5.1、`reportlab` 从 4.x 升级到 5.0.0 —— 均为大版本跳跃，但本功能是全新沙盒，不存在存量脚本迁移成本，直接按新 API 编写即可
- `pandas==2.2.3` 在 Python 3.14 发布前已发布，PyPI 预编译 wheel 不会为后来的 Python 版本补建，大概率找不到 3.14 的预编译包，安装会失败或退化为缓慢的源码编译
**构建验证结果（2026-07-14）：✅ 全部通过**
- `python:3.14-slim` + 上述 requirements.txt 构建成功
- 全部 11 个包均有 cp314 预编译 wheel（Pillow/matplotlib/pandas/numpy/contourpy/fonttools/kiwisolver/lxml 为原生扩展，其余为纯 Python），无源码编译退化
- 镜像标签: `cyrene-sandbox:latest` (54a3c393bf7e)
- 注: BuildKit 在当前环境下 auth 超时，需用 `DOCKER_BUILDKIT=0 docker build` 构建（Docker Desktop 的 BuildKit 已知问题，不影响镜像本身）

#### 2.2 PythonSandboxTool
- **新增:** `harness-tool/src/main/java/com/harness/tool/builtin/PythonSandboxTool.java`
- **实现标记接口:** `ArtifactProducingTool`（见 2.4），ReActEngine 只对实现了此接口的工具做产物解析
- **ToolSpec 参数:**
  - `script` (string, required): Python 代码
  - `input_artifact_ids` (string[], optional): 已有 artifact ID 列表，执行前复制到 `/workspace/input/`
  - `timeout_seconds` (int, optional, default 60): 执行超时
  - `memory_limit_mb` (int, optional, default 512): 内存限制
- **执行流程（try-finally 保证清理）:**
  1. 创建临时目录 `{tmpDir}/{uuid}/`
  2. 写入 `script.py`
  3. 如果 `input_artifact_ids` 非空，从 ArtifactStore 取出文件，复制到 `{tmpDir}/{uuid}/input/`（保持原文件名）
  4. **try:**
     5. 启动 Docker 容器:
        ```
        docker run --rm
          --memory={memory_limit_mb}m
          --cpus=1
          --pids-limit=50             ← 防止 fork bomb
          --network=none              ← 禁止网络访问
          --read-only                 ← 只读根文件系统
          --tmpfs /workspace/output:size=200m  ← output 用 tmpfs，内核层面限制写入大小
          -v {tmpDir}/{uuid}:/workspace
          cyrene-sandbox
          python /workspace/script.py
        ```
     6. 等待完成或超时（`Process.waitFor(timeout)`）
     7. 超时 → `process.destroyForcibly()` 强制 kill
     8. 读取 stdout/stderr，**截断至最后 64KB**（防止打印循环导致内存/上下文溢出）
     9. 扫描 `/workspace/output/` 中的文件
     10. 每个文件 → `ArtifactStorageService.storeFromPath()`
  11. **finally:** 清理临时目录（`FileUtils.deleteRecursive`，无论正常/异常/超时都执行）
  12. 返回 JSON: `{"success": true, "stdout": "...(截断)", "artifacts": [{id, name, mimeType, sizeBytes, downloadUrl}]}`
- **安全措施（完整清单）:**
  - `--network=none`: 禁止网络
  - `--read-only`: 只读根文件系统
  - `--memory`: 内存限制
  - `--pids-limit=50`: 进程数限制，防止 fork bomb
  - `--tmpfs /workspace/output:size=200m`: 输出目录 tmpfs，内核层面限制（ENOSPC），比事后检查文件大小更彻底
  - 超时强制 kill（`destroyForcibly`）
  - stdout/stderr 截断至 64KB
  - `HARNESS_SANDBOX_MAX_CONCURRENT` 信号量限制同时运行的容器数（与 SemaphoreChatModel 同理）

#### 2.3 ArtifactProducingTool 标记接口
- **新增:** `harness-tool/src/main/java/com/harness/tool/ArtifactProducingTool.java`
```java
/**
 * Marker interface for tools that produce downloadable artifacts.
 * ReActEngine only parses artifact JSON from tools implementing this interface,
 * avoiding false positives from regular tool outputs.
 */
public interface ArtifactProducingTool extends Tool {
}
```
- `PythonSandboxTool`, `ImageGenerationTool`, `VideoGenerationTool` 均实现此接口
- ReActEngine 中的 artifact 检测逻辑：`if (tool instanceof ArtifactProducingTool)` 才解析输出中的 artifacts 字段
- 避免对所有工具输出做字符串嗅探导致误判

#### 2.4 LLM 使用指引
在 system prompt 中注入沙盒使用规范:
- 输出文件必须写入 `/workspace/output/`，这是唯一会被收集的目录
- 输入数据通过 `input_artifact_ids` 参数传入，文件在 `/workspace/input/` 目录下，按原文件名读取
- 如果任务需要基于已有数据生成文件（如"根据这份 CSV 生成报表"），必须通过 `input_artifact_ids` 引用，不要把数据硬编码到脚本里
- 不要使用 `os.system`, `subprocess`, `eval`, `exec` 等（沙盒禁止网络，且这些调用无意义）
- 不要尝试访问 `/workspace/output/` 以外的写入路径（根文件系统只读）

---

### Phase 3: 独立生成工具

#### 3.1 ImageGenerationTool（同步）
- **新增:** `harness-tool/.../builtin/ImageGenerationTool.java`
- 参数: `prompt`, `size` (1024x1024/1792x1024/1024x1792), `quality` (standard/hd), `style` (vivid/natural), `n` (1-4)
- OkHttp → OpenAI `/v1/images/generations` (DALL-E 3)
- 下载图片 URL → `ArtifactStorageService.store()` → 返回 JSON artifacts
- 环境变量: `HARNESS_TOOL_IMAGE_GEN_API_KEY`（默认复用 `HARNESS_MODEL_CHAT_API_KEY`）

#### 3.2 VideoGenerationTool（异步回调）
- **新增:** `harness-tool/.../builtin/VideoGenerationTool.java`
- **两个 action（同一个 Tool）:**

**submit 阶段:**
- 参数: `action="submit"`, `prompt`, `duration`, `resolution`
- OkHttp → 外部 API 提交任务
- 返回: `{"status": "submitted", "task_id": "xxx", "message": "视频正在生成中，完成后会自动通知"}`
- 启动后台轮询线程 (`ScheduledExecutorService`)

**后台轮询:**
- 每 10 秒 poll 一次 API 状态
- 完成后: 下载视频 → `ArtifactStorageService.store()`
- 通过 `ArtifactCallback` 接口通知主流程
- 超时: 最长 10 分钟

**check 阶段（可选）:**
- 参数: `action="check"`, `task_id`
- 返回当前状态 + artifact（如果已完成）

- 环境变量: `HARNESS_TOOL_VIDEO_GEN_PROVIDER`, `HARNESS_TOOL_VIDEO_GEN_API_KEY`, `HARNESS_TOOL_VIDEO_GEN_BASE_URL`

#### 3.3 ArtifactCallback 机制
- **新增:** 接口 `ArtifactCallback { void onArtifactReady(String sessionId, Artifact artifact); }`
- **修改:** `AgentOrchestrator` 实现此接口
- 收到回调后:
  - streaming 模式: 发送 `StreamEvent.artifact()` SSE 事件
  - blocking 模式: 存入 ArtifactStore，下次请求时可查询

---

### Phase 4: ReAct 引擎集成

#### 4.1 Artifact 检测（基于标记接口，非字符串嗅探）
- **修改:** `harness-ai/.../react/ReActEngine.java`
- 工具执行后检查: `if (tool instanceof ArtifactProducingTool)` → 解析输出 JSON 中的 `artifacts` 字段
- 解析成功 → 通过 `ReActListener.onArtifact(List<Artifact>)` 发送事件
- 非 `ArtifactProducingTool` 的工具输出不做 artifact 解析，避免误判

#### 4.2 ReActListener 扩展
- **修改:** `harness-ai/.../react/ReActListener.java`
- 新增 `default void onArtifact(List<Artifact> artifacts) {}`

#### 4.3 AgentOrchestrator 桥接
- **修改:** `harness-agent/.../AgentOrchestrator.java`
- `streamRun()`: `onArtifact` → `StreamEvent.artifact()` → callback
- `run()`: 收集 artifacts → `AgentResult`
- `registerBuiltinTools()`: 注册三个工具（按配置条件）

#### 4.4 ChatHandler
- **修改:** `harness-server/.../ChatHandler.java`
- streaming: `case ARTIFACT` → `event: artifact` SSE
- blocking: done 事件包含 `artifacts` 数组

---

### Phase 5: Web UI

#### 5.1 artifact 事件处理
- **修改:** `public/js/app.js`
- SSE `artifact` 事件 → 存入 `msg.artifacts[]`
- done 事件中的 artifacts → 同样存入

#### 5.2 Artifact 渲染
- **修改:** `public/js/app.js`
- 图片 (IMAGE): `<img>` 预览 + 点击放大
- 视频 (VIDEO): `<video>` 播放器
- 文档/代码 (DOCUMENT/CODE): 下载卡片（图标 + 文件名 + 大小 + 下载按钮）

#### 5.3 API + CSS
- **修改:** `public/js/api.js` — `getArtifactUrl(id)`, `getArtifactPreviewUrl(id)`
- **修改:** `public/css/style.css` — `.artifact-card`, `.artifact-image`, `.artifact-video`, `.artifact-download`

---

### Phase 6: 配置

#### 6.1 环境变量
- **修改:** `harness-env/.../EnvKey.java`

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `HARNESS_ARTIFACT_DIR` | `./artifacts` | 产物存储目录 |
| `HARNESS_ARTIFACT_TTL_MINUTES` | `60` | 产物过期时间 |
| `HARNESS_ARTIFACT_MAX_SIZE_MB` | `100` | 单文件最大 |
| `HARNESS_SANDBOX_DOCKER_IMAGE` | `cyrene-sandbox` | 沙盒 Docker 镜像名 |
| `HARNESS_SANDBOX_TIMEOUT_SECONDS` | `120` | 沙盒执行超时 |
| `HARNESS_SANDBOX_MEMORY_MB` | `512` | 沙盒内存限制 |
| `HARNESS_SANDBOX_MAX_CONCURRENT` | `3` | 同时运行的沙盒容器数上限（信号量） |
| `HARNESS_TOOL_IMAGE_GEN_API_KEY` | (复用 chat key) | DALL-E API key |
| `HARNESS_TOOL_VIDEO_GEN_PROVIDER` | — | 视频生成 provider |
| `HARNESS_TOOL_VIDEO_GEN_API_KEY` | — | 视频生成 API key |
| `HARNESS_TOOL_VIDEO_GEN_BASE_URL` | — | 视频生成 API base URL |

---

## 关键文件清单

| 操作 | 文件 | 说明 |
|------|------|------|
| 新增 | `harness-core/.../model/Artifact.java` | 产物模型 |
| 新增 | `harness-preprocess/.../artifact/ArtifactStore.java` | 存储接口 |
| 新增 | `harness-preprocess/.../artifact/FilesystemArtifactStore.java` | 文件系统实现 |
| 新增 | `harness-preprocess/.../artifact/ArtifactStorageService.java` | 存储服务 |
| 新增 | `harness-tool/.../ArtifactProducingTool.java` | 标记接口，产物工具实现此接口 |
| 新增 | `harness-tool/.../builtin/PythonSandboxTool.java` | Python 沙盒（实现 ArtifactProducingTool） |
| 新增 | `harness-tool/.../builtin/ImageGenerationTool.java` | DALL-E 图片生成（实现 ArtifactProducingTool） |
| 新增 | `harness-tool/.../builtin/VideoGenerationTool.java` | 视频生成-异步（实现 ArtifactProducingTool） |
| 新增 | `harness-server/.../ArtifactHandler.java` | 下载/预览端点 |
| 新增 | `docker/sandbox/Dockerfile` | 沙盒镜像 |
| 新增 | `docker/sandbox/requirements.txt` | Python 依赖版本锁定 |
| 修改 | `harness-core/.../model/StreamEvent.java` | +ARTIFACT 类型 |
| 修改 | `harness-core/.../model/AgentResult.java` | +artifacts 字段 |
| 修改 | `harness-ai/.../react/ReActEngine.java` | artifact 检测（基于 ArtifactProducingTool） |
| 修改 | `harness-ai/.../react/ReActListener.java` | +onArtifact 回调 |
| 修改 | `harness-agent/.../AgentOrchestrator.java` | 桥接+注册+回调 |
| 修改 | `harness-server/.../ChatHandler.java` | artifact SSE 事件 |
| 修改 | `harness-server/.../Main.java` | 注册路由 |
| 修改 | `harness-env/.../EnvKey.java` | 环境变量 |
| 修改 | `public/js/app.js` | artifact 渲染 |
| 修改 | `public/js/api.js` | artifact API |
| 修改 | `public/css/style.css` | artifact 样式 |

---

## 验证方案

1. **构建 Docker 镜像:** `docker build -t cyrene-sandbox docker/sandbox/`
2. **沙盒基础测试:** python_sandbox 生成 Excel → 下载验证
3. **输入数据测试:** 上传 CSV → 用 input_artifact_ids 传入沙盒 → 生成 Excel 报表
4. **图片测试:** image_generation "猫咪图片" → 图片预览
5. **视频测试:** video_generation submit → 等待回调 → 视频播放
6. **安全测试:**
   - 沙盒内网络访问（应失败，`--network=none`）
   - 超时脚本（应被 `destroyForcibly` kill）
   - fork bomb（`--pids-limit=50` 应阻止）
   - 超大输出写入（tmpfs 200m 限制应返回 ENOSPC）
   - stdout 打印循环（应截断至 64KB，不溢出）
7. **并发测试:** 同时发起超过 `HARNESS_SANDBOX_MAX_CONCURRENT` 个沙盒任务，验证信号量排队
8. **清理测试:** Docker daemon 不可用时，临时目录应被 finally 块清理
