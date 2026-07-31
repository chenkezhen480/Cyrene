# TODO10：轻量多模态文档修复与流式 ASR/TTS API

## 0. 背景与已确认决策

当前知识库入库主链路为：

```text
文件
  → TextExtractorRegistry
  → TextChunker
  → EmbeddingModelProvider
  → VectorStore
```

当前链路对 TXT、PDF、DOC/DOCX、XLS/XLSX 等文件执行原生文本抽取，但抽取结果直接收敛为扁平 `String`，页码、段落、区域坐标、图片关系和修复历史没有保留下来。对于以下内容可能出现空文本或乱码：

- PDF 缺少或损坏 ToUnicode/CMap，页面视觉文字正常但文本抽取异常。
- 扫描 PDF 页面没有文本层。
- PPT/PPTX、DOCX 中包含承载主要内容的图片。
- 文件局部编码错误、控制字符或不可见字符污染。
- 表格、流程图和架构图无法由普通文本抽取器表达。

当前语音能力由 `VoiceModelProvider` 同时承担 ASR 与 TTS，仅有 OpenAI-compatible API 实现。现有 TTS 会等待完整文本生成后再一次性合成，无法在最终回答生成期间开始播放。

本轮确认以下决策：

1. **不引入本地 OCR 服务作为必选组件。**
2. **不把整个文档交给视觉 LLM 重新解析。**
3. **先执行原生结构化抽取，通过确定性规则检测异常，只将异常区域图片交给主多模态 LLM 修复。**
4. **视觉修复结果按稳定 `blockId` 回填，之后继续复用现有切片、Embedding 和 VectorStore 链路。**
5. **不新增 `HARNESS_KNOWLEDGE_VISUAL_MODE`。**
6. **复用现有文档类型和图片解析开关；先清点并真正接通当前已定义但未生效的配置。**
7. **主聊天模型具备图片输入能力时直接用于局部修复，不额外部署 OCR，不静默切换到其他模型。**
8. **语音功能采用 ASR + TTS API，不部署本地语音模型、原生库、Python 服务或额外语音容器。**
9. **保留现有 `VoiceModelProvider` 组合接口，不将 ASR/TTS 默认注册为 LLM Tool。**
10. **ASR 在音频输入解析阶段确定性调用；TTS 在最终回答输出阶段确定性调用。**
11. **TTS 不等待完整回答生成完成：最终回答首个稳定短句形成后立即调用语音 API，后续文本生成、语音合成与前端播放并行。**
12. **ReAct 中间轮 token、工具前说明和工具参数不得进入 TTS。**
13. **继续复用现有 `HARNESS_MODEL_VOICE_*` 配置，不新增本地模型路径配置。**
14. **任何修复、入库、删除和替换操作保持事务一致性；失败时不得留下半份文件、半批向量或部分修复状态。**

---

## 1. 目标与非目标

### 1.1 目标

- 将文档抽取结果从扁平字符串升级为带来源位置的结构化 Block。
- 通过无 LLM 规则识别乱码、空文本和异常字符区域。
- 只渲染异常区域或异常页，减少视觉模型输入量。
- 使用主多模态 LLM 返回严格结构化的修复结果。
- 验证修复结果后按 `blockId` 精确回填，保留原始文本和修复审计信息。
- 修复完成后继续使用现有 `TextChunker`、`EmbeddingModelProvider` 和 `VectorStore`。
- 支持 PDF 局部区域修复和扫描页整页修复。
- 补齐 PPTX 结构化文本与图片抽取能力。
- 为 DOCX 图片内容和异常段落保留扩展位置。
- 保留 ASR 与 TTS 两种能力及其独立可用性检测。
- ASR 首期通过 OpenAI-compatible API 支持常见音频文件转写。
- TTS 首期通过 OpenAI-compatible API 支持声音选择、语速和流式音频输出。
- 最终回答按稳定短句增量提交 TTS，不等待完整文本。
- 在现有聊天 SSE 中传输有序音频事件。
- 前端边接收、边排队、边播放语音。
- 所有新增列表型管理接口使用游标分页。
- 前端相关页面保持响应式设计，并严格匹配后端返回 DTO。

### 1.2 非目标

- 不实现全量 OCR 平台。
- 不把所有图片、所有页面或所有 PPT 幻灯片默认交给 LLM。
- 不让 LLM 自由重写整篇原文。
- 不将视觉修复结果直接写入向量库而跳过现有切片流程。
- 不在同一个向量字段混用文本、图片和音频 Embedding。
- 不在首期实现原生图片向量、以图搜图或音频向量检索。
- 不在首期实现语音克隆、音色训练、说话人注册和实时会议转写。
- 不引入本地 ASR/TTS 模型、JNI、ONNX Runtime、GPU 或 Python sidecar。
- 不把 ASR 和 TTS 默认暴露为 LLM 自主调用工具。
- 不将每个 token 单独提交给 TTS。
- 不朗读 ReAct 中间推理、工具调用参数、Markdown 代码块或隐藏内容。
- 不在首期切换到 Realtime Speech-to-Speech 架构。

---

## 2. 总体架构

