package com.harness.agent.memory;

import com.harness.core.model.MessageBlock;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Encodes persisted Tool calls/results and reconstructs valid model history messages. */
final class ToolMemoryCodec {

    static final String TOOL_CALL_ROLE = "assistant_tool_call";
    static final String TOOL_RESULT_ROLE = "tool";

    private static final String TOOL_CALLS_KEY = "toolCalls";
    private static final String TOOL_CALL_ID_KEY = "toolCallId";
    private static final String TOOL_NAME_KEY = "toolName";

    private ToolMemoryCodec() {
    }

    static List<MessageBlock> encodeCalls(List<ToolCall> calls) {
        List<Map<String, Object>> encodedCalls = calls.stream()
                .map(call -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", call.id());
                    value.put("name", call.toolName());
                    value.put("arguments", call.arguments() == null
                            ? "null"
                            : call.arguments().toString());
                    return Map.copyOf(value);
                })
                .toList();
        String description = calls.stream()
                .map(call -> call.toolName() + "(" + call.arguments() + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return List.of(new MessageBlock(
                MessageBlock.BlockType.TEXT,
                "[Tool call] " + description,
                null,
                Map.of(TOOL_CALLS_KEY, encodedCalls)));
    }

    static List<MessageBlock> encodeResult(ToolResult result) {
        ToolOutput output = result.success()
                ? result.content()
                : ToolOutput.text("ERROR: " + result.error());
        List<MessageBlock> blocks = new ArrayList<>(
                output == null ? List.of() : output.toMessageBlocks());
        if (blocks.isEmpty()) {
            blocks.add(new MessageBlock(MessageBlock.BlockType.TEXT, "", null));
        }
        MessageBlock first = blocks.get(0);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (first.metadata() != null) {
            metadata.putAll(first.metadata());
        }
        metadata.put(TOOL_CALL_ID_KEY, result.toolCallId());
        metadata.put(TOOL_NAME_KEY, result.toolName());
        blocks.set(0, new MessageBlock(
                first.type(), first.text(), first.artifactId(), Map.copyOf(metadata)));
        return List.copyOf(blocks);
    }

    static List<ChatMessage> toChatMessages(List<MemoryMessage> memoryMessages) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (MemoryMessage message : memoryMessages) {
            String modelText = message.modelText();
            if (message.isSummary()) {
                chatMessages.add(AiMessage.from(
                        "[Previous conversation summary]\n" + modelText));
                continue;
            }
            switch (message.role()) {
                case "user" -> chatMessages.add(UserMessage.from(modelText));
                case "assistant" -> chatMessages.add(AiMessage.from(modelText));
                case TOOL_CALL_ROLE -> chatMessages.add(decodeCalls(message));
                case TOOL_RESULT_ROLE -> chatMessages.add(decodeResult(message));
                default -> {
                    // Non-conversation system records are not injected into model history.
                }
            }
        }
        return chatMessages;
    }

    private static AiMessage decodeCalls(MemoryMessage message) {
        Map<String, Object> metadata = firstMetadata(message.content());
        Object rawCalls = metadata.get(TOOL_CALLS_KEY);
        List<ToolExecutionRequest> requests = new ArrayList<>();
        if (rawCalls instanceof List<?> calls) {
            for (Object rawCall : calls) {
                if (!(rawCall instanceof Map<?, ?> call)) {
                    throw new IllegalArgumentException(
                            "Persisted Tool call metadata must be an object");
                }
                requests.add(ToolExecutionRequest.builder()
                        .id(stringValue(call.get("id")))
                        .name(stringValue(call.get("name")))
                        .arguments(stringValue(call.get("arguments")))
                        .build());
            }
        }
        return requests.isEmpty()
                ? AiMessage.from(message.modelText())
                : AiMessage.from(requests);
    }

    private static ToolExecutionResultMessage decodeResult(MemoryMessage message) {
        Map<String, Object> metadata = firstMetadata(message.content());
        String toolCallId = stringValue(metadata.get(TOOL_CALL_ID_KEY));
        String toolName = stringValue(metadata.get(TOOL_NAME_KEY));
        if (toolCallId == null || toolName == null) {
            throw new IllegalArgumentException(
                    "Persisted Tool result is missing toolCallId or toolName");
        }
        return ToolExecutionResultMessage.from(toolCallId, toolName, message.modelText());
    }

    private static Map<String, Object> firstMetadata(List<MessageBlock> blocks) {
        if (blocks == null || blocks.isEmpty() || blocks.get(0).metadata() == null) {
            return Map.of();
        }
        return blocks.get(0).metadata();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
