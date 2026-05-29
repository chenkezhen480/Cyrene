package com.harness.agent;

import com.harness.core.model.AgentResult;
import com.harness.input.multimodal.MultimodalParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Extended orchestrator that supports spawning sub-agents for parallel task execution.
 * Wraps the existing AgentOrchestrator and exposes sub-agent capabilities.
 * The main agent can call the spawn_subagent tool (registered in AgentOrchestrator)
 * to delegate sub-tasks to independent sub-agent instances.
 *
 * This class provides convenience methods for programmatic sub-agent access
 * in addition to the tool-based spawning available to the LLM.
 */
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    private final AgentOrchestrator mainOrchestrator;

    public MultiAgentOrchestrator() {
        this.mainOrchestrator = new AgentOrchestrator();
        log.info("[MultiAgent] MultiAgentOrchestrator initialized");
    }

    /**
     * Run the main agent. The spawn_subagent tool is available to the LLM.
     */
    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments) {
        return mainOrchestrator.run(token, text, attachments);
    }

    /**
     * Run the main agent with session ID.
     */
    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments, String sessionId) {
        return mainOrchestrator.run(token, text, attachments, sessionId);
    }

    /**
     * Spawn a sub-agent task programmatically (outside of the main agent's tool calling).
     * The sub-agent runs with its own ReActEngine but shares the main agent's tool registry.
     */
    public CompletableFuture<SubAgentResult> spawnSubAgent(String taskDescription, String context) {
        String taskId = "sub-" + UUID.randomUUID().toString().substring(0, 8);
        SubAgentTask task = SubAgentTask.create(taskId, taskDescription, context);
        return mainOrchestrator.subAgentOrchestrator().submitTask(task);
    }

    /**
     * Spawn a sub-agent task with dependencies.
     */
    public CompletableFuture<SubAgentResult> spawnSubAgent(String taskDescription, String context, List<String> dependencies) {
        String taskId = "sub-" + UUID.randomUUID().toString().substring(0, 8);
        SubAgentTask task = SubAgentTask.create(taskId, taskDescription, context, dependencies);
        return mainOrchestrator.subAgentOrchestrator().submitTask(task);
    }

    /**
     * Expose the main orchestrator for direct access.
     */
    public AgentOrchestrator mainOrchestrator() {
        return mainOrchestrator;
    }

    /**
     * Expose the sub-agent orchestrator for direct access.
     */
    public SubAgentOrchestrator subAgentOrchestrator() {
        return mainOrchestrator.subAgentOrchestrator();
    }

    public void shutdown() {
        mainOrchestrator.shutdown();
        log.info("[MultiAgent] Shut down");
    }
}