### 2.1 文档修复链路

```text
原始文件
   │
   ▼
StructuredDocumentExtractorRegistry
   │
   ▼
ExtractedDocument
   ├─ DocumentBlock[]
   ├─ DocumentAsset[]
   └─ PageMetadata[]
   │
   ▼
TextCorruptionDetector
   │
   ├─ 正常 Block ──────────────────────────────┐
   │                                           │
   └─ 异常 Block                              │
        │                                      │
        ▼                                      │
   DocumentRegionRenderer                     │
        │                                      │
        ▼                                      │
   DocumentVisualRepairService                 │
        │ 主多模态 LLM + JSON Schema            │
        ▼                                      │
   RepairResultValidator                       │
        │                                      │
        ▼                                      │
   DocumentRepairMerger ───────────────────────┘
        │
        ▼
   StructuredDocumentFormatter
        │
        ▼
   TextChunker → Embedding → VectorStore
```

### 2.2 ASR 输入链路

```text
音频附件
  → MultimodalParser
  → VoiceModelProvider.transcribe()
  → 转写文本
  → 主 LLM / ReAct
```

ASR 由输入处理流程确定性调用，不先让 LLM 判断是否需要调用语音工具。

### 2.3 TTS 流式输出链路

```text
ReAct 工具阶段
  → 最终回答流式阶段（禁用工具）
  → 最终文本 token
  → SpeechTextAccumulator
  → SpeechChunker
  → TtsChunkQueue
  → VoiceModelProvider.streamSynthesize()
  → AUDIO_DELTA SSE
  → 浏览器 AudioContext 顺序播放
```

语音输出请求使用独立最终回答阶段，确保送入 TTS 的 token 已经是最终回答，而不是 ReAct 中间轮文本。

---

## 3. 结构化文档模型

### 3.1 `ExtractedDocument`

```java
public record ExtractedDocument(
        String documentId,
        String fileName,
        String mimeType,
        List<DocumentBlock> blocks,
        List<DocumentAsset> assets,
        List<PageMetadata> pages,
        Map<String, Object> metadata
) {
}
```

约束：

- `documentId` 由文件内容哈希生成，不能依赖临时路径。
- `blocks` 必须保持稳定阅读顺序。
- `metadata` 只允许 JSON 可序列化类型。
- 抽取器不得直接调用切片器、Embedding 或 VectorStore。

### 3.2 `DocumentBlock`

```java
public record DocumentBlock(
        String blockId,
        BlockType blockType,
        String text,
        int pageNumber,
        int readingOrder,
        BoundingBox boundingBox,
        String assetId,
        Map<String, Object> metadata
) {
}
```

`BlockType` 首期包含：

```text
TITLE
HEADING
PARAGRAPH
LIST_ITEM
TABLE
CAPTION
FOOTNOTE
IMAGE_TEXT
UNKNOWN
```

约束：

- `blockId` 在同一份文件重复解析时保持稳定。
- PDF 推荐使用 `pageNumber + readingOrder + normalizedTextHash`。
- PPTX 推荐使用 `slideNumber + shapeId`。
- DOCX 推荐使用 `bodyIndex + paragraph/run relationship id`。
- `boundingBox` 使用页面坐标系，不直接保存屏幕像素。
- 没有坐标时允许为 `null`，但必须保留页码或结构索引。

### 3.3 原文与修复记录

修复不得覆盖审计信息：

```java
public record DocumentRepairRecord(
        String repairId,
        String documentId,
        String blockId,
        String originalText,
        String repairedText,
        RepairReason reason,
        String modelProvider,
        String modelName,
        String promptVersion,
        String regionHash,
        Instant repairedAt
) {
}
```

原始文件继续由 `FileStorageService` 保存；向量 metadata 只保存必要的修复标识，不把完整图片或长文本复制进 metadata。

---

## 4. 乱码与异常检测

### 4.1 `TextCorruptionDetector`

```java
public interface TextCorruptionDetector {
    CorruptionAssessment assess(DocumentBlock block, DetectionContext context);
}
```

```java
public record CorruptionAssessment(
        boolean corrupted,
        double score,
        Set<CorruptionReason> reasons,
        List<TextRange> suspiciousRanges
) {
}
```

检测必须为纯函数，不访问网络、不调用 LLM，便于单元测试和复用。

### 4.2 首期检测规则

- Unicode 替换字符 `U+FFFD`。
- 非法代理项、NUL 和异常控制字符。
- 私有使用区字符占比异常。
- 常见 UTF-8/GBK/Latin-1 mojibake 序列。
- 符号、不可见字符或未知字符比例异常。
- 单字符或短模式异常重复。
- 页面存在图片或绘制内容，但抽取文本为空。
- 相邻正常块使用稳定语言，而当前块有效文字比例突然下降。
- PDF 字形数量明显大于成功映射字符数量。

规则不得把以下内容直接判为乱码：

- Java、JSON、XML、SQL 和 Shell 代码。
- 数学公式。
- URL、哈希、UUID。
- 日文、韩文、少数民族文字或混合语言。
- 专业缩写和产品型号。

### 4.3 阈值管理

