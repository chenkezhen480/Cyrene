# TODO5 — 回归初衷·极简版：向量数据库抽象 + Milvus + 切块合并 + 删除 CLI + LLM 并发保护 + ReAct 反思

> **设计哲学**：框架只做核心逻辑（Agent 编排、RAG、LLM 保护），把高并发限流、鉴权、排队交给基础设施（Nginx/APISIX/K8s）。面向**通用 AI 应用开发架构**，不面向生产环境的流量治理。

---

## 一、环境变量统一

### 1.1 EnvKey 变更

- [ ] **新增通用变量**：

  ```java
  RAG_USER       = "HARNESS_RAG_USER"        // 数据库用户名（PG）/ 无（Milvus）
  RAG_PASS       = "HARNESS_RAG_PASS"        // 数据库密码（PG）/ 无（Milvus）
  RAG_EMBED_DIM  = "HARNESS_RAG_EMBED_DIM"   // 向量维度，默认 1536
  ```

- [ ] **废弃 PG 专用变量**（保留常量但标记 `@Deprecated`，代码里做个兼容读取即可）：

  ```java
  @Deprecated RAG_PG_URL       = "HARNESS_RAG_PG_URL"
  @Deprecated RAG_PG_USER      = "HARNESS_RAG_PG_USER"
  @Deprecated RAG_PG_PASS      = "HARNESS_RAG_PG_PASS"
  @Deprecated RAG_PG_TABLE     = "HARNESS_RAG_PG_TABLE"
  @Deprecated RAG_PG_EMBED_DIM = "HARNESS_RAG_PG_EMBED_DIM"
  ```

- [ ] **新增 Milvus 专用变量**：

  ```java
  RAG_MILVUS_METRIC_TYPE = "HARNESS_RAG_MILVUS_METRIC_TYPE"  // COSINE/L2/IP，默认 COSINE
  ```

- [ ] **新增通用检索变量**：

  ```java
  RAG_BM25_WEIGHT = "HARNESS_RAG_BM25_WEIGHT"  // BM25 混合检索权重，默认 0.3
  ```

- [ ] **废弃路由相关变量**（检索策略由 Provider 内聚，不再需要外部开关）：

  ```java
  @Deprecated RAG_MULTI_ROUTE       = "HARNESS_RAG_MULTI_ROUTE"
  @Deprecated RAG_FULLTEXT_ENABLED  = "HARNESS_RAG_FULLTEXT_ENABLED"
  @Deprecated RAG_FULLTEXT_LANG     = "HARNESS_RAG_FULLTEXT_LANG"
  ```

### 1.2 变量映射关系

| 用途 | PG 实现读取 | Milvus 实现读取 | 通用变量 |
|------|------------|----------------|---------|
| 连接地址 | `HARNESS_RAG_URL` | `HARNESS_RAG_URL` | `RAG_URL` |
| 认证 | `HARNESS_RAG_USER` + `HARNESS_RAG_PASS` | `HARNESS_RAG_API_KEY` | `RAG_USER`/`RAG_PASS`/`RAG_API_KEY` |
| 集合/表名 | `HARNESS_RAG_COLLECTION` | `HARNESS_RAG_COLLECTION` | `RAG_COLLECTION` |
| 返回数 | `HARNESS_RAG_TOP_K` | `HARNESS_RAG_TOP_K` | `RAG_TOP_K` |
| 阈值 | `HARNESS_RAG_SCORE_THRESHOLD` | `HARNESS_RAG_SCORE_THRESHOLD` | `RAG_SCORE_THRESHOLD` |
| 向量维度 | `HARNESS_RAG_EMBED_DIM` | `HARNESS_RAG_EMBED_DIM` | `RAG_EMBED_DIM` |
| BM25 权重 | `HARNESS_RAG_BM25_WEIGHT` | `HARNESS_RAG_BM25_WEIGHT` | `RAG_BM25_WEIGHT` |

---

## 二、向量数据库抽象层

### 2.1 通用接口

