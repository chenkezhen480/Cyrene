package com.harness.agent.runtime;

import com.harness.agent.KnowledgeGraphTool;
import com.harness.agent.context.AgentPromptBuilder;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.knowledge.KnowledgeReadTool;
import com.harness.agent.knowledge.KnowledgeSearchTool;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.agent.lifecycle.AgentLifecycleHooks;
import com.harness.agent.memory.AgentMemoryRuntime;
import com.harness.agent.memory.AgentMemoryRuntime.CompressionOutcome;
import com.harness.agent.memory.AgentMemoryRuntime.MemoryContext;
import com.harness.agent.memory.LongTermKnowledgeRetriever;
import com.harness.agent.memory.PreferenceActivationContextBuilder;
import com.harness.core.knowledge.LongTermKnowledgeBudgetAllocator;
import com.harness.core.model.AgentContext;
import com.harness.core.model.AgentMessage;
import com.harness.core.model.GraphRequestContext;
import com.harness.core.model.MemoryMessage;
import com.harness.core.runtime.RunTrace;
import com.harness.input.ProcessedInput;
import com.harness.input.gap.GapAnalysis;
import com.harness.input.multimodal.MultimodalParser;
import com.harness.tool.builtin.WebSearchTool;
import com.harness.tool.RunToolCatalog;
import com.harness.tool.skill.LoadSkillTool;
import com.harness.tool.web.AuthorizedUrlContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Converts an authenticated request into immutable input for one ReAct execution. */
public final class AgentRunPreparer {

    private static final Logger log = LoggerFactory.getLogger(AgentRunPreparer.class);

    private final AgentRuntime runtime;
    private final AgentPromptBuilder promptBuilder;
    private final AgentLifecycleHooks lifecycleHooks;
    private final AgentMemoryRuntime memoryRuntime;
    private final boolean knowledgeGraphToolEnabled;
    private final LongTermKnowledgeRetriever longTermKnowledgeRetriever;
    private final PreferenceActivationContextBuilder preferenceActivationBuilder;

    public AgentRunPreparer(
            AgentRuntime runtime,
            AgentPromptBuilder promptBuilder,
            AgentLifecycleHooks lifecycleHooks,
            AgentMemoryRuntime memoryRuntime,
            boolean knowledgeGraphToolEnabled,
            LongTermKnowledgeRetriever longTermKnowledgeRetriever,
            PreferenceActivationContextBuilder preferenceActivationBuilder
    ) {
        this.runtime = runtime;
        this.promptBuilder = promptBuilder;
        this.lifecycleHooks = java.util.Objects.requireNonNull(
                lifecycleHooks, "lifecycleHooks");
        this.memoryRuntime = memoryRuntime;
        this.knowledgeGraphToolEnabled = knowledgeGraphToolEnabled;
        this.longTermKnowledgeRetriever = longTermKnowledgeRetriever;
        this.preferenceActivationBuilder = java.util.Objects.requireNonNull(
                preferenceActivationBuilder, "preferenceActivationBuilder");
    }

    public PreparedAgentRun prepare(AgentRunRequest request, RunTrace trace) {
        ProcessedInput input = runtime.input().process(
                request.token(),
                request.text(),
                request.attachments(),
                request.contextUserId());
        AuthorizedUrlContext.setFromUserText(request.text());
        trace.recordInput(
                input.userId(),
                request.text(),
                input.message().attachments().stream()
                        .map(AgentMessage.Attachment::name)
                        .toList());

        AgentContext agentContext = request.agentContext() != null
                ? request.agentContext()
                : AgentContext.empty();
        MemoryContext memoryContext = memoryRuntime.resolve(
                input.userId(), agentContext.optionalTenantId().orElse(null),
                request.requestedSessionId(), request.text(), trace);
        String enhancedText = promptBuilder.enhanceUserText(
                request.text(), input.message().attachments(), agentContext, memoryContext.sessionId());
        activateRequestContexts(agentContext, memoryContext);

        GapAnalysis gapAnalysis = lifecycleHooks.beforeLoop(
                new AgentLifecycleHooks.BeforeLoopContext(enhancedText, agentContext))
                .gapAnalysis();
        GraphRequestContext graphRequestContext = agentContext.graphRequestContext();
        trace.putMetadata(gapMetadata(gapAnalysis, trace.snapshot().metadata()));
        trace.recordPreprocess(resolveIntent(gapAnalysis), List.of(), null);

        String systemPrompt = promptBuilder.buildSystemPrompt(
                request.systemPromptOverride(),
                memoryContext.sessionId(),
                Boolean.TRUE.equals(gapAnalysis.needsKnowledgeBase()),
                knowledgeGraphToolEnabled,
                graphRequestContext,
                Boolean.TRUE.equals(gapAnalysis.needsWebSearch()));
        trace.recordLlmMeta(runtime.providers().chat().modelName(), "v1");

        log.debug("Prepared run: sessionId={}, userId={}, history={}, unavailableTools={}",
                memoryContext.sessionId(),
                memoryContext.userId(),
                memoryContext.shorttermMessages().size(),
                requestUnavailableTools(agentContext));
        return new PreparedAgentRun(
                memoryContext.sessionId(),
                memoryContext.userId(),
                memoryContext.tenantId(),
                enhancedText,
                systemPrompt,
                memoryContext.shorttermMessages(),
                gapAnalysis,
                requestUnavailableTools(agentContext),
                null,
                null,
                agentContext,
                request.updateActivityAfterUserMessage());
    }