- 默认值集中在不可变配置对象中，不散落在检测函数。
- 仅将确有运维价值的阈值开放为环境变量。
- 环境变量解析、范围校验和默认值统一放在 `harness-env`。
- 不增加总开关 `HARNESS_KNOWLEDGE_VISUAL_MODE`。
- 优先复用已有文件类型与图片解析开关。

---

## 5. 各格式抽取与区域渲染

### 5.1 PDF

使用 PDFBox：

- 自定义 `PDFTextStripper` 或监听 `TextPosition`，生成按行/段落聚合的 Block。
- 保存页码、字符位置和 Block 边界。
- 使用 `PDFRenderer` 渲染异常 Block 的裁剪区域。
- 裁剪区域增加安全边距，避免截断上下文。
- 页面抽取为空但页面有绘制对象时，升级为整页渲染。
- 限制最大渲染像素和单次修复图片大小。

适用问题：

- 字体映射异常。
- 扫描页。
- 页面局部图片文字。
- 图表、流程图或表格视觉内容。

不适用问题：

- 原始页面本身显示的就是乱码。
- 原文件内容已经不可恢复。

遇到不适用情况必须返回明确错误，不允许让 LLM猜测缺失原文。

### 5.2 PPTX

当前知识库上传白名单和 `OfficeTextExtractor` 尚未实际支持 PPT/PPTX，需要补齐：

- 使用 Apache POI XSLF 读取 Slide、TextShape、TableShape、PictureShape。
- 使用 `shapeId` 建立稳定 Block。
- 文本 Shape 保留坐标和阅读顺序。
- 图片 Shape 建立 `DocumentAsset`。
- 文本乱码时裁剪 Shape 区域；整页关系复杂时渲染当前 Slide。
- 仅在现有图片解析配置允许时解析图片 Shape。
- PPT 老格式是否支持单独评估，不因上传白名单存在就声称可解析。

### 5.3 DOCX

- 按正文顺序读取段落、表格和图片关系。
- Block 不再只保存 `XWPFParagraph.getText()`。
- 图片与附近段落建立弱关联，不伪造精确页面坐标。
- 没有可靠排版坐标时，以图片本身或关联内容为视觉输入。
- DOCX 原始 XML 本身为乱码时，不进行猜测式修复。

### 5.4 XLSX

- 按工作表、行、列生成结构化 Block。
- 保留公式、显示值和单元格坐标，类型必须与 POI 返回类型一致。
- 首期不承诺无 Office 渲染引擎下的工作表像素级区域截图。
- 图片对象可独立抽取，但不得伪装成对应单元格内容。

### 5.5 纯文本与其他格式

- TXT/MD/CSV/JSON/XML 保持原生抽取。
- 尝试确定性字符集解码后仍为乱码时返回错误。
- 不调用视觉 LLM，因为不存在可信的视觉原文。

---

## 6. 主多模态 LLM 局部修复

### 6.1 `DocumentVisualRepairService`

```java
public final class DocumentVisualRepairService {

    private final ChatModelProvider chatModelProvider;
    private final RepairPromptFactory repairPromptFactory;
    private final RepairResultValidator repairResultValidator;

    public DocumentVisualRepairService(
            ChatModelProvider chatModelProvider,
            RepairPromptFactory repairPromptFactory,
            RepairResultValidator repairResultValidator
    ) {
        this.chatModelProvider = chatModelProvider;
        this.repairPromptFactory = repairPromptFactory;
        this.repairResultValidator = repairResultValidator;
    }
}
```

约束：

- 通过构造注入，不在方法内 `new` Provider。
- 启动或调用前使用 `ModalCapabilityRegistry` 验证主模型支持 `IMAGE_INPUT`。
- 主模型不支持图片时直接抛出可渲染异常，不静默切换其他 Provider。
- 使用主模型的非思考调用，避免为字符恢复消耗推理 Token。
- 复用现有模型并发控制和请求超时。
- 每次请求只包含异常区域、异常 Block 和少量相邻文本。

### 6.2 请求结构

```java
public record DocumentRepairRequest(
        String documentId,
        String promptVersion,
        byte[] regionImage,
        String imageMimeType,
        List<RepairTargetBlock> targets,
        List<ContextBlock> contextBlocks
) {
}
```

### 6.3 返回结构

```java
public record DocumentRepairResult(
        List<RepairedBlock> blocks,
        String detectedLanguage,
        List<String> warnings
) {
}
```

模型只能返回：

```json
{
  "blocks": [
    {
      "blockId": "page-3-block-7",
      "blockType": "PARAGRAPH",
      "text": "修复后的原文"
    }
  ],
  "detectedLanguage": "zh-CN",
  "warnings": []
}
```

禁止模型：

- 返回未请求的 `blockId`。
- 改写正常 Block。
- 总结、扩写或解释原文。
- 根据上下文补写图片中不存在的内容。
- 改变表格行列含义。

### 6.4 校验与回填

`RepairResultValidator` 必须检查：

- JSON Schema 合法。
- `blockId` 全部来自请求目标。
- 每个目标至多返回一次。
- 返回文本非空且不继续满足严重乱码规则。
- Block 类型未越权改变。
- 返回数量与请求目标一致，除非明确标记不可识别。