- [ ] **新建 `VectorStore` 接口** — `harness-preprocess/.../rag/VectorStore.java`

  ```java
  public interface VectorStore {
      // 1. 基础管理
      void upsert(String collection, List<Document> docs);
      void delete(String collection);
      boolean deleteById(String id);

      // 2. 查询能力
      Document getById(String id);
      List<Document> listByCollection(String collection);

      // 3. 检索能力（按能力实现，不支持的抛 UnsupportedOperationException）
      List<Document> searchVector(String collection, float[] embedding, int topK);
      List<Document> searchKeyword(String collection, String query, int topK);

      // 4. 混合检索（天然支持的库如 Milvus 实现此方法，PG 在内部并发调 Vector+Keyword 做融合）
      List<Document> searchHybrid(String collection, String query, float[] embedding, int topK);

      // 5. Provider 名称
      String providerName();
  }

  public record Document(String id, String content, String source, double score, Map<String, Object> metadata) {}
  ```

  > **为什么需要 `getById` / `listByCollection` / `deleteById`**：
  > - `getById` — `SemanticContextRetriever` 的 lookback 回看依赖按 ID 查询 chunk（`fetchById(prevId)`）
  > - `listByCollection` — `GET /api/knowledge/{collection}` 端点依赖列出集合下所有文档
  > - `deleteById` — `DELETE /api/knowledge/{collection}/{documentId}` 端点依赖按 ID 删除单个文档
  >
  > Milvus 实现中 `getById` 可通过 `query()` + ID 过滤实现；`listByCollection` 可通过 `query()` + collection 过滤实现。

### 2.2 PgConnectionPool 改造

- [ ] **PgConnectionPool 改读通用变量** — `harness-env/.../PgConnectionPool.java`

  ```java
  // 通用变量优先，PG 专用变量作为 fallback
  String url = cfg.getString(EnvKey.RAG_URL);
  if (url == null || url.trim().isEmpty() || (!url.startsWith("jdbc:") && !url.startsWith("postgres"))) {
      url = cfg.getString(EnvKey.RAG_PG_URL, "jdbc:postgresql://localhost:5432/agent");
  }
  String user = cfg.getString(EnvKey.RAG_USER, cfg.getString(EnvKey.RAG_PG_USER, "postgres"));
  String pass = cfg.getString(EnvKey.RAG_PASS, cfg.getString(EnvKey.RAG_PG_PASS, ""));
  ```

### 2.3 PgVectorStore 实现

- [ ] **重构 `PgVectorRagRetriever` → `PgVectorStore`** — implements `VectorStore`

  - `searchVector()` — 现有向量检索逻辑
  - `searchKeyword()` — 现有 tsvector 全文检索逻辑（从 FulltextRoute 提取）
  - `searchHybrid()` — 内部用 `CompletableFuture` 并发调用 `searchVector` + `searchKeyword`，然后在内存中做加权分数合并
  - `getById()` — 现有 `retrieveById()` 逻辑（`SemanticContextRetriever` lookback 依赖）
  - `listByCollection()` — 现有 `listByCollection()` 逻辑（`KnowledgeManagementHandler` 依赖）
  - `deleteById()` — 现有 `deleteById()` 逻辑（`KnowledgeManagementHandler` 依赖）
  - `delete()` — 现有 `deleteByCollection()` 逻辑
  - 构造函数改读通用变量（PG 专用变量作为 fallback）

### 2.4 下游消费者类型替换

- [ ] `SemanticContextRetriever` — 构造参数 → `VectorStore`
- [ ] `ContextBuilder` — 字段类型 → `VectorStore`
- [ ] `RagRetriever` — 字段类型 → `VectorStore`
- [ ] `KnowledgeIngestService` — 构造参数 → `VectorStore`
- [ ] `KnowledgeManagementHandler` — 构造参数 → `VectorStore`
- [ ] `AgentOrchestrator` — 返回类型 → `VectorStore`
- [ ] `harness-server/Main.java` — 变量类型 → `VectorStore`

---

## 三、Milvus 向量数据库实现

### 3.1 依赖

