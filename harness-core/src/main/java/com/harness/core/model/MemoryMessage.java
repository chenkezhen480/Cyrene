package com.harness.core.model;

import java.time.Instant;
import java.util.List;

/**
 * A persisted conversation message within a session.
 * Named MemoryMessage to avoid collision with AgentMessage.
 *
 * @param content Structured content blocks. Always non-null, at least one TEXT block for normal messages.
 */
public record MemoryMessage(
        long id,
        String sessionId,
        String role,
        List<MessageBlock> content,
        boolean isSummary,
        Instant createdAt
) {
    /** Extract plain text from content blocks (for LLM context, compression, etc.). */
    public static String text(List<MessageBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (MessageBlock b : blocks) {
            if (b.type() == MessageBlock.BlockType.TEXT && b.text() != null) {
                sb.append(b.text());
            }
        }
        return sb.toString();
    }

    /** Convenience: plain text content of this message. */
    public String text() {
        return text(content);
    }

    /**
     * Deterministic representation supplied to the model and compression pipeline.
     * Unlike {@link #text()}, this preserves artifact references and structured JSON blocks.
     */
    public String modelText() {
        return ToolOutput.fromMessageBlocks(content).modelContent();
    }
}