`DocumentRepairMerger` 是纯函数：

```java
public interface DocumentRepairMerger {
    ExtractedDocument merge(
            ExtractedDocument original,
            DocumentRepairResult repairResult
    );
}
```

不得使用全局字符串替换，必须按 `blockId` 替换。

---

## 7. 入库事务与缓存

### 7.1 调整后的 `KnowledgeIngestService`

```text
校验文件
  → 结构化抽取
  → 异常检测
  → 局部视觉修复
  → 修复结果校验
  → 格式化文本
  → 切片
  → 批量 Embedding
  → 保存原始文件
  → VectorStore 批量写入
  → 提交
```

约束：

- 视觉修复失败时不得开始 Embedding。
- Embedding 失败时不得写入 VectorStore。
- 文件已写入但向量失败时删除该文件。
- pgvector 使用数据库事务批量写入。
- Milvus 使用批次状态和补偿删除保证一致性。
- 不允许返回成功但只写入部分 Chunk。
- 异常通过统一业务异常抛给 Handler，由前端展示页码、Block 和原因。

### 7.2 修复缓存

缓存键：

```text
SHA-256(
  sourceFileHash
  + pageNumber
  + boundingBox
  + regionImageHash
  + promptVersion
  + modelProvider
  + modelName
)
```

缓存目标：

- 同一文件重复上传时不重复消耗视觉 Token。
- Prompt 或模型变化后自动失效。
- 失败结果不长期缓存。
- 缓存记录不能替代原始文件。

首期可以使用文件型缓存或现有存储抽象；如果增加管理列表，必须使用游标分页。

---

## 8. 现有配置清理与复用

当前已定义：

```text
HARNESS_KNOWLEDGE_PDF_ENABLED
HARNESS_KNOWLEDGE_DOCX_ENABLED
HARNESS_KNOWLEDGE_XLSX_ENABLED
HARNESS_MULTIMODAL_IMAGE_ENABLED
HARNESS_MULTIMODAL_VIDEO_ENABLED
```

当前问题：

- 部分 `HARNESS_KNOWLEDGE_*_ENABLED` 仅定义在 `EnvKey` 和 `.env.example`，尚未接入知识上传校验及抽取流程。
- 知识库上传当前未允许 PPT/PPTX。
- 普通文件上传允许 PPT/PPTX，不代表知识库抽取器已经支持。

实施要求：

- 先统一配置语义和实际调用位置。
- 不新增 `HARNESS_KNOWLEDGE_VISUAL_MODE`。
- 图片解析是否允许继续复用现有图片类配置。
- 若现有分支已经存在 PPT/PPTX 开关，直接复用，避免重复键。
- 若最终确认没有 PPT/PPTX 开关，只增加文件类型级开关，不增加第二套视觉模式。
- 删除配置接通后仍然无用的死代码和重复判断。

---

## 9. API ASR/TTS 方案

语音能力统一接外部 API，不部署语音模型、原生库、Python 服务或额外容器。

### 9.1 Provider 边界

- 保留现有 `VoiceModelProvider`，不拆成两个 Provider。
- 保留现有 `OpenAiVoiceModelProvider`，在其内部实现 ASR、普通 TTS 和流式 TTS。
- ASR 是音频输入解析步骤，TTS 是最终文本响应的确定性输出步骤；二者都不注册为 LLM Tool。
- Provider、HTTP Client、序列化器和配置对象均通过依赖注入获得，不在业务流程中 `new`。
- `provider=none` 时调用语音能力明确抛出未配置异常。
- 不做 Provider 自动切换，不在流式 TTS 不可用时静默退回“全文生成完再合成”。

### 9.2 API 映射

```text
ASR  -> POST {baseUrl}/audio/transcriptions
TTS  -> POST {baseUrl}/audio/speech
```

要求：

- ASR 使用 multipart 请求，严格按供应商响应 DTO 的实际层级读取转写文本。
- TTS 每次提交一个稳定短句，而不是逐 Token 调用。
- TTS API 的音频流采用供应商支持的流格式；若模型或兼容 API 不支持，能力检查直接返回不可用。
- 复用 HTTP 连接池，并为 ASR、TTS 分别设置连接、首字节和总请求超时。
- API Key 只在服务端使用，不返回前端、不写日志。

### 9.3 能力检查

新增 `VoiceCapabilities`，至少包含：

```java
public record VoiceCapabilities(
        boolean asrAvailable,
        boolean ttsAvailable,
        boolean ttsStreamingAvailable,
        List<String> acceptedInputMimeTypes,
        List<String> outputFormats
) {
}
```

- 启动配置校验负责检查必填项和数值范围。
- 远端能力可在首次调用时探测并做短期缓存，避免每次请求额外消耗。
- 能力探测失败须区分配置错误、鉴权错误、模型不支持和远端暂时不可用。
- 流式语音模式要求 `ttsStreamingAvailable=true`；不满足时在请求开始前返回可展示错误。

---

## 10. `VoiceModelProvider` 流式扩展

