package com.harness.input.memory;

import com.harness.core.env.EnvConfig;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.provider.ChatModelProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemoryCompressorTest {

    @Mock TurnCompressibleMessageStore messageStore;
    @Mock SessionStore sessionStore;
    @Mock ChatModelProvider chatModelProvider;

    private MemoryCompressor compressor;
    private List<MemoryMessage> persisted;

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of(
                "HARNESS_CTX_COMPRESS_MAJOR", "50",
                "HARNESS_CTX_COMPRESS_MAJOR_TARGET", "0",
                "HARNESS_CTX_COMPRESS_KEEP_RECENT_TURNS", "0"));
        when(chatModelProvider.chatModel()).thenReturn(null);
        compressor = new MemoryCompressor(messageStore, sessionStore, chatModelProvider);
    }

    @Test
    void caseA_ordinaryCompletedTurn_becomesOneSummary() {
        backStore(List.of(
                message(1, "turn-a", "user", "Explain the lifecycle in detail"),
                message(2, "turn-a", "assistant", "The lifecycle completes normally")));

        var result = compress();

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.MAJOR);
        assertThat(persisted).singleElement().satisfies(summary -> {
            assertThat(summary.isSummary()).isTrue();
            assertThat(summary.traceId()).isEqualTo("turn-a");
            assertThat(metadata(summary)).containsEntry("summaryType", "turn")
                    .containsEntry("turnId", "turn-a")
                    .containsEntry("outcome", "completed");
        });
    }

    @Test
    void caseB_threeToolCalls_preserveStructuredFactsAndDropRawBlocks() {
        backStore(List.of(
                message(1, "turn-b", "user", "Run all three tools and report the result"),
                toolCalls(2, "turn-b", List.of(
                        Map.of("id", "call-1", "name", "search"),
                        Map.of("id", "call-2", "name", "read"),
                        Map.of("id", "call-3", "name", "write"))),
                toolResult(3, "turn-b", "call-1", "search", "SUCCEEDED", "found record", null),
                toolResult(4, "turn-b", "call-2", "read", "SUCCEEDED", "read record", null),
                toolResult(5, "turn-b", "call-3", "write", "SUCCEEDED", "saved record", null),
                message(6, "turn-b", "assistant", "All three operations completed")));

        compress();

        assertThat(persisted).singleElement().satisfies(summary -> {
            assertThat(summary.role()).isEqualTo("system");
            assertThat(toolFacts(summary)).extracting(fact -> fact.get("toolName"))
                    .containsExactly("search", "read", "write");
            assertThat(summary.modelText()).contains("found record", "read record", "saved record");
        });
    }

    @Test
    void caseC_spawnSuspendResume_closesAndCompressesSameTurnOnlyAfterFinalAnswer() {
        backStore(List.of(
                message(1, "turn-c", "user", "Delegate this work and continue afterwards"),
                message(2, "turn-c", "assistant_partial", "Sub-agent started"),
                toolResult(3, "turn-c", null, "spawn_subagent", "RUNNING", "task-1", null)));

        assertThat(compress().type())
                .isEqualTo(MemoryCompressor.CompressionResult.CompressionType.NONE);
        verify(messageStore, never()).replaceTurnWithSummary(eq("sess1"), anyList(), any());

        persisted.add(toolResult(
                4, "turn-c", null, "spawn_subagent", "COMPLETED", "task-1 finished", null));
        persisted.add(message(5, "turn-c", "assistant", "The delegated work is complete"));

        assertThat(compress().type())
                .isEqualTo(MemoryCompressor.CompressionResult.CompressionType.MAJOR);
        assertThat(persisted).singleElement().satisfies(summary ->
                assertThat(metadata(summary)).containsEntry("turnId", "turn-c"));
    }

    @Test
    void caseD_twoCompletedTurns_areCompressedOldestFirst() {
        backStore(List.of(
                message(1, "turn-d1", "user", "First sufficiently long question"),
                message(2, "turn-d1", "assistant", "First sufficiently long answer"),
                message(3, "turn-d2", "user", "Second sufficiently long question"),
                message(4, "turn-d2", "assistant", "Second sufficiently long answer")));
        List<String> replacedTurns = new ArrayList<>();
        installReplacement(replacedTurns);

        compress();

        assertThat(replacedTurns).containsExactly("turn-d1", "turn-d2");
        assertThat(persisted).hasSize(2).allMatch(MemoryMessage::isSummary);
    }

    @Test
    void caseE_activeTurn_isNeverCompressed() {
        backStore(List.of(
                message(1, "turn-e1", "user", "Completed turn question with enough text"),
                message(2, "turn-e1", "assistant", "Completed turn answer with enough text"),
                message(3, "turn-e2", "user", "Current unfinished turn must remain raw")));

        compress();

        assertThat(persisted).hasSize(2);
        assertThat(persisted).anySatisfy(message -> {
            assertThat(message.traceId()).isEqualTo("turn-e2");
            assertThat(message.role()).isEqualTo("user");
            assertThat(message.isSummary()).isFalse();
        });
    }

    @Test
    void caseF_existingSummary_isNotCompressedAgain() {
        MemoryMessage summary = turnSummary(1, "turn-f");
        backStore(List.of(summary));

        assertThat(compress().type())
                .isEqualTo(MemoryCompressor.CompressionResult.CompressionType.NONE);
        assertThat(persisted).containsExactly(summary);
        verify(messageStore, never()).replaceTurnWithSummary(eq("sess1"), anyList(), any());
    }

    @Test
    void caseG_failedTool_preservesFailureStatusAndError() {
        backStore(List.of(
                message(1, "turn-g", "user", "Attempt an operation that may fail"),
                toolCalls(2, "turn-g", List.of(Map.of("id", "call-g", "name", "dangerous_tool"))),
                toolResult(3, "turn-g", "call-g", "dangerous_tool", "FAILED",
                        "ERROR: permission denied", "permission denied"),
                message(4, "turn-g", "assistant", "The operation failed and was not hidden")));

        compress();

        assertThat(metadata(persisted.getFirst()))
                .containsEntry("outcome", "completed_with_tool_failure");
        assertThat(toolFacts(persisted.getFirst()).getFirst())
                .containsEntry("status", "FAILED")
                .containsEntry("error", "permission denied");
    }

    @Test
    void caseH_artifactIdentity_survivesCompression() {
        MessageBlock result = new MessageBlock(
                MessageBlock.BlockType.TEXT,
                "generated report",
                null,
                Map.of("toolCallId", "call-h", "toolName", "report", "status", "SUCCEEDED"));
        MessageBlock artifact = new MessageBlock(
                MessageBlock.BlockType.ARTIFACT, "report", "artifact-42");
        backStore(List.of(
                message(1, "turn-h", "user", "Generate a durable report artifact"),
                toolCalls(2, "turn-h", List.of(Map.of("id", "call-h", "name", "report"))),
                new MemoryMessage(3, "sess1", "turn-h", "tool",
                        List.of(result, artifact), false, Instant.now()),
                message(4, "turn-h", "assistant", "The report artifact is ready")));

        compress();

        assertThat(toolFacts(persisted.getFirst()).getFirst().get("artifactIds"))
                .isEqualTo(List.of("artifact-42"));
    }

    @Test
    void recentCompletedTurn_canBeKeptUncompressed() {
        EnvConfig.init(Map.of(
                "HARNESS_CTX_COMPRESS_MAJOR", "50",
                "HARNESS_CTX_COMPRESS_MAJOR_TARGET", "0",
                "HARNESS_CTX_COMPRESS_KEEP_RECENT_TURNS", "1"));
        compressor = new MemoryCompressor(messageStore, sessionStore, chatModelProvider);
        backStore(List.of(
                message(1, "old", "user", "Old question with enough detail"),
                message(2, "old", "assistant", "Old answer with enough detail"),
                message(3, "recent", "user", "Recent question with enough detail"),
                message(4, "recent", "assistant", "Recent answer with enough detail")));

        compress();

        assertThat(persisted).filteredOn(message -> "recent".equals(message.traceId()))
                .hasSize(2).allMatch(message -> !message.isSummary());
    }

    @Test
    void baseMessageStoreRemainsCompatibleWithTurnCompressionDisabled() {
        MessageStore baseStore = org.mockito.Mockito.mock(MessageStore.class);
        MemoryCompressor baseCompressor = new MemoryCompressor(
                baseStore, sessionStore, chatModelProvider);

        var result = baseCompressor.compressIfNeeded(
                "sess1",
                List.of(
                        message(1, "legacy", "user", "A long user request"),
                        message(2, "legacy", "assistant", "A complete response")),
                900,
                900,
                1_000);

        assertThat(result.type())
                .isEqualTo(MemoryCompressor.CompressionResult.CompressionType.NONE);
        verifyNoInteractions(baseStore);
    }

    private MemoryCompressor.CompressionResult compress() {
        return compressor.compressIfNeeded("sess1", List.copyOf(persisted), 900, 900, 1_000);
    }

    private void backStore(List<MemoryMessage> messages) {
        persisted = new ArrayList<>(messages);
        when(messageStore.loadForContext("sess1")).thenAnswer(ignored -> List.copyOf(persisted));
        installReplacement(null);
    }

    private void installReplacement(List<String> replacedTurns) {
        doAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            MessageWrite write = invocation.getArgument(2);
            if (replacedTurns != null) replacedTurns.add(write.traceId());
            int insertionIndex = 0;
            for (int index = 0; index < persisted.size(); index++) {
                if (ids.contains(persisted.get(index).id())) {
                    insertionIndex = index;
                    break;
                }
            }
            long summaryId = ids.stream().min(Comparator.naturalOrder()).orElseThrow();
            persisted.removeIf(message -> ids.contains(message.id()));
            persisted.add(insertionIndex, new MemoryMessage(
                    summaryId,
                    write.sessionId(),
                    write.traceId(),
                    write.role(),
                    write.content(),
                    true,
                    Instant.now()));
            return null;
        }).when(messageStore).replaceTurnWithSummary(eq("sess1"), anyList(), any());
    }

    private static MemoryMessage message(long id, String turnId, String role, String text) {
        return new MemoryMessage(
                id,
                "sess1",
                turnId,
                role,
                List.of(new MessageBlock(MessageBlock.BlockType.TEXT, text, null)),
                false,
                Instant.now());
    }

    private static MemoryMessage toolCalls(
            long id,
            String turnId,
            List<Map<String, String>> calls
    ) {
        return new MemoryMessage(
                id,
                "sess1",
                turnId,
                "assistant_tool_call",
                List.of(new MessageBlock(
                        MessageBlock.BlockType.TEXT,
                        "tool calls",
                        null,
                        Map.of("toolCalls", calls))),
                false,
                Instant.now());
    }

    private static MemoryMessage toolResult(
            long id,
            String turnId,
            String callId,
            String toolName,
            String status,
            String text,
            String error
    ) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        if (callId != null) metadata.put("toolCallId", callId);
        metadata.put("toolName", toolName);
        metadata.put("status", status);
        if (error != null) metadata.put("error", error);
        return new MemoryMessage(
                id,
                "sess1",
                turnId,
                "tool",
                List.of(new MessageBlock(MessageBlock.BlockType.TEXT, text, null, metadata)),
                false,
                Instant.now());
    }

    private static MemoryMessage turnSummary(long id, String turnId) {
        MessageBlock block = new MessageBlock(
                MessageBlock.BlockType.TEXT,
                "existing summary",
                null,
                Map.of("summaryType", "turn", "turnId", turnId));
        return new MemoryMessage(
                id, "sess1", turnId, "system", List.of(block), true, Instant.now());
    }

    private static Map<String, Object> metadata(MemoryMessage summary) {
        return summary.content().getFirst().metadata();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolFacts(MemoryMessage summary) {
        return (List<Map<String, Object>>) metadata(summary).get("tools");
    }
}