- [ ] **添加 milvus-sdk-java** — `harness-preprocess/pom.xml` + 根 `pom.xml`

  ```xml
  <dependency>
      <groupId>io.milvus</groupId>
      <artifactId>milvus-sdk-java</artifactId>
      <version>2.5.4</version>
  </dependency>
  ```

  > **依赖冲突提示**：`milvus-sdk-java 2.5.x` 强依赖特定版本的 gRPC、Netty、Guava、Protobuf。建议先在一个空模块引入并跑通 `mvn clean compile`，排除冲突后再写业务代码。

### 3.2 MilvusVectorStore

- [ ] **新建 `MilvusVectorStore.java`** — `harness-preprocess/.../rag/MilvusVectorStore.java`

  ```java
  public class MilvusVectorStore implements VectorStore {
      private final MilvusServiceClient client;
      private final String collection;
      private final int embedDim;
      private final double bm25Weight;

      public MilvusVectorStore(Config cfg) {
          this.client = new MilvusServiceClient(
              ConnectParam.newBuilder()
                  .withUri(cfg.getString(EnvKey.RAG_URL, "http://localhost:19530"))
                  .withToken(cfg.getString(EnvKey.RAG_API_KEY, ""))
                  .build());
          this.collection = cfg.getString(EnvKey.RAG_COLLECTION, "knowledge_documents");
          this.embedDim = cfg.getInt(EnvKey.RAG_EMBED_DIM, 1536);
          this.bm25Weight = cfg.getDouble(EnvKey.RAG_BM25_WEIGHT, 0.3);
      }

      @Override
      public List<Document> searchVector(String collection, float[] embedding, int topK) {
          // SearchRequest + COSINE metric
      }

      @Override
      public List<Document> searchKeyword(String collection, String query, int topK) {
          // BM25 全文检索（Milvus 2.5 原生）
      }

      @Override
      public List<Document> searchHybrid(String collection, String query, float[] embedding, int topK) {
          // HybridSearchRequest 组合向量 + BM25，加权分数合并
          // 单路惩罚防护：如果文档只在一路召回，权重自动提升为 1.0
      }

      @Override
      public Document getById(String id) {
          // query() + ID 过滤，返回单个 Document
      }

      @Override
      public List<Document> listByCollection(String collection) {
          // query() + collection 过滤，返回文档列表（不含向量字段）
      }

      @Override
      public boolean deleteById(String id) {
          // delete() + ID 过滤
      }

      @Override
      public String providerName() { return "milvus"; }
  }
  ```

### 3.3 工厂类

- [ ] **新建 `VectorStoreFactory.java`** — `harness-preprocess/.../rag/VectorStoreFactory.java`

  ```java
  public class VectorStoreFactory {
      public static VectorStore create(Config cfg) {
          String provider = cfg.getString(EnvKey.RAG_PROVIDER, "pgvector");
          return switch (provider.toLowerCase()) {
              case "pgvector" -> new PgVectorStore(cfg);
              case "milvus"   -> new MilvusVectorStore(cfg);
              default -> throw new IllegalArgumentException("Unknown RAG provider: " + provider);
          };
      }
  }
  ```

### 3.4 删除不再需要的类

- [ ] `FulltextRoute.java` — 全文检索内聚到 PgVectorStore.searchKeyword()
- [ ] `PgVectorRoute.java` — 路由模式废弃
- [ ] `RetrievalRoute.java` — 接口废弃
- [ ] `RetrievalRouteFactory.java` — 由 VectorStoreFactory 替代
- [ ] `MultiRouteRetriever.java` — 多路合并由 Provider 内部处理
- [ ] `MilvusVectorRoute.java` — 路由模式废弃
- [ ] `MilvusBm25Route.java` — BM25 内聚到 MilvusVectorStore.searchHybrid()

---

## 四、删除 CLI 模块

- [ ] 删除 `harness-cli/` 整个目录
- [ ] 根 `pom.xml` 移除 `<module>harness-cli</module>`
- [ ] `EnvKey.java` 删除 `CLI_ENABLED`
- [ ] `.env.example` 删除 `HARNESS_CLI_ENABLED=false`
- [ ] `CLAUDE.md` 移除 CLI 相关内容
- [ ] `README.md` 移除 CLI 相关内容

---

## 五、LLM 并发保护（极简方案）