保留已有同步能力，新增流式合成方法：

```java
public interface VoiceModelProvider {

    String transcribe(InputStream audio, String mimeType);

    byte[] synthesize(String text, String voice);

    void streamSynthesize(
            SynthesisRequest request,
            AudioStreamCallback callback
    );

    VoiceCapabilities capabilities();
}
```

```java
public record SynthesisRequest(
        long sequence,
        String text,
        String voice,
        double speed,
        String responseFormat,
        String streamFormat
) {
}
```

```java
public record AudioChunk(
        long sequence,
        byte[] data,
        String mimeType,
        boolean last
) {
}
```

```java
public interface AudioStreamCallback {

    void onStart(long sequence, String mimeType);

    void onChunk(AudioChunk chunk);

    void onComplete(long sequence);

    void onError(long sequence, Throwable error);
}
```

实现要求：

- `OpenAiVoiceModelProvider` 负责将远端音频增量转换为 `AudioChunk`。
- `NoOpVoiceModelProvider` 对所有语音调用抛出明确配置异常，不提供空音频。
- 回调异常、远端非成功响应和协议解析异常统一转换为项目异常并保留 cause。
- 流式请求返回可取消句柄，统一挂到当前聊天请求生命周期。
- 迁移完成后删除重复的整段音频缓存、无用兼容分支和未被调用的方法。

---

## 11. 最终答案流式 TTS

### 11.1 只朗读最终答案

当前 ReAct 流程可能在工具调用轮次也触发 `onToken`。这些 Token 不能直接进入 TTS，否则会朗读工具参数、中间推理或随后被替换的草稿。

语音输出模式调整为：

1. ReAct 阶段完成工具选择和执行。
2. 进入显式的最终答案生成阶段，并在该阶段禁用工具。
3. 只有最终答案阶段的 Token 同时发送文本 SSE 和 `VoiceOutputCoordinator`。
4. 最终答案完成后刷新剩余文本，再发送文本与音频完成事件。

该边界必须由运行状态明确标识，禁止用字符串特征猜测“是否最终答案”。

成本约束：

- 仅 `outputMode=audio` 使用最终答案专用阶段，文本模式不增加调用。
- 若请求在生成前已能确定不需要工具，可直接进入最终答案阶段，不重复生成。
- 若请求允许工具调用，为避免朗读中间内容，需要在工具执行结束后进行一次工具禁用的最终生成；这是流式安全边界带来的明确成本，不再额外调用 TTS 文本整理模型。
- 短句切分和 Markdown 规范化全部用本地确定性函数完成，不消耗 LLM Token。

### 11.2 稳定短句切分

`SpeechTextAccumulator` 接收最终答案 Token，`SpeechChunker` 产生可合成短句：

- `。！？；\n` 作为强边界。
- 达到软阈值后，逗号、冒号等可作为软边界。
- 超过硬阈值时按最近的安全文本边界切分。
- 最终答案结束时刷新不足最小阈值的尾部文本。
- Markdown 标题、列表符号和强调标记在合成前规范化，但不改变发给前端的原始文本。
- 代码块、表格、URL 和超长标识符采用可配置策略；首期默认跳过代码块和完整 URL。
- 最小、软、最大字符数全部来自配置，并校验 `min <= soft <= max`。

不能逐 Token 调用 TTS，也不能等待整段答案生成完成后才调用。

### 11.3 队列、顺序与背压

- `VoiceOutputCoordinator` 通过有界 `TtsChunkQueue` 管理待合成短句。
- 每个短句分配单调递增 `sequence`，音频必须按序发送和播放。
- 首期每个会话最多一个在途 TTS 请求，避免并发完成顺序不确定。
- 播放第 N 段时可以继续合成第 N+1 段，实现生成、合成、播放重叠。
- 队列接近容量时合并尚未提交的相邻短句，不阻塞 LLM Token 回调。
- 队列已满且无法合并时抛出语音背压异常并发送 `AUDIO_ERROR`；文本流继续完成，不静默切换为全文 TTS。

### 11.4 取消与错误

- 用户停止生成时，同时取消 LLM、当前 TTS HTTP 请求和后续队列。
- 后端停止发送音频事件，前端清空播放队列并停止 `AudioContext` 中的当前音源。
- TTS 失败不破坏已经生成的文本；语音通道发送结构化错误，前端明确展示。
- 不自动重试非幂等或已部分播放的短句，避免重复朗读。

---

## 12. 音频传输与播放

### 12.1 SSE 事件

复用现有聊天 SSE 通道，扩展事件类型：

```text
AUDIO_START
AUDIO_DELTA
AUDIO_CHUNK_DONE
AUDIO_DONE
AUDIO_ERROR
```

事件字段至少包含：

```json
{
  "type": "AUDIO_DELTA",
  "sequence": 1,
  "mimeType": "audio/pcm",
  "data": "<base64>"
}
```

要求：

