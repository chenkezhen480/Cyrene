package com.harness.agent.runtime;

import com.harness.agent.KnowledgeBaseTool;
import com.harness.agent.KnowledgeContextReadTool;
import com.harness.agent.KnowledgeGraphTool;
import com.harness.agent.context.AgentPromptBuilder;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.memory.AgentMemoryRuntime;
import com.harness.agent.memory.AgentMemoryRuntime.CompressionOutcome;
import com.harness.agent.memory.AgentMemoryRuntime.MemoryContext;
import com.harness.core.model.AgentContext;
import com.harness.core.model.AgentMessage;
import com.harness.core.model.GraphRequestContext;
import com.harness.core.model.MemoryMessage;
import com.harness.core.runtime.RunTrace;
import com.harness.input.ProcessedInput;
import com.harness.input.gap.GapAnalysis;
import com.harness.input.gap.GapAnalyzer;
import com.harness.input.multimodal.MultimodalParser;
import com.harness.tool.builtin.UpdateMemoryTool;
import com.harness.tool.builtin.WebSearchTool;
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
    private final GapAnalyzer gapAnalyzer;
    private final AgentMemoryRuntime memoryRuntime;
    private final boolean knowledgeGraphToolEnabled;

    public AgentRunPreparer(
            AgentRuntime runtime,
            AgentPromptBuilder promptBuilder,
            GapAnalyzer gapAnalyzer,
            AgentMemoryRuntime memoryRuntime,
            boolean knowledgeGraphToolEnabled
    ) {
        this.runtime = runtime;
        this.promptBuilder = promptBuilder;
        this.gapAnalyzer = gapAnalyzer;
        this.memoryRuntime = memoryRuntime;
        this.knowledgeGraphToolEnabled = knowledgeGraphToolEnabled;
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

        String enhancedText = promptBuilder.enhanceUserText(
                request.text(), input.parsedContents(), request.agentContext());
        AgentContext agentContext = request.agentContext() != null
                ? request.agentContext()
                : AgentContext.empty();
        GapAnalysis gapAnalysis = gapAnalyzer.analyze(enhancedText, agentContext);
        GraphRequestContext graphRequestContext = agentContext.graphRequestContext();
        trace.putMetadata(gapMetadata(gapAnalysis, trace.snapshot().metadata()));

        MemoryContext memoryContext = memoryRuntime.resolve(
                input.userId(), request.requestedSessionId(), request.text(), trace);
        activateRequestContexts(agentContext, memoryContext);

        String systemPrompt = promptBuilder.buildSystemPrompt(
                memoryContext.longtermPreferences(),
                request.systemPromptOverride(),
                memoryContext.sessionId(),
                Boolean.TRUE.equals(gapAnalysis.needsKnowledgeBase()),
                knowledgeGraphToolEnabled,
                graphRequestContext,
                Boolean.TRUE.equals(gapAnalysis.needsWebSearch()));
        trace.recordLlmMeta(runtime.providers().chat().modelName(), "v1");

        CompressionOutcome compressionOutcome = memoryRuntime.compress(
                memoryContext.sessionId(),
                memoryContext.userId(),
                memoryContext.shorttermMessages(),
                enhancedText,
                systemPrompt);
        memoryRuntime.recordCompressionMetadata(trace, compressionOutcome);
        memoryRuntime.persistUserMessage(
                memoryContext.sessionId(),
                memoryContext.userId(),
                enhancedText,
                request.updateActivityAfterUserMessage());

        log.debug("Prepared run: sessionId={}, userId={}, history={}, unavailableTools={}",
                memoryContext.sessionId(),
                memoryContext.userId(),
                compressionOutcome.finalMessages().size(),
                requestUnavailableTools(agentContext));
        return new PreparedAgentRun(
                memoryContext.sessionId(),
                memoryContext.userId(),
                enhancedText,
                systemPrompt,
                compressionOutcome.finalMessages(),
                gapAnalysis,
                requestUnavailableTools(agentContext),
                compressionOutcome);
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
        UpdateMemoryTool.setCurrentUserId(memoryContext.userId());
        UpdateMemoryTool.setCurrentSessionId(memoryContext.sessionId());
    }

    private static Map<String, String> gapMetadata(
            GapAnalysis gapAnalysis,
            Map<String, String> existing
    ) {
        Map<String, String> metadata = new HashMap<>(existing);
        metadata.put("gap_needsKnowledgeBase", String.valueOf(gapAnalysis.needsKnowledgeBase()));
        metadata.put("gap_needsThinking", String.valueOf(gapAnalysis.needsThinking()));
        metadata.put("gap_needsWebSearch", String.valueOf(gapAnalysis.needsWebSearch()));
        metadata.put("gap_source", String.valueOf(gapAnalysis.source()));
        return metadata;
    }

    private static Set<String> requestUnavailableTools(AgentContext context) {
        Set<String> unavailable = new HashSet<>();
        if (Boolean.FALSE.equals(context.needsKnowledgeBase())) {
            unavailable.add(KnowledgeBaseTool.TOOL_NAME);
            unavailable.add(KnowledgeContextReadTool.TOOL_NAME);
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
            String enhancedText,
            String systemPrompt,
            List<MemoryMessage> shorttermMessages,
            GapAnalysis gapAnalysis,
            Set<String> unavailableTools,
            CompressionOutcome compressionOutcome
    ) {
    }
}