> **核心思路**：框架只保护上游 LLM API 的并发限制（如 OpenAI 的 RPM/TPM），不需要在 HTTP 层做请求排队和单用户限流——这些应该交给部署在最外层的 Nginx/APISIX 等 API 网关。

### 5.1 SemaphoreChatModel

- [ ] **新建 `SemaphoreChatModel.java`** — `harness-ai/.../model/impl/SemaphoreChatModel.java`

  ```java
  /**
   * ChatModel 装饰器：用 Semaphore 控制发往 LLM API 的最大并发数。
   * 无论前端涌入多少 HTTP 请求，框架内部永远只有 N 个线程在同时调用 LLM API。
   * 获取不到许可的请求利用 Java 21 虚拟线程自然阻塞等待。
   */
  public class SemaphoreChatModel implements ChatModel {
      private final ChatModel delegate;
      private final Semaphore semaphore;

      public SemaphoreChatModel(ChatModel delegate, int maxConcurrent) {
          this.delegate = delegate;
          this.semaphore = new Semaphore(maxConcurrent, true);  // fair=true
      }

      @Override
      public ChatResponse chat(ChatRequest request) {
          try {
              semaphore.acquire();
              try {
                  return delegate.chat(request);
              } finally {
                  semaphore.release();
              }
          } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new RuntimeException("Interrupted while waiting for LLM API permit", e);
          }
      }

      public int availablePermits() { return semaphore.availablePermits(); }
      public int activeRequests() { return semaphore.getQueueLength(); }
  }
  ```

### 5.2 SemaphoreStreamingChatModel

- [ ] **新建 `SemaphoreStreamingChatModel.java`** — `harness-ai/.../model/impl/SemaphoreStreamingChatModel.java`

  ```java
  /**
   * StreamingChatModel 装饰器：同样用 Semaphore 控制并发。
   * 信号量在 onComplete/onError 回调中释放。
   */
  public class SemaphoreStreamingChatModel implements StreamingChatModel {
      private final StreamingChatModel delegate;
      private final Semaphore semaphore;

      public SemaphoreStreamingChatModel(StreamingChatModel delegate, int maxConcurrent) {
          this.delegate = delegate;
          this.semaphore = new Semaphore(maxConcurrent, true);
      }

      @Override
      public void chat(ChatRequest request, StreamingChatResponseHandler handler, CancellationToken cancellationToken) {
          try {
              semaphore.acquire();
          } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              handler.onError(e);
              return;
          }

          delegate.chat(request, new StreamingChatResponseHandler() {
              @Override public void onPartialResponse(String token) { handler.onPartialResponse(token); }
              @Override public void onCompleteResponse(ChatResponse response) {
                  try { handler.onCompleteResponse(response); } finally { semaphore.release(); }
              }
              @Override public void onError(Throwable error) {
                  try { handler.onError(error); } finally { semaphore.release(); }
              }
          }, cancellationToken);
      }
  }
  ```

### 5.3 集成

- [ ] **修改 `ModelProviderFactory.java`** — 创建 ChatModel 时包装 Semaphore 层：

  ```java
  int maxConcurrent = cfg.getInt(EnvKey.MODEL_API_MAX_CONCURRENT, 10);

  ChatModel raw = provider.chatModel();
  ChatModel semaphore = new SemaphoreChatModel(raw, maxConcurrent);
  ChatModel retrying = new RetryingChatModel(semaphore);

  StreamingChatModel rawStream = provider.streamingChatModel();
  StreamingChatModel semaphoreStream = new SemaphoreStreamingChatModel(rawStream, maxConcurrent);
  StreamingChatModel retryingStream = new RetryingStreamingChatModel(semaphoreStream);
  ```

### 5.4 环境变量

- [ ] **EnvKey.java 新增**：

  ```java
  MODEL_API_MAX_CONCURRENT = "HARNESS_MODEL_API_MAX_CONCURRENT"  // LLM API 最大并发，默认 10
  ```

---

## 六、切块合并

### 6.1 TextChunker 合并方法