- `AUDIO_START` 声明输出格式和会话级音频参数。
- `AUDIO_DELTA` 仅承载当前 `sequence` 的增量数据。
- `AUDIO_CHUNK_DONE` 表示一个短句音频完成。
- `AUDIO_DONE` 仅在所有已提交短句播放数据发送完成后触发。
- `AUDIO_ERROR` 使用与项目一致的 `code`、`message` 和必要详情。
- 首期接受 Base64 的传输开销；只有基准证明不可接受时再拆独立二进制通道。

### 12.2 前端播放

- 使用 `AudioContext` 或等价浏览器能力维护有序播放队列。
- 只有用户选择语音输出后才初始化播放上下文，满足浏览器用户激活约束。
- 按 `sequence` 拼接或排队，不按网络到达顺序直接播放。
- 文本继续实时渲染，音频播放不能阻塞界面更新。
- 窗口隐藏、网络抖动、取消和页面卸载时释放播放资源。

### 12.3 ASR 输入格式

- 浏览器录音和文件上传使用远端 ASR API 支持的格式。
- 服务端在调用 API 前校验 MIME、扩展名、文件大小和音频时长。
- 不在首期引入 FFmpeg 转码；不支持的格式直接返回 `415 Unsupported Media Type`。
- 临时文件仅在 multipart 客户端确有需要时创建，并在 `finally` 中删除。

---

## 13. 配置设计

继续复用现有语音配置：

```properties
HARNESS_MODEL_VOICE_PROVIDER=
HARNESS_MODEL_VOICE_API_KEY=
HARNESS_MODEL_VOICE_BASE_URL=
HARNESS_MODEL_VOICE_ASR_MODEL=
HARNESS_MODEL_VOICE_TTS_MODEL=
```

只在同一命名空间增加流式行为参数：

```properties
HARNESS_MODEL_VOICE_TTS_STREAM_ENABLED=true
HARNESS_MODEL_VOICE_TTS_STREAM_FORMAT=sse
HARNESS_MODEL_VOICE_TTS_RESPONSE_FORMAT=pcm
HARNESS_MODEL_VOICE_TTS_DEFAULT_VOICE=alloy
HARNESS_MODEL_VOICE_TTS_SPEED=1.0
HARNESS_MODEL_VOICE_TTS_CHUNK_MIN_CHARS=12
HARNESS_MODEL_VOICE_TTS_CHUNK_SOFT_CHARS=30
HARNESS_MODEL_VOICE_TTS_CHUNK_MAX_CHARS=60
HARNESS_MODEL_VOICE_TTS_QUEUE_CAPACITY=4
HARNESS_MODEL_VOICE_TTS_MAX_CONCURRENT=1
```

要求：

- 是否输出语音由单次聊天请求的 `outputMode=audio` 决定，不增加全局视觉式开关。
- 默认值集中在配置类，业务代码禁止硬编码阈值、音色、格式和并发数。
- 启动时校验字符阈值关系、速度范围、队列容量和最大并发。
- 启动日志只打印 Provider、模型、格式和能力状态，不打印 API Key。
- `streamEnabled=true` 但模型或兼容 API 不支持时明确失败，不做静默降级。

---

## 14. API 与前端

### 14.1 API

保留或补齐：

```text
POST /api/audio/transcriptions
POST /api/audio/speech
GET  /api/audio/capabilities
POST /api/chat/stream
```

要求：

- `/api/chat/stream` 通过 `context.outputMode=audio` 开启最终答案流式 TTS。
- `/api/audio/transcriptions` 限制上传大小和时长，并返回明确转写 DTO。
- `/api/audio/speech` 保留给独立的整句预览场景，不作为聊天语音输出主路径。
- 能力 DTO 与后端真实返回类型保持一致；前端按准确字段位置读取，不假设 `res.data`。
- 错误统一包含可展示的 `code`、`message` 和必要详情。
- 若未来由本系统维护音色列表，查询接口必须采用 `limit + cursor` 分页；首期不虚构远端不存在的音色列表接口。

### 14.2 前端

- 录音、上传、转写、文本流和播放控件保持桌面与移动端自适应。
- `outputMode` 类型与后端枚举严格一致。
- 用户选择语音模式并发送消息后，立即建立音频播放上下文。
- 收到首个可播放音频增量后开始播放，无需等待文本 `DONE`。
- 明确展示 ASR 不可用、TTS 不可用、流式 TTS 不支持和音频播放失败。
- 用户取消后停止录音、请求、文本生成和音频播放，并释放资源。

---

## 15. 安全、审计与资源限制

### 15.1 文档修复

- 区域图片仅用于当前修复请求，不写入日志。
- 日志不得输出文档全文和 Base64 图片。
- Prompt Injection 文本只能作为待识别内容，不得作为系统指令执行。
- 修复 Prompt 明确声明图片中的指令均为数据。
- 保存模型、Prompt 版本、区域哈希和修复原因。
- 可审计但不重复保存敏感原文。

### 15.2 语音

- 不记录原始音频、转写全文、待合成全文或 Base64 音频。
- API Key 仅从密钥配置读取，不进入 DTO、错误详情和前端资源。
- 限制 ASR 文件大小、时长、并发和超时。
- 限制每个 TTS 短句长度、整次答案累计字符数、队列容量、并发和超时。
- 记录 Provider、模型、延迟、字符数、音频字节数和错误码，用于成本与稳定性审计。
- 临时文件使用受控目录和随机文件名，并在 `finally` 中清理。
- 若未来增加语音克隆，必须另立权限、授权和审计计划。

