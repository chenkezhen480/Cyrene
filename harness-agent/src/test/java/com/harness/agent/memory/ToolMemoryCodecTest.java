package com.harness.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.Artifact;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
                ToolResult.ResultStatus.SUCCESS);
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

    private static MemoryMessage message(
            String role, List<com.harness.core.model.MessageBlock> blocks) {
        return new MemoryMessage(1, "session-1", role, blocks, false, Instant.now());
    }
}
