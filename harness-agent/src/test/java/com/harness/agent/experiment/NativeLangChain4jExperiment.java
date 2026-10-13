package com.harness.agent.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.model.*;
import com.harness.provider.ChatModelProvider;
import com.harness.provider.LangChainJsonSchemaMapper;
import com.harness.tool.HttpApiTool;
import com.harness.tool.RunToolCatalog;
import com.harness.tool.ToolExecutor;
import com.harness.trace.TraceCollector;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Native library loop; business tools, authorization and storage stay shared with Cyrene. */
final class NativeLangChain4jExperiment {
    interface Assistant { Result<String> respond(String prompt); }

    static AgentResult run(ChatModelProvider provider, RunToolCatalog catalog, ToolExecutor executor,
            AgentContext context, TraceCollector trace, String systemPrompt, String prompt,
            ThinkingLevel thinking, int maxRounds) {
        if (KnowledgeToolRuntimeContext.captureCurrent() != null) throw new IllegalStateException("Native comparison cannot nest inside an active Cyrene run");
        var mapper = new ObjectMapper();
        var specs = catalog.getAll().stream().map(spec -> ToolSpecification.builder().name(spec.name())
                .description(spec.description()).parameters(LangChainJsonSchemaMapper.toObjectSchema(spec.parameters())).build()).toList();
        Map<ToolSpecification, dev.langchain4j.service.tool.ToolExecutor> callbacks = new LinkedHashMap<>();
        var stepNumber = new AtomicInteger();
        for (var spec : specs) {
            var authorized = catalog.get(spec.name());
            callbacks.put(spec, (request, memoryId) -> {
                var credentials = HttpApiTool.getCurrentCredentialsSnapshot();
                var previousAccess = KnowledgeAccessService.captureCurrentContext();
                try {
                    HttpApiTool.setCurrentCredentials(context.credentials());
                    KnowledgeAccessService.setCurrentContext(context.tenantId(), context.knowledgeRequestContext());
                    KnowledgeToolRuntimeContext.activate(context.tenantId(), context.userId(), context.knowledgeRequestContext(), null, catalog, trace);
                    var call = new ToolCall(request.id(), request.name(), mapper.readTree(request.arguments()));
                    var result = executor.executeAuthorized(call, authorized, null, trace.snapshot().sessionId());
                    trace.addStep(new ReActStep(stepNumber.incrementAndGet(), null, request.name(),
                            List.of(call), List.of(result), result.success() ? result.output() : result.error(), null));
                    return result.success() ? result.output() : mapper.writeValueAsString(Map.of("error", result.error()));
                } catch (java.io.IOException exception) {
                    throw new IllegalArgumentException("Invalid native tool arguments", exception);
                } finally {
                    if (credentials.isEmpty()) HttpApiTool.clearCurrentCredentials();
                    else HttpApiTool.setCurrentCredentials(credentials);
                    KnowledgeAccessService.restoreCurrentContext(previousAccess);
                    KnowledgeToolRuntimeContext.clear();
                }
            });
        }
        ChatModel delegate = provider.chatModel();
        ChatModel measured = new ChatModel() {
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public java.util.Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
            @Override public ChatResponse doChat(ChatRequest request) {
                long started = System.currentTimeMillis();
                var policy = provider.planningRequestParameters(thinking, specs);
                var response = delegate.chat(policy == null ? request : request.toBuilder().parameters(policy).build());
                trace.recordModelUsage(provider.modelUsage(response, System.currentTimeMillis() - started));
                if (response.tokenUsage() != null && response.tokenUsage().totalTokenCount() != null) trace.addTokens(response.tokenUsage().totalTokenCount());
                return response;
            }
        };
        trace.recordInput(context.userId(), prompt, List.of());
        trace.recordLlmMeta(provider.modelName(), "v1");
        trace.putMetadata("experimentRuntime", "LangChain4j native tool loop with shared Cyrene tools/security");
        trace.putMetadata("thinkingLevel", thinking.configValue());
        try {
            var result = AiServices.builder(Assistant.class).chatModel(measured).systemMessage(systemPrompt)
                    .tools(callbacks).maxToolCallingRoundTrips(maxRounds).build().respond(prompt);
            boolean pendingConfirmation = trace.snapshot().steps().stream().flatMap(step -> step.toolResults().stream())
                    .anyMatch(tool -> tool.executionStatus() == ExecutionStatus.CONFIRMATION_REQUIRED);
            trace.recordOutput(result.content(), pendingConfirmation ? RiskLevel.MEDIUM : RiskLevel.LOW, !pendingConfirmation);
            var completed = trace.finish();
            if (pendingConfirmation) return AgentResult.needConfirmation(result.content(), RiskLevel.MEDIUM, completed, completed.steps());
            return AgentResult.success(result.content(), completed, completed.steps());
        } catch (RuntimeException exception) {
            trace.recordOutput("Error: " + exception.getMessage(), RiskLevel.HIGH, false);
            trace.finish();
            throw exception;
        }
    }
}