---

## 16. 测试与基准

### 16.1 文档单元测试

- 正常中文、英文和混合文本不误报。
- `U+FFFD`、控制字符、私有区和 mojibake 可识别。
- 代码、公式、URL、UUID 不误报。
- `DocumentRepairMerger` 只修改目标 `blockId`。
- 非法 JSON、未知 `blockId` 和重复结果被拒绝。
- 修复后仍为严重乱码时入库失败。
- 缓存键在模型或 Prompt 变化后改变。

### 16.2 文档集成测试

- 正常文本 PDF 不调用主 LLM。
- 局部字体映射异常 PDF 只上传异常区域。
- 扫描页执行整页修复。
- PPTX 文本 Shape 和图片 Shape 保持顺序。
- 视觉修复失败不产生文件和向量残留。
- pgvector 事务回滚。
- Milvus 批次失败执行补偿删除。

### 16.3 ASR 测试

- multipart 请求字段、文件名、MIME 和模型参数正确。
- 严格从供应商响应 DTO 的 `text` 字段读取转写结果。
- 普通话、中英混合、噪声和长音频测试集记录转写差异与延迟。
- 超大小、超时长和不支持格式在调用远端前被拒绝。
- 鉴权失败、限流、超时、取消和非法响应转换为可展示错误。
- 用户取消后关闭上传流和远端 HTTP 请求。

### 16.4 流式 TTS 测试

- 首个稳定最终短句形成后立即发起 TTS，早于文本 `DONE`。
- 工具调用轮次和中间 ReAct Token 不触发 TTS。
- 强边界、软边界、硬阈值和最终尾句切分正确。
- Markdown、代码块、表格、URL 的朗读清理符合配置。
- 每段音频 `sequence` 单调递增，前端严格按序播放。
- 队列容量、短句合并、背压错误和取消行为可重复验证。
- 远端音频增量正确映射为 `AUDIO_START`、`AUDIO_DELTA`、`AUDIO_CHUNK_DONE` 和 `AUDIO_DONE`。
- 远端失败发送 `AUDIO_ERROR`，文本流仍可正常完成。
- 文本模式不调用 TTS；语音模式且能力不可用时在生成前返回错误。

### 16.5 验收门槛

- 正常文档不产生视觉模型调用。
- 视觉请求数量与异常区域数量一致，不按总页数调用。
- 修复结果可以稳定回填且不改写正常文本。
- ASR 和 TTS 统一通过已配置的远端 API 提供。
- 不新增语音模型、原生库、Python 服务、GPU 依赖或额外容器。
- ASR/TTS 不作为 LLM Tool 暴露。
- 首段音频在最终答案文本完成前开始返回。
- 任何工具中间内容都不会进入 TTS。
- 取消后不再产生新的文本或音频事件。
- 所有现有模型和 RAG 测试保持通过。

---

## 17. 预计文件变更

### 17.1 `harness-core`

新增：

```text
model/document/ExtractedDocument.java
model/document/DocumentBlock.java
model/document/DocumentAsset.java
model/document/BoundingBox.java
model/document/DocumentRepairRecord.java
model/speech/SynthesisRequest.java
model/speech/AudioChunk.java
model/speech/VoiceCapabilities.java
```

调整：

```text
StreamEvent.java
```

### 17.2 `harness-input`

新增或调整：

```text
StructuredDocumentExtractor.java
StructuredDocumentExtractorRegistry.java
TextCorruptionDetector.java
RuleBasedTextCorruptionDetector.java
DocumentRegionRenderer.java
PdfStructuredDocumentExtractor.java
PptxStructuredDocumentExtractor.java
DocxStructuredDocumentExtractor.java
XlsxStructuredDocumentExtractor.java
StructuredDocumentFormatter.java
```

现有 `TextExtractor` 保留兼容期，迁移完成后删除重复实现。

### 17.3 `harness-ai`

新增：

```text
AudioStreamCallback.java
VoiceOutputCoordinator.java
SpeechTextAccumulator.java
SpeechChunker.java
TtsChunkQueue.java
OrderedAudioEmitter.java
DocumentVisualRepairService.java
RepairPromptFactory.java
RepairResultValidator.java
```

调整：

```text
ModelProviderFactory.java
FallbackChatModel.java
VoiceModelProvider.java
OpenAiVoiceModelProvider.java
NoOpVoiceModelProvider.java
ReActEngine.java
```

### 17.4 `harness-agent`

调整：

```text
AgentOrchestrator.java
```

### 17.5 `harness-preprocess`

调整：

```text
KnowledgeIngestService.java
FileStorageService.java
```

新增：

```text
DocumentRepairMerger.java
DocumentRepairCache.java
```

### 17.6 `harness-env`

调整：

```text
EnvKey.java
EnvConfig.java
```

### 17.7 `harness-server`

调整：

```text
KnowledgeUploadHandler.java
ChatHandler.java
Main.java
```

新增或补齐：

