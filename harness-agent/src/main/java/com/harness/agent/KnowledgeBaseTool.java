package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.ai.model.RerankModelProvider;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.preprocess.ContextBuilder;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.tool.Tool;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 内部知识库检索工具，将 RAG 检索流程暴露给 ReAct 引擎。
 * <p>
 * 模型只看到一个参数 {@code query}，感知不到 rewrite 开关的存在。
 * rewrite 决策完全由工具执行层根据 ReActStep 历史驱动：
 * <ul>
 *   <li>第 1 次调用 → fast path：单 query 直接检索</li>
 *   <li>第 2 次及以后 → 自动升级为 rewrite 全套流水线（5 个 query）</li>
 * </ul>
 * <p>
 * rewrite 全套流水线：一次 LLM 调用生成 multi-query(3) + step-back(1) + hyde(1) = 5 个查询，
 * 每个查询检索 top 3 片段，合并去重后 rerank 返回最终结果。
 */
public class KnowledgeBaseTool implements Tool {

    public static final String TOOL_NAME = "knowledge_base_search";
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Relevance score threshold below which results are considered LOW_RELEVANCE. */
    private static final double RELEVANCE_THRESHOLD =
            EnvConfig.get().getDouble(EnvKey.RAG_SCORE_THRESHOLD, 0.7);

    private static final String COMBINED_REWRITE_PROMPT = """
            请为以下检索问题生成 5 个不同的查询版本，帮助从知识库中检索到最全面的相关文档。

            要求：
            1. 前 3 个：用不同的措辞或角度表达相同的意思（覆盖不同表述方式）
            2. 第 4 个：将问题抽象为更通用的分类或背景层面的问题（step-back）
            3. 第 5 个：写一段假设性回答（约 2-3 句话），模拟知识库中可能出现的文档段落（不需要准确）
            4. 每行一个查询，不要编号，不要加任何解释

            原始问题：%s""";

    private final ContextBuilder contextBuilder;
    private final ChatModel chatModel;

    public KnowledgeBaseTool(EmbeddingModelProvider embeddingProvider,
                             RerankModelProvider rerankModelProvider,
                             ChatModelProvider chatModelProvider) {
        this.contextBuilder = new ContextBuilder(rerankModelProvider, embeddingProvider, chatModelProvider);
        this.chatModel = chatModelProvider != null ? chatModelProvider.chatModel() : null;
        log.info("[KnowledgeBaseTool] initialized");
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                TOOL_NAME,
                "Search the knowledge base for relevant documents. " +
                        "Use this tool when the user's question may benefit from internal knowledge, documentation, or reference materials. " +
                        "The query MUST be a complete, standalone question that can be understood without any conversation context. " +
                        "Do NOT use pronouns or references like 'this', 'that', 'the above', 'mentioned earlier'. " +
                        "Example good query: 'What is the maximum file upload size limit?' " +
                        "Example bad query: 'What is the limit for this?'",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("query",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description",
                                                                "A complete, standalone search query. Must not contain pronouns or context-dependent references.")))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                                mapper.createArrayNode().add("query"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String query = arguments.has("query") ? arguments.get("query").asText() : null;
        if (query == null || query.isBlank()) {
            throw new ToolExecutionException(TOOL_NAME, "Missing required parameter: query");
        }

        // 读取 ReActStep 历史，判断是否需要升级为 rewrite 模式
        // 模型全程不知道有这个开关——由引擎根据上一次的 InspectionStatus 自动决定
        ReActStep.InspectionResult.InspectionStatus lastStatus =
                ReActStep.getLastInspectionStatus(TOOL_NAME);
        boolean forceRewrite = lastStatus == ReActStep.InspectionResult.InspectionStatus.INSUFFICIENT;

        log.info("[KnowledgeBaseTool] query=\"{}\", lastStatus={}, forceRewrite={}",
                truncate(query), lastStatus, forceRewrite);

        try {
            ContextBuilder.ContextResult result;
            if (forceRewrite && chatModel != null) {
                result = executeWithRewrite(query);
            } else {
                result = contextBuilder.buildRagForTool(query, false);
            }

            if (!result.hasContext()) {
                if (forceRewrite) {
                    // Rewrite 全套流水线已经用过但仍然无结果 — 工具的升级手段用尽
                    ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
                } else {
                    // Fast path 无结果 — 还有 rewrite 升级手段可以用
                    ToolResult.setCurrentStatus(ToolResult.ResultStatus.ESCALATING);
                }
                return "No results found in knowledge base for: " + query;
            }

            // 检查 rerank 最高分，低于阈值视为"查到了但不相关"
            double topScore = result.topScore();
            if (topScore > 0 && topScore < RELEVANCE_THRESHOLD) {
                log.info("[KnowledgeBaseTool] topScore={} < threshold={}, marking LOW_RELEVANCE",
                        String.format("%.4f", topScore), RELEVANCE_THRESHOLD);
                if (forceRewrite) {
                    // Rewrite 后仍然低相关 — 已无更多升级手段
                    ToolResult.setCurrentStatus(ToolResult.ResultStatus.LOW_RELEVANCE);
                } else {
                    // Fast path 低相关 — 下一轮会触发 rewrite
                    ToolResult.setCurrentStatus(ToolResult.ResultStatus.ESCALATING);
                }
                return result.contextBlock();
            }

            log.info("[KnowledgeBaseTool] Found {} RAG hits, context={} chars, topScore={}, rewrite={}",
                    result.ragHitIds().size(), result.contextBlock().length(),
                    String.format("%.4f", topScore), forceRewrite);
            ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            return result.contextBlock();
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("[KnowledgeBaseTool] execution failed: {}", e.getMessage(), e);
            throw new ToolExecutionException("knowledge_base_search",
                    "Knowledge base search failed: " + e.getMessage());
        }
    }

    /**
     * 带查询改写的 RAG 执行。
     * 调用主 LLM 一次生成 5 个查询（multi-query 3 + step-back 1 + hyde 1），
     * 然后通过 ContextBuilder 执行完整的检索流程。
     */
    private ContextBuilder.ContextResult executeWithRewrite(String originalQuery) {
        List<String> queries = generateRewrittenQueries(originalQuery);
        if (queries.isEmpty()) {
            log.warn("[KnowledgeBaseTool] Rewrite produced no queries, falling back to original");
            return contextBuilder.buildRagForTool(originalQuery, false);
        }

        log.info("[KnowledgeBaseTool] Rewrite generated {} queries", queries.size());
        return contextBuilder.buildRagWithQueries(queries);
    }

    /**
     * 调用主 LLM 一次生成 5 个改写查询。
     * 失败时返回空列表，调用方降级为原始查询。
     */
    private List<String> generateRewrittenQueries(String originalQuery) {
        try {
            String prompt = String.format(COMBINED_REWRITE_PROMPT, originalQuery);
            String response = chatModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build()).aiMessage().text();

            if (response == null || response.isBlank()) {
                return List.of();
            }

            List<String> queries = new ArrayList<>();
            for (String line : response.strip().split("\\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    queries.add(trimmed);
                }
            }

            if (queries.size() < 2) {
                log.warn("[KnowledgeBaseTool] Rewrite produced only {} queries, discarding", queries.size());
                return List.of();
            }
            if (queries.size() > 6) {
                queries = queries.subList(0, 6);
            }

            return queries;
        } catch (Exception e) {
            log.warn("[KnowledgeBaseTool] Rewrite LLM call failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String truncate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