- [ ] **TextChunker 新增 `mergeSmallChunks()`** — `harness-input/.../TextChunker.java`

  ```java
  /**
   * 单遍贪心合并：相邻 chunk 若合计 token < chunkTokenSize 则合并。
   * 硬边界保护：遇到 Markdown 标题或分割线开头的 chunk 则强制断开。
   */
  public static List<String> mergeSmallChunks(List<String> chunks, int chunkTokenSize) {
      if (chunks == null || chunks.size() <= 1) return chunks;
      List<String> merged = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      int currentTokens = 0;

      for (String chunk : chunks) {
          int chunkTokens = estimateTokens(chunk);
          boolean isHardBoundary = startsWithHeading(chunk) || startsWithDivider(chunk);

          if (currentTokens > 0 && currentTokens + chunkTokens <= chunkTokenSize && !isHardBoundary) {
              current.append("\n\n").append(chunk);
              currentTokens += chunkTokens;
          } else {
              if (currentTokens > 0) merged.add(current.toString().strip());
              current = new StringBuilder(chunk);
              currentTokens = chunkTokens;
          }
      }
      if (currentTokens > 0) merged.add(current.toString().strip());
      return merged;
  }

  private static boolean startsWithHeading(String chunk) {
      if (chunk == null || chunk.isEmpty()) return false;
      String firstLine = chunk.stripLeading();
      int nl = firstLine.indexOf('\n');
      if (nl > 0) firstLine = firstLine.substring(0, nl);
      // Markdown 标题：# ## ### 等
      if (firstLine.matches("^#{1,6}\\s+.*")) return true;
      // 数字编号标题：1. / 1、 / (1) / 第X章 / 第X节
      if (firstLine.matches("^(第.+[章节]|\\d+[.、)）]\\s*).*")) return true;
      return false;
  }

  private static boolean startsWithDivider(String chunk) {
      if (chunk == null || chunk.isEmpty()) return false;
      String trimmed = chunk.stripLeading();
      return trimmed.startsWith("---") || trimmed.startsWith("***") || trimmed.startsWith("___");
  }
  ```

### 6.2 KnowledgeIngestService 集成

- [ ] 切分后调用 `mergeSmallChunks(chunks, 1024)`
- [ ] 日志记录合并前后 chunk 数量

---

## 七、ReAct 自我反思（极简方案）

> **核心思路**：把反思变成一次普通的 Prompt 注入，利用 LLM 原生的 Context 理解能力，不需要独立的 Reflector 类、不需要 JSON 解析、不需要 SystemHint 覆盖机制。

### 7.1 反思注入

- [ ] **修改 `ReActEngine.java`** — 在 ReAct 循环中，每隔 N 步注入一条反思消息

  ```java
  private void maybeInjectReflection(List<ChatMessage> messages, int step, int reflectionInterval, String userInput) {
      if (reflectionInterval <= 0) return;
      if (step % reflectionInterval != 0 || step == 0) return;

      String reflectionPrompt = """
          [System Reflection]
          Review the steps taken so far.
          1. Are we stuck in a loop or repeating the same tool calls?
          2. Are we actually making progress toward the user's original goal?
          Think briefly and adjust your next action. If the task is impossible, output the final answer now.

          Original task: %s
          Steps executed so far: %d
          """.formatted(userInput, step);

      messages.add(UserMessage.from(reflectionPrompt));
  }
  ```

  > **安全性说明**：`messages` 是 `ReActEngine.execute()` 的局部变量，每次调用新建。`AgentOrchestrator` 中持久化用户消息（`messageWriteWorker.submit`）在 ReAct 调用**之前**，保存 AI 回复在**之后**，两者写的是 `enhancedText` 和 `result.output()`，不是 ReActEngine 内部的 messages 列表。因此反思消息**不会**进入数据库或影响压缩统计。这与现有的 inspection hint 注入（`messages.add(UserMessage.from(hint))`）机制一致，已在生产环境验证。

### 7.2 防死循环（启发式 Inspector 保留）

- [ ] **修改 `Inspector.java`** — 保留最简单的循环检测

  ```java
  // 连续 3 次调用相同工具且参数一致 → 强制终止并返回当前结果
  if (isRepeatedCall(history, 3)) {
      return InspectionStatus.LOOP_DETECTED;
  }
  ```

