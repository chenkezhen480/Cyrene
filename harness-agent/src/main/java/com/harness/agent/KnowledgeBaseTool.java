package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.provider.ChatModelProvider;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.provider.RerankModelProvider;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.agent.context.ContextBuilder;
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
 * rewrite 决策完全由工具执行层根据检索分数和 ReActStep 历史驱动：
 * <ul>
 *   <li>第 1 次调用 → fast path：单 query 直接检索</li>
 *   <li>硬阈值以下但达到候选下限 → 下一次调用隐式升级为 rewrite 流水线</li>
 *   <li>完全无关或已经升级过 → 不再触发 rewrite</li>
 * </ul>
 * <p>
 * rewrite 全套流水线：一次 LLM 调用生成 multi-query(3) + step-back(1) + hyde(1) = 5 个查询，
 * 每个查询按配置的 topK 检索，合并去重后 rerank 返回最终结果。
 */
public class KnowledgeBaseTool implements Tool {

    public static final String TOOL_NAME = "knowledge_base_search";
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

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
    private final RetrievalEscalationPolicy escalationPolicy;

    public KnowledgeBaseTool(EmbeddingModelProvider embeddingProvider,
                             RerankModelProvider rerankModelProvider,
                             ChatModelProvider chatModelProvider) {
        this.contextBuilder = new ContextBuilder(rerankModelProvider, embeddingProvider);
        this.chatModel = chatModelProvider != null ? chatModelProvider.chatModel() : null;
        this.escalationPolicy = RetrievalEscalationPolicy.fromEnvironment();
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

        ToolResult.ResultStatus lastResultStatus = ReActStep.getLastToolResultStatus(TOOL_NAME);
        boolean forceRewrite = lastResultStatus == ToolResult.ResultStatus.ESCALATING;
        boolean escalationAlreadyUsed = ReActStep.hasToolResultStatus(
                TOOL_NAME, ToolResult.ResultStatus.ESCALATING);

        log.info("[KnowledgeBaseTool] query=\"{}\", lastResultStatus={}, forceRewrite={}",
                truncate(query), lastResultStatus, forceRewrite);

        try {
            ContextBuilder.ContextResult result;
            if (forceRewrite && chatModel != null) {
                result = executeWithRewrite(query);
            } else {
                result = contextBuilder.buildRagForTool(query);
            }

            ToolResult.ResultStatus resultStatus = escalationPolicy.evaluate(
                    result, forceRewrite, escalationAlreadyUsed);
            ToolResult.setCurrentStatus(resultStatus);

            if (!result.hasContext()) {
                log.info("[KnowledgeBaseTool] No accepted results: bestObservedScore={}, status={}",
                        String.format("%.4f", result.bestObservedScore()), resultStatus);
                return "No results found in knowledge base for: " + query;
            }

            double topScore = result.topScore();
            if (resultStatus != ToolResult.ResultStatus.SUCCESS) {
                log.info("[KnowledgeBaseTool] Result quality requires attention: topScore={}, status={}",
                        String.format("%.4f", topScore), resultStatus);
                return result.contextBlock();
            }

            log.info("[KnowledgeBaseTool] Found {} RAG hits, context={} chars, topScore={}, rewrite={}",
                    result.ragHitIds().size(), result.contextBlock().length(),
                    String.format("%.4f", topScore), forceRewrite);
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
            return contextBuilder.buildRagForTool(originalQuery);
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