```text
AudioTranscriptionHandler.java
AudioSpeechHandler.java
AudioCapabilityHandler.java
```

---

## 18. 分阶段实施顺序

### Phase 1：结构化抽取基础

- [ ] 新增结构化文档模型。
- [ ] 新增 `StructuredDocumentExtractor` 和 Registry。
- [ ] PDF 抽取保留页码、阅读顺序和坐标。
- [ ] `StructuredDocumentFormatter` 输出与现有切片器兼容的文本。
- [ ] 正常文档入库行为保持不变。

### Phase 2：异常检测

- [ ] 实现纯规则 `TextCorruptionDetector`。
- [ ] 增加中英文、代码、公式、乱码测试集。
- [ ] 输出异常 Block、原因、分数和范围。
- [ ] 验证正常文档不会调用视觉模型。

### Phase 3：PDF 局部视觉修复

- [ ] 实现 PDF 区域渲染。
- [ ] 实现主模型图片能力校验。
- [ ] 实现 JSON Schema 修复请求和响应。
- [ ] 实现按 `blockId` 校验和回填。
- [ ] 实现修复缓存。
- [ ] 修复失败时阻止 Embedding 和向量写入。

### Phase 4：Office 扩展

- [ ] 实现 PPTX TextShape/TableShape/PictureShape 抽取。
- [ ] 接通知识库 PPTX 上传能力。
- [ ] 补齐 DOCX 表格、图片和结构顺序。
- [ ] 补齐 XLSX 公式、显示值和单元格坐标。
- [ ] 复用已有文档/图片配置，清除重复或无效配置代码。

### Phase 5：API 语音 Provider 扩展

- [ ] 保留统一 `VoiceModelProvider`，补充 `streamSynthesize` 和能力 DTO。
- [ ] 在 `OpenAiVoiceModelProvider` 实现 ASR、普通 TTS 和流式 TTS。
- [ ] 接通请求取消、HTTP 连接复用、超时和结构化错误。
- [ ] 对流式格式、响应格式和模型能力做显式校验。
- [ ] `NoOpVoiceModelProvider` 对未配置能力明确报错。
- [ ] 删除整段缓存和旧兼容分支中的重复、无用代码。

### Phase 6：最终答案流式 TTS

- [ ] 在 ReAct 工具阶段之后增加显式最终答案生成阶段。
- [ ] 最终答案阶段禁用工具，并只将该阶段 Token 送入 TTS。
- [ ] 实现 `SpeechTextAccumulator` 和可配置 `SpeechChunker`。
- [ ] 实现有界 `TtsChunkQueue`、短句合并、顺序编号和背压处理。
- [ ] 实现音频增量 SSE 事件和请求取消。
- [ ] 验证首个稳定短句生成后即发起 TTS，早于文本 `DONE`。

### Phase 7：API、前端与资源控制

- [ ] 补齐转写、整句预览、能力查询和聊天流式 API。
- [ ] 前端类型与后端 DTO、枚举和 SSE 事件字段对齐。
- [ ] 增加响应式录音、转写、文本流和顺序播放控件。
- [ ] 增加音频大小、时长、文本长度、队列、并发和超时限制。
- [ ] 增加取消、审计与语音通道错误展示。
- [ ] 若新增系统自有音色列表，查询使用游标分页。

### Phase 8：回归、清理与文档

- [ ] 跑全量相关模块测试。
- [ ] 删除迁移后废弃的旧语音实现和扁平抽取死代码。
- [ ] 更新 `.env.example`。
- [ ] 更新 README 中的 API ASR/TTS 配置与流式语音说明。
- [ ] 不修改或新增 `CLAUDE.md` 一类文件，除非收到显式指令。

---

## 19. 最终验收清单

- [ ] 正常 PDF/Office 文档只走原生解析。
- [ ] 仅异常区域调用主多模态 LLM。
- [ ] 无新增 `HARNESS_KNOWLEDGE_VISUAL_MODE`。
- [ ] 原始文本、修复文本、区域哈希和模型版本可审计。
- [ ] 修复结果按 Block 回填后再进入原切片链路。
- [ ] 视觉修复失败不会留下半批向量。
- [ ] PPTX 支持状态与上传白名单一致。
- [ ] 现有知识类型开关真正生效。
- [ ] ASR 与 TTS 统一使用现有 `VoiceModelProvider` 和远端 API 配置。
- [ ] 不引入语音模型、原生库、Python 服务、GPU 依赖或额外容器。
- [ ] ASR/TTS 不注册为 LLM Tool。
- [ ] 最终答案首个稳定短句产生后开始 TTS，不等待全文完成。
- [ ] 工具调用轮次和中间 ReAct Token 不进入 TTS。
- [ ] 音频事件带顺序号并在前端按序播放。
- [ ] 用户取消会同时终止 LLM、TTS 请求和播放队列。
- [ ] 语音 API 不支持流式时明确报错，不静默退回全文 TTS。
- [ ] 所有实际存在的列表查询使用游标分页。
- [ ] 前端响应式布局和数据类型与后端 DTO 一致。
- [ ] 删除迁移产生的重复代码和死代码。
