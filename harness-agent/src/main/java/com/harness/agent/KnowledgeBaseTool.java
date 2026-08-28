package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.context.ContextBuilder;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.provider.ChatModelProvider;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.provider.RerankModelProvider;
import com.harness.tool.Tool;
import com.harness.tool.knowledge.KnowledgeSearchData;
import com.harness.tool.protocol.ToolEnvelope;
import com.harness.tool.protocol.ToolEnvelopeStatus;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Searches internal knowledge and returns stable document anchors. */
public class KnowledgeBaseTool implements Tool {

    public static final String TOOL_NAME = "knowledge_base_search";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTool.class);
    private static final ObjectMapper SPEC_MAPPER = new ObjectMapper();

    private static final String COMBINED_REWRITE_PROMPT = """
            请为以下检索问题生成 5 个不同的查询版本，帮助从知识库中检索到最全面的相关文档。

            要求：
            1. 前 3 个：用不同的措辞或角度表达相同的意思（覆盖不同表述方式）
            2. 第 4 个：将问题抽象为更通用的分类或背景层面的问题（step-back）
            3. 第 5 个：写一段假设性回答（约 2-3 句话），模拟知识库中可能出现的文档段落（不需要准确）
            4. 每行一个查询，不要编号，不要加任何解释

            原始问题：%s""";

    private final KnowledgeAccessService knowledgeAccess;
    private final java.util.function.Supplier<ChatModel> chatModelSupplier;
    private final RetrievalEscalationPolicy escalationPolicy;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseTool(
            EmbeddingModelProvider embeddingProvider,
            RerankModelProvider rerankModelProvider,
            ChatModelProvider chatModelProvider
    ) {
        this(
                new KnowledgeAccessService(rerankModelProvider, embeddingProvider),
                chatModelProvider != null ? chatModelProvider::chatModel : () -> null,
                RetrievalEscalationPolicy.fromEnvironment(),
                new ObjectMapper());
        log.info("[KnowledgeBaseTool] initialized");
    }

    public KnowledgeBaseTool(
            KnowledgeAccessService knowledgeAccess,
            ChatModelProvider chatModelProvider
    ) {
        this(
                knowledgeAccess,
                chatModelProvider != null ? chatModelProvider::chatModel : () -> null,
                RetrievalEscalationPolicy.fromEnvironment(),
                new ObjectMapper());
    }

    KnowledgeBaseTool(
            ContextBuilder contextBuilder,
            ChatModel chatModel,
            RetrievalEscalationPolicy escalationPolicy,
            ObjectMapper objectMapper
    ) {
        this(new KnowledgeAccessService(contextBuilder, 2),
                () -> chatModel, escalationPolicy, objectMapper);
    }

    KnowledgeBaseTool(
            KnowledgeAccessService knowledgeAccess,
            ChatModel chatModel,
            RetrievalEscalationPolicy escalationPolicy,
            ObjectMapper objectMapper
    ) {
        this(knowledgeAccess, () -> chatModel, escalationPolicy, objectMapper);
    }

    private KnowledgeBaseTool(
            KnowledgeAccessService knowledgeAccess,
            java.util.function.Supplier<ChatModel> chatModelSupplier,
            RetrievalEscalationPolicy escalationPolicy,
            ObjectMapper objectMapper
    ) {
        this.knowledgeAccess = Objects.requireNonNull(knowledgeAccess, "knowledgeAccess");
        this.chatModelSupplier = Objects.requireNonNull(chatModelSupplier, "chatModelSupplier");
        this.escalationPolicy = Objects.requireNonNull(escalationPolicy, "escalationPolicy");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolSpec spec() {
        boolean trustedCollection = knowledgeAccess.hasTrustedCollection();

        ObjectNode properties = SPEC_MAPPER.createObjectNode();
        properties.set("query", stringProperty(
                "Complete standalone query for knowledge-base search."));
        properties.set("limit", integerProperty(1, knowledgeAccess.maxSearchLimit()));
        if (!trustedCollection) {
            properties.set("collection", stringProperty(
                    "Optional logical collection. Defaults to the configured collection."));
        }
        ObjectNode schema = SPEC_MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("query");
        schema.put("additionalProperties", false);

        return new ToolSpec(
                TOOL_NAME,
                "Search internal knowledge and return relevant chunks with stable documentId and chunkIndex anchors. "
                        + "If a returned chunk lacks a definition, prerequisite, or following step, call knowledge_context_read with that anchor. "
                        + "Search queries must be complete standalone questions without conversation-dependent pronouns.",
                schema);
    }

    @Override
    public String execute(JsonNode arguments) {
        String query = textArgument(arguments, "query");
        if (query == null) {
            throw new ToolExecutionException(TOOL_NAME, "Missing required parameter: query");
        }
        String collection = effectiveCollection(arguments);
        int limit = integerArgument(arguments, "limit", knowledgeAccess.maxSearchLimit());
        if (limit < 1 || limit > knowledgeAccess.maxSearchLimit()) {
            throw new ToolExecutionException(
                    TOOL_NAME, "limit must be between 1 and " + knowledgeAccess.maxSearchLimit());
        }

        ToolResult.ResultStatus lastResultStatus = ReActStep.getLastToolResultStatus(TOOL_NAME);
        boolean forceRewrite = lastResultStatus == ToolResult.ResultStatus.ESCALATING;
        boolean escalationAlreadyUsed = ReActStep.hasToolResultStatus(
                TOOL_NAME, ToolResult.ResultStatus.ESCALATING);

        log.info("[KnowledgeBaseTool] collection={}, query=\"{}\", forceRewrite={}",
                collection, truncate(query), forceRewrite);

        try {
            ContextBuilder.ContextResult result;
            ChatModel chatModel = chatModelSupplier.get();
            if (forceRewrite && chatModel != null) {
                result = executeWithRewrite(query, collection, limit, chatModel);
            } else {
                result = knowledgeAccess.search(query, collection, limit);
            }

            ToolResult.ResultStatus resultStatus = escalationPolicy.evaluate(
                    result, forceRewrite, escalationAlreadyUsed);
            ToolResult.setCurrentStatus(resultStatus);
            KnowledgeSearchData data = KnowledgeSearchData.from(result.documents());
            ToolEnvelopeStatus envelopeStatus = data.hits().isEmpty()
                    ? ToolEnvelopeStatus.EMPTY
                    : ToolEnvelopeStatus.SUCCESS;

            if (data.hits().isEmpty()) {
                log.info("[KnowledgeBaseTool] No accepted results: bestObservedScore={}, status={}",
                        String.format("%.4f", result.bestObservedScore()), resultStatus);
            } else {
                log.info("[KnowledgeBaseTool] Found {} hits, topScore={}, rewrite={}, status={}",
                        data.hits().size(), String.format("%.4f", result.topScore()),
                        forceRewrite, resultStatus);
            }

            return objectMapper.writeValueAsString(new ToolEnvelope<>(
                    envelopeStatus,
                    data,
                    null,
                    diagnosticMeta(result, forceRewrite)));
        } catch (ToolExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("[KnowledgeBaseTool] search failed: {}", exception.getMessage(), exception);
            throw new ToolExecutionException(
                    TOOL_NAME, "Knowledge base search failed: " + exception.getMessage());
        }
    }

    private ContextBuilder.ContextResult executeWithRewrite(
            String originalQuery,
            String collection,
            int limit,
            ChatModel chatModel
    ) {
        List<String> queries = generateRewrittenQueries(originalQuery, chatModel);
        if (queries.isEmpty()) {
            log.warn("[KnowledgeBaseTool] Rewrite produced no queries, using original query");
            return knowledgeAccess.search(originalQuery, collection, limit);
        }
        log.info("[KnowledgeBaseTool] Rewrite generated {} queries", queries.size());
        return knowledgeAccess.searchWithQueries(queries, collection, limit);
    }

    private List<String> generateRewrittenQueries(
            String originalQuery,
            ChatModel chatModel
    ) {
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
                log.warn("[KnowledgeBaseTool] Rewrite produced only {} queries, discarding",
                        queries.size());
                return List.of();
            }
            return queries.size() > 6 ? List.copyOf(queries.subList(0, 6)) : List.copyOf(queries);
        } catch (Exception exception) {
            log.warn("[KnowledgeBaseTool] Rewrite LLM call failed: {}", exception.getMessage());
            return List.of();
        }
    }

    private String effectiveCollection(JsonNode arguments) {
        String requestedCollection = textArgument(arguments, "collection");
        return knowledgeAccess.effectiveCollection(requestedCollection);
    }

    private static String textArgument(JsonNode arguments, String name) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new ToolExecutionException(TOOL_NAME, name + " must be a non-blank string");
        }
        return value.asText().trim();
    }

    private static int integerArgument(JsonNode arguments, String name, int defaultValue) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new ToolExecutionException(TOOL_NAME, name + " must be an integer");
        }
        return value.intValue();
    }

    private static ObjectNode stringProperty(String description) {
        return SPEC_MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", description);
    }

    private static ObjectNode integerProperty(int minimum, int maximum) {
        return SPEC_MAPPER.createObjectNode()
                .put("type", "integer")
                .put("minimum", minimum)
                .put("maximum", maximum);
    }

    private static String truncate(String value) {
        return value.length() <= 80 ? value : value.substring(0, 80) + "...";
    }

    private static Map<String, Object> diagnosticMeta(
            ContextBuilder.ContextResult result,
            boolean rewrite
    ) {
        Map<String, Object> meta = new LinkedHashMap<>();
        putIntegerMetadata(meta, "queryCount", result.metadata(), "query_count");
        putStringMetadata(meta, "provider", result.metadata(), "provider");
        putStringMetadata(meta, "collection", result.metadata(), "collection");
        putLongMetadata(meta, "rerankMs", result.metadata(), "rerank_ms");
        meta.put("bestObservedScore", result.bestObservedScore());
        meta.put("observedCandidateCount", result.observedCandidateCount());
        meta.put("rewrite", rewrite);
        return Map.copyOf(meta);
    }

    private static void putStringMetadata(
            Map<String, Object> target,
            String targetKey,
            Map<String, String> source,
            String sourceKey
    ) {
        String value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    private static void putIntegerMetadata(
            Map<String, Object> target,
            String targetKey,
            Map<String, String> source,
            String sourceKey
    ) {
        String value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, Integer.parseInt(value));
        }
    }

    private static void putLongMetadata(
            Map<String, Object> target,
            String targetKey,
            Map<String, String> source,
            String sourceKey
    ) {
        String value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, Long.parseLong(value));
        }
    }

}
