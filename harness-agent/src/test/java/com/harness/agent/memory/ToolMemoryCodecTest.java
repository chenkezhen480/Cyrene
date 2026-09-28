package com.harness.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.Artifact;
import com.harness.core.model.MessageBlock;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolMemoryCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void reconstructsPersistedToolCallAndTypedResultForNextModelTurn() throws Exception {
        ToolCall call = new ToolCall(
                "call-1", "customer_lookup", MAPPER.readTree("{\"id\":\"c-1\"}"));
        Artifact artifact = new Artifact(
                "artifact-1", "session-1", "report.json",
                Artifact.ArtifactType.DOCUMENT, "application/json", 32,
                "private/report.json", Instant.now());
        ToolResult result = ToolResult.ok(
                call.id(),
                call.toolName(),
                new ToolOutput(
                        "lookup complete",
                        List.of(artifact),
                        MAPPER.readTree("{\"eligible\":true}")),
                5,
                com.harness.core.model.ResultStatus.AVAILABLE);
        List<MemoryMessage> memory = List.of(
                message(ToolMemoryCodec.TOOL_CALL_ROLE, ToolMemoryCodec.encodeCalls(List.of(call))),
                message(ToolMemoryCodec.TOOL_RESULT_ROLE, ToolMemoryCodec.encodeResult(result)));

        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(memory);

        assertThat(history).hasSize(2);
        AiMessage callMessage = (AiMessage) history.get(0);
        assertThat(callMessage.toolExecutionRequests()).hasSize(1);
        assertThat(callMessage.toolExecutionRequests().get(0).id()).isEqualTo("call-1");
        assertThat(callMessage.toolExecutionRequests().get(0).arguments())
                .isEqualTo("{\"id\":\"c-1\"}");
        ToolExecutionResultMessage resultMessage =
                (ToolExecutionResultMessage) history.get(1);
        assertThat(resultMessage.id()).isEqualTo("call-1");
        assertThat(resultMessage.toolName()).isEqualTo("customer_lookup");
        assertThat(MAPPER.readTree(resultMessage.text()).get("json"))
                .isEqualTo(MAPPER.readTree("{\"eligible\":true}"));
    }

    @Test
    void keepsAssistantStructuredBlockInOrdinaryConversationHistory() throws Exception {
        MemoryMessage assistant = message(
                "assistant",
                ToolOutput.json(MAPPER.readTree("{\"rows\":[1]}")).toMessageBlocks());

        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(List.of(assistant));

        assertThat(history).hasSize(1);
        assertThat(((AiMessage) history.get(0)).text())
                .isEqualTo("{\"rows\":[1]}");
    }

    @Test
    void closesInterruptedBatchBeforeNextUserMessageWithoutInventingSuccess() throws Exception {
        ToolCall first = call("call-1");
        ToolCall second = call("call-2");
        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(List.of(
                calls(first, second),
                result(first, "first completed"),
                textMessage("user", "continue")));

        assertThat(history).hasSize(4);
        assertThat(((ToolExecutionResultMessage) history.get(1)).text()).isEqualTo("first completed");
        assertUnknownResult(history.get(2), "call-2");
        assertThat(history.get(3)).isEqualTo(UserMessage.from("continue"));
    }

    @Test
    void closesBatchWithNoResultsAtEndOfHistory() throws Exception {
        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(List.of(
                calls(call("call-1"), call("call-2"))));

        assertThat(history).hasSize(3);
        assertUnknownResult(history.get(1), "call-1");
        assertUnknownResult(history.get(2), "call-2");
    }

    @Test
    void closesMissingResultsBeforeAssistantSummaryAndNextCallBatch() throws Exception {
        ToolCall second = call("call-2");
        MemoryMessage summary = new MemoryMessage(2, "session-1", "trace-1", "assistant",
                List.of(new MessageBlock(MessageBlock.BlockType.TEXT, "summary", null)), true, Instant.now());
        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(List.of(
                calls(call("call-1")), summary, calls(second), result(second, "done")));

        assertThat(history).hasSize(5);
        assertUnknownResult(history.get(1), "call-1");
        assertThat(((AiMessage) history.get(2)).text()).contains("summary");
        assertThat(((AiMessage) history.get(3)).toolExecutionRequests().getFirst().id()).isEqualTo("call-2");
        assertThat(((ToolExecutionResultMessage) history.get(4)).text()).isEqualTo("done");
    }

    @Test
    void closesMissingResultsBeforeAdjacentCallBatch() throws Exception {
        ToolCall second = call("call-2");
        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(List.of(
                calls(call("call-1")), calls(second), result(second, "done")));

        assertThat(history).hasSize(4);
        assertUnknownResult(history.get(1), "call-1");
        assertThat(((AiMessage) history.get(2)).toolExecutionRequests().getFirst().id()).isEqualTo("call-2");
    }

    @Test
    void retainsOrphanResultAsOrdinaryHistoricalContext() throws Exception {
        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(List.of(
                result(call("orphan"), "historical evidence"), textMessage("user", "continue")));

        assertThat(history).hasSize(2);
        assertThat(history.getFirst()).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) history.getFirst()).text()).contains("orphan", "historical evidence");
        assertThat(((AiMessage) history.getFirst()).hasToolExecutionRequests()).isFalse();
    }

    @Test
    void defersDuplicateAndUnrelatedResultsUntilPendingBatchIsClosed() throws Exception {
        ToolCall first = call("call-1");
        ToolCall second = call("call-2");
        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(List.of(
                calls(first, second), result(first, "first completed"),
                result(first, "duplicate evidence"), result(call("orphan"), "unrelated evidence"),
                result(second, "second completed"), textMessage("user", "continue")));

        assertThat(history).hasSize(6);
        assertThat(((ToolExecutionResultMessage) history.get(1)).text()).isEqualTo("first completed");
        assertThat(((ToolExecutionResultMessage) history.get(2)).text()).isEqualTo("second completed");
        assertThat(((AiMessage) history.get(3)).text()).contains("duplicate evidence");
        assertThat(((AiMessage) history.get(4)).text()).contains("unrelated evidence");
        assertThat(history.get(5)).isEqualTo(UserMessage.from("continue"));
    }

    @Test
    void preservesCompleteOutOfOrderResultsAndFailureContent() throws Exception {
        ToolCall first = call("call-1");
        ToolCall second = call("call-2");
        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(List.of(
                textMessage("user", "run"), calls(first, second), result(second, "second completed"),
                message(ToolMemoryCodec.TOOL_RESULT_ROLE,
                        ToolMemoryCodec.encodeResult(ToolResult.fail(first.id(), first.toolName(), "denied", 1))),
                textMessage("assistant", "finished")));

        assertThat(history).hasSize(5);
        assertThat(history.getFirst()).isEqualTo(UserMessage.from("run"));
        assertThat(((ToolExecutionResultMessage) history.get(2)).id()).isEqualTo("call-2");
        assertThat(((ToolExecutionResultMessage) history.get(2)).text()).isEqualTo("second completed");
        assertThat(((ToolExecutionResultMessage) history.get(3)).id()).isEqualTo("call-1");
        assertThat(((ToolExecutionResultMessage) history.get(3)).text()).isEqualTo("ERROR: denied");
        assertThat(history.get(4)).isEqualTo(AiMessage.from("finished"));
    }

    @Test
    void doesNotPairResultFromAnotherTraceWithSameCallId() throws Exception {
        ToolCall call = call("call-1");
        MemoryMessage otherTraceResult = new MemoryMessage(2, "session-1", "trace-2", "tool",
                ToolMemoryCodec.encodeResult(ToolResult.ok(call.id(), call.toolName(), "other trace result", 1)),
                false, Instant.now());

        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(List.of(calls(call), otherTraceResult));

        assertThat(history).hasSize(3);
        assertUnknownResult(history.get(1), "call-1");
        assertThat(((AiMessage) history.get(2)).text()).contains("other trace result");
    }

    @Test
    void doesNotPairResultFromAnotherSessionWithSameCallId() throws Exception {
        ToolCall call = call("call-1");
        MemoryMessage otherSessionResult = new MemoryMessage(2, "session-2", "trace-1", "tool",
                ToolMemoryCodec.encodeResult(ToolResult.ok(call.id(), call.toolName(), "other session result", 1)),
                false, Instant.now());

        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(List.of(calls(call), otherSessionResult));

        assertThat(history).hasSize(3);
        assertUnknownResult(history.get(1), "call-1");
        assertThat(((AiMessage) history.get(2)).text()).contains("other session result");
    }

    @Test
    void exposesSubAgentCompletionAsLabeledContextWithoutToolAssociation() throws Exception {
        MemoryMessage completion = message("subagent_event", List.of(
                new MessageBlock(MessageBlock.BlockType.TEXT, "child result", null,
                        java.util.Map.of("taskId", "task-1", "status", "INCOMPLETE")),
                new MessageBlock(MessageBlock.BlockType.ARTIFACT, null, "artifact-1",
                        java.util.Map.of("name", "report.json", "mimeType", "application/json"))));

        List<ChatMessage> history = ToolMemoryCodec.toChatMessages(List.of(calls(call("call-1")), completion));

        assertThat(history).hasSize(3);
        assertUnknownResult(history.get(1), "call-1");
        AiMessage event = (AiMessage) history.get(2);
        assertThat(event.hasToolExecutionRequests()).isFalse();
        assertThat(event.text()).contains("Sub-Agent", "task-1", "INCOMPLETE", "child result", "artifact-1");
    }

    private static void assertUnknownResult(ChatMessage message, String callId) {
        assertThat(message).isInstanceOf(ToolExecutionResultMessage.class);
        ToolExecutionResultMessage result = (ToolExecutionResultMessage) message;
        assertThat(result.id()).isEqualTo(callId);
        assertThat(result.toolName()).isEqualTo("lookup");
        assertThat(result.text()).contains("ERROR", "UNKNOWN");
        assertThat(result.text()).containsIgnoringCase("missing");
    }

    private static ToolCall call(String id) throws Exception {
        return new ToolCall(id, "lookup", MAPPER.readTree("{}"));
    }

    private static MemoryMessage calls(ToolCall... calls) {
        return message(ToolMemoryCodec.TOOL_CALL_ROLE, ToolMemoryCodec.encodeCalls(List.of(calls)));
    }

    private static MemoryMessage result(ToolCall call, String text) {
        return message(ToolMemoryCodec.TOOL_RESULT_ROLE,
                ToolMemoryCodec.encodeResult(ToolResult.ok(call.id(), call.toolName(), text, 1)));
    }

    private static MemoryMessage textMessage(String role, String text) {
        return message(role, List.of(new MessageBlock(MessageBlock.BlockType.TEXT, text, null)));
    }

    private static MemoryMessage message(
            String role, List<com.harness.core.model.MessageBlock> blocks) {
        return new MemoryMessage(1, "session-1", "trace-1", role, blocks, false, Instant.now());
    }
}
