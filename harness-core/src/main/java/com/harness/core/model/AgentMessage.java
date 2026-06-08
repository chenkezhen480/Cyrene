package com.harness.core.model;

import java.util.Collections;
import java.util.List;

/**
 * Represents a message in the agent conversation.
 */
public record AgentMessage(
        Role role,
        String text,
        List<Attachment> attachments,
        String userId,
        String sessionId
) {
    public enum Role {
        USER, ASSISTANT, SYSTEM, TOOL
    }

    public record Attachment(
            AttachmentType type,
            String name,
            byte[] data,
            String mimeType
    ) {
        public enum AttachmentType {
            IMAGE, FILE, VIDEO, AUDIO
        }
    }

    public static AgentMessage user(String text) {
        return new AgentMessage(Role.USER, text, Collections.emptyList(), null, null);
    }

    public static AgentMessage user(String text, List<Attachment> attachments) {
        return new AgentMessage(Role.USER, text, attachments, null, null);
    }

    public static AgentMessage assistant(String text) {
        return new AgentMessage(Role.ASSISTANT, text, Collections.emptyList(), null, null);
    }

    public static AgentMessage system(String text) {
        return new AgentMessage(Role.SYSTEM, text, Collections.emptyList(), null, null);
    }

    public static AgentMessage toolResult(String toolName, String result) {
        return new AgentMessage(Role.TOOL, "[" + toolName + "] " + result, Collections.emptyList(), null, null);
    }

    public AgentMessage withSession(String userId, String sessionId) {
        return new AgentMessage(this.role, this.text, this.attachments, userId, sessionId);
    }
}