- [ ] **修改 `ReActStep.java`** — `InspectionStatus` 新增 `LOOP_DETECTED`

- [ ] **修改 `ReActEngine.java`** — `LOOP_DETECTED` 时强制停止 ReAct 循环，**收尾逻辑**：跳出循环后，用当前 messages 列表（不含工具消息）发起一次**不带工具的 LLM 调用**，让 LLM 基于已有信息做总结性回复，返回给用户。避免"循环戛然而止、用户收到空白或报错"。代码示意：

  ```java
  if (inspection.status() == InspectionStatus.LOOP_DETECTED) {
      log.warn("[L3-ReAct] Loop detected at iteration {}, forcing summary", i);
      // 去掉工具规格，让 LLM 只能输出文本
      ChatRequest summaryReq = ChatRequest.builder().messages(messages).build();
      ChatResponse summaryResp = activeModel.chat(summaryReq);
      return new ReActResult(summaryResp.aiMessage().text(), allSteps);
  }
  ```

### 7.3 环境变量

- [ ] **EnvKey.java 新增**：

  ```java
  REACT_REFLECTION_INTERVAL = "HARNESS_REACT_REFLECTION_INTERVAL"  // 反思间隔轮数，默认 3（0=禁用）
  ```

### 7.4 AgentContext 参数

- [ ] **AgentContext 新增 per-request 覆盖**：

  ```java
  // context JSON: {"reflectionInterval": 5} 覆盖 env 默认值
  public Integer reflectionInterval() {
      Object val = data.get("reflectionInterval");
      if (val instanceof Number n) return n.intValue();
      return null;  // use env default
  }
  ```

---

## 八、文件变更总览

### 新增文件（5 个）

| 文件 | 用途 |
|------|------|
| `harness-preprocess/.../rag/VectorStore.java` | 向量存储通用接口 |
| `harness-preprocess/.../rag/VectorStoreFactory.java` | 工厂类，根据 provider 创建对应实现 |
| `harness-preprocess/.../rag/MilvusVectorStore.java` | Milvus 实现（向量 + BM25 混合检索） |
| `harness-ai/.../model/impl/SemaphoreChatModel.java` | ChatModel 并发控制装饰器 |
| `harness-ai/.../model/impl/SemaphoreStreamingChatModel.java` | StreamingChatModel 并发控制装饰器 |

### 删除文件/目录（8 个）

| 目标 | 说明 |
|------|------|
| `harness-cli/` 整个目录 | CLI 模块 |
| `FulltextRoute.java` | 全文检索内聚到 PgVectorStore |
| `PgVectorRoute.java` | 路由模式废弃 |
| `RetrievalRoute.java` | 接口废弃 |
| `RetrievalRouteFactory.java` | 由 VectorStoreFactory 替代 |
| `MultiRouteRetriever.java` | 多路合并由 Provider 内部处理 |
| `MilvusVectorRoute.java` | 路由模式废弃 |
| `MilvusBm25Route.java` | BM25 内聚到 MilvusVectorStore |

### 修改文件（20 个）

| 文件 | 变更 |
|------|------|
| `EnvKey.java` | +3 通用变量，+1 Milvus 变量，+1 检索变量，+1 并发变量，+1 反思变量，废弃 5 个 PG 变量 + 3 个路由变量，删除 CLI_ENABLED |
| `PgConnectionPool.java` | 改读通用变量（PG 专用变量作为 fallback） |
| `PgVectorRagRetriever.java` | 重构为 `PgVectorStore implements VectorStore`，改读通用变量 |
| `RagRetriever.java` | 类型替换 + VectorStoreFactory |
| `ContextBuilder.java` | 类型替换 |
| `SemanticContextRetriever.java` | 构造参数类型替换 |
| `KnowledgeIngestService.java` | 类型替换 + 切块合并 |
| `KnowledgeManagementHandler.java` | 类型替换 |
| `AgentOrchestrator.java` | 返回类型替换 |
| `TextChunker.java` | +mergeSmallChunks() |
| `ModelProviderFactory.java` | 包装 SemaphoreChatModel/SemaphoreStreamingChatModel |
| `ReActEngine.java` | 反思注入 + Inspector 循环检测 |
| `ReActStep.java` | InspectionStatus 新增 LOOP_DETECTED |
| `AgentContext.java` | +reflectionInterval() accessor |
| `harness-server/Main.java` | 变量类型替换 |
| `harness-preprocess/pom.xml` | +milvus-sdk-java 依赖 |
| `pom.xml` | -harness-cli module，+milvus-sdk-java |
| `.env.example` | 删除 CLI_ENABLED，新增通用变量、Milvus 变量、并发变量 |
| `CLAUDE.md` | 移除 CLI 相关内容 |
| `README.md` | 移除 CLI 相关内容 |