    /** Completes preparation only after the immutable request Tool Catalog exists. */
    public PreparedAgentRun complete(
            PreparedAgentRun prepared,
            RunToolCatalog toolCatalog,
            RunTrace trace
    ) {
        java.util.Objects.requireNonNull(prepared, "prepared");
        java.util.Objects.requireNonNull(toolCatalog, "toolCatalog");
        KnowledgeToolRuntimeContext.activate(
                prepared.tenantId(),
                prepared.userId(),
                prepared.agentContext().knowledgeRequestContext(),
                prepared.agentContext().graphRequestContext(),
                toolCatalog,
                trace);

        String dynamicKnowledgeContext = null;
        if (longTermKnowledgeRetriever != null) {
            var activationContext = preferenceActivationBuilder.build(
                    prepared.enhancedText(), prepared.gapAnalysis(), toolCatalog);
            LongTermKnowledgeBudgetAllocator.Allocation allocation =
                    longTermKnowledgeRetriever.retrieve(
                            activationContext,
                            KnowledgeToolRuntimeContext.requireCurrent(
                                    KnowledgeSearchTool.TOOL_NAME),
                            runtime.providers().chat().contextWindow(),
                            trace);
            if (!allocation.selectedBlocks().isEmpty()) {
                dynamicKnowledgeContext = "<dynamic-knowledge-context role=\"evidence\">\n"
                        + allocation.renderedContext()
                        + "\n</dynamic-knowledge-context>";
            }
        }

        String budgetedCurrentInput = dynamicKnowledgeContext == null
                ? prepared.enhancedText()
                : dynamicKnowledgeContext + "\n\n" + prepared.enhancedText();
        CompressionOutcome compressionOutcome = memoryRuntime.compress(
                prepared.sessionId(),
                prepared.userId(),
                prepared.shorttermMessages(),
                budgetedCurrentInput,
                prepared.systemPrompt());
        memoryRuntime.recordCompressionMetadata(trace, compressionOutcome);
        memoryRuntime.persistUserMessage(
                prepared.sessionId(),
                prepared.userId(),
                trace.traceId(),
                prepared.enhancedText(),
                prepared.updateActivityAfterUserMessage());
        return new PreparedAgentRun(
                prepared.sessionId(),
                prepared.userId(),
                prepared.tenantId(),
                prepared.enhancedText(),
                prepared.systemPrompt(),
                compressionOutcome.finalMessages(),
                prepared.gapAnalysis(),
                prepared.unavailableTools(),
                compressionOutcome,
                dynamicKnowledgeContext,
                prepared.agentContext(),
                prepared.updateActivityAfterUserMessage());
    }

    private void activateRequestContexts(
            AgentContext agentContext,
            MemoryContext memoryContext
    ) {
        if (knowledgeGraphToolEnabled) {
            KnowledgeGraphTool.setCurrentContext(
                    agentContext.tenantId(), agentContext.graphRequestContext());
        } else {
            KnowledgeGraphTool.clearCurrentContext();
        }
        KnowledgeAccessService.setCurrentContext(
                agentContext.tenantId(), agentContext.knowledgeRequestContext());
        LoadSkillTool.setCurrentSession(memoryContext.sessionId());
    }

    private static Map<String, String> gapMetadata(
            GapAnalysis gapAnalysis,
            Map<String, String> existing
    ) {
        Map<String, String> metadata = new HashMap<>(existing);
        metadata.put("gap_needsKnowledgeBase", String.valueOf(gapAnalysis.needsKnowledgeBase()));
        metadata.put("gap_needsThinking", String.valueOf(gapAnalysis.needsThinking()));
        metadata.put("gap_thinkingLevel", gapAnalysis.thinkingLevel() == null
                ? "default" : gapAnalysis.thinkingLevel().configValue());
        metadata.put("gap_needsWebSearch", String.valueOf(gapAnalysis.needsWebSearch()));
        metadata.put("gap_source", String.valueOf(gapAnalysis.source()));
        return metadata;
    }

    public static String resolveIntent(GapAnalysis gapAnalysis) {
        if (gapAnalysis == null) {
            return "chat";
        }
        boolean kb = Boolean.TRUE.equals(gapAnalysis.needsKnowledgeBase());
        boolean web = Boolean.TRUE.equals(gapAnalysis.needsWebSearch());
        boolean think = Boolean.TRUE.equals(gapAnalysis.needsThinking());
        if (kb && web) {
            return "knowledge_and_web_search";
        }
        if (kb) {
            return "knowledge_search";
        }
        if (web) {
            return "web_search";
        }
        if (think) {
            return "reasoning";
        }
        return "chat";
    }

    private static Set<String> requestUnavailableTools(AgentContext context) {
        Set<String> unavailable = new HashSet<>();
        if (Boolean.FALSE.equals(context.needsKnowledgeBase())) {
            unavailable.add(KnowledgeSearchTool.TOOL_NAME);
            unavailable.add(KnowledgeReadTool.TOOL_NAME);
        }
        if (Boolean.FALSE.equals(context.needsWebSearch())) {
            unavailable.add(WebSearchTool.TOOL_NAME);
        }
        return Set.copyOf(unavailable);
    }

    public record AgentRunRequest(
            String token,
            String text,
            List<MultimodalParser.RawAttachment> attachments,
            String requestedSessionId,
            String systemPromptOverride,
            String contextUserId,
            AgentContext agentContext,
            boolean updateActivityAfterUserMessage
    ) {
    }

    public record PreparedAgentRun(
            String sessionId,
            String userId,
            String tenantId,
            String enhancedText,
            String systemPrompt,
            List<MemoryMessage> shorttermMessages,
            GapAnalysis gapAnalysis,
            Set<String> unavailableTools,
            CompressionOutcome compressionOutcome,
            String dynamicKnowledgeContext,
            AgentContext agentContext,
            boolean updateActivityAfterUserMessage
    ) {
    }
}
