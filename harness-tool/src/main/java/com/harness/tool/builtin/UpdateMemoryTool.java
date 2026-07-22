package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in tool for updating the user's single long-term memory record.
 * The LLM maintains a complete user profile in one record.
 * Each call replaces the entire memory content (not append).
 *
 * Uses ThreadLocal for userId/sessionId context, set by AgentOrchestrator before each run.
 */
public class UpdateMemoryTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(UpdateMemoryTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();

    /**
     * Callback interface for saving the memory.
     * Arguments: userId, content, sessionId
     */
    @FunctionalInterface
    public interface MemorySaver {
        void save(String userId, String content, String sessionId);
    }

    private final MemorySaver saver;

    public UpdateMemoryTool(MemorySaver saver) {
        this.saver = saver;
    }

    public static void setCurrentUserId(String userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static void setCurrentSessionId(String sessionId) {
        CURRENT_SESSION_ID.set(sessionId);
    }

    public static void clearContext() {
        CURRENT_USER_ID.remove();
        CURRENT_SESSION_ID.remove();
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "update_memory",
                "Append a new entry to the current user's long-term memory. " +
                "Each call APPENDS the new content to the existing memory, separated by '#'. " +
                "Only include NEW information not already in the memory. " +
                "Call this when: the user shares personal info, states preferences, corrects something about themselves, or explicitly asks to be remembered. " +
                "Be concise — each entry should be a brief, self-contained fact or preference.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("memory",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "The NEW information to append to memory. Do not include previously saved facts.")))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                                mapper.createArrayNode().add("memory"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String memory = arguments.has("memory") ? arguments.get("memory").asText().trim() : null;

        if (memory == null || memory.isEmpty()) {
            return "ERROR: 'memory' is required";
        }

        String userId = CURRENT_USER_ID.get();
        if (userId == null || userId.isEmpty()) {
            return "ERROR: No user context available. Cannot save memory without authentication.";
        }

        String sessionId = CURRENT_SESSION_ID.get();

        try {
            saver.save(userId, memory, sessionId);
            log.info("Updated long-term memory for user {} ({} chars)", userId, memory.length());
            ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            return "Memory updated successfully (" + memory.length() + " chars)";
        } catch (Exception e) {
            log.error("Failed to update memory: {}", e.getMessage(), e);
            return "ERROR: Failed to update memory: " + e.getMessage();
        }
    }
}