---

## 九、实现顺序

```
Phase 1: 删除 CLI + 环境变量统一
  → Phase 2: LLM 并发保护（Semaphore 装饰器）
  → Phase 3: 向量数据库抽象层 + PG 改造
  → Phase 4: Milvus 实现
  → Phase 5: 切块合并
  → Phase 6: ReAct 反思注入
```

- **Phase 1**：独立操作，先清理 CLI 模块，统一环境变量
- **Phase 2**：新增 2 个类 + 修改 1 个文件，极简实现
- **Phase 3**：重构 PG，删除路由类，替换下游消费者
- **Phase 4**：新功能，需要本地 Milvus 2.5 实例测试
- **Phase 5**：独立于 Phase 4，可并行
- **Phase 6**：独立于 Phase 2-5，可并行

---

## 十、与 TODO4 的对比

| 维度 | TODO4（过度设计） | TODO5（极简版） |
|------|-------------------|-----------------|
| 请求队列 | RequestQueueManager + UserRateLimiter + AgentThreadPool（4 个线程池隔离） | **删除**，交给网关 |
| LLM 保护 | ThrottledChatModel + Semaphore + CancellationToken 绑定 | SemaphoreChatModel，纯 Semaphore |
| 并发控制环境变量 | 7 个 | **1 个**（MODEL_API_MAX_CONCURRENT） |
| Per-User 限流 | Caffeine 令牌桶 + AtomicLong | **删除**，交给网关 |
| 线程池 | AgentThreadPool（io/rag/audit/background 4 个池 + AbortPolicy/DiscardPolicy） | **删除**，用 Java 21 虚拟线程 |
| 向量接口 | VectorStoreRetriever（15 个方法） | VectorStore（9 个方法） |
| 反思机制 | Reflector + ReActConfig + SystemHint Map 覆盖 + JSON 解析容错 | **一行 Prompt 注入** |
| 新增文件数 | 14 个 | **5 个** |
| 修改文件数 | 23 个 | **20 个** |
| 代码量 | ~2000 行新增 | ~500 行新增 |

---

## 十一、验证方式

1. **编译验证**：`mvn clean compile`
2. **PG 回归**：配置 `HARNESS_RAG_PROVIDER=pgvector` + `HARNESS_RAG_URL=jdbc:postgresql://...`，行为不变
3. **Milvus 测试**：配置 `HARNESS_RAG_PROVIDER=milvus` + `HARNESS_RAG_URL=http://localhost:19530`
4. **混合检索测试**：验证向量 + BM25/全文检索结果正确合并
5. **Chunk 链表回看测试**：上传包含长段落的文件，验证 `SemanticContextRetriever` 的 lookback 功能正常（PG 和 Milvus 均需验证）
6. **知识库管理 API 测试**：验证 `GET /api/knowledge/{collection}` 列表、`DELETE /api/knowledge/{collection}/{documentId}` 按 ID 删除功能正常
7. **切块合并测试**：上传短段落文件，验证 chunk 数量减少；上传含标题的 Markdown，验证标题不被合并
8. **并发保护测试**：设置 `HARNESS_MODEL_API_MAX_CONCURRENT=2`，并发 5 个请求，验证只有 2 个同时调用 LLM
9. **反思测试**：设置 `HARNESS_REACT_REFLECTION_INTERVAL=2`，验证第 2/4/6... 轮注入反思消息
10. **循环检测测试**：连续 3 次相同工具+参数，验证 ReAct 循环强制停止并返回总结性回复（非空白/报错）
