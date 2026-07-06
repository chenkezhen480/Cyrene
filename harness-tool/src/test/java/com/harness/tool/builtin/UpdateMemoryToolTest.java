package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateMemoryToolTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    record SavedMemory(String userId, String content, String sessionId) {}

    List<SavedMemory> saved;
    UpdateMemoryTool tool;

    @BeforeEach
    void setUp() {
        saved = new ArrayList<>();
        tool = new UpdateMemoryTool((userId, content, sessionId) ->
                saved.add(new SavedMemory(userId, content, sessionId)));
        UpdateMemoryTool.setCurrentUserId("user-1");
        UpdateMemoryTool.setCurrentSessionId("session-1");
    }

    @AfterEach
    void tearDown() {
        UpdateMemoryTool.clearContext();
    }

    private ObjectNode args(String memory) {
        ObjectNode node = MAPPER.createObjectNode();
        if (memory != null) node.put("memory", memory);
        return node;
    }

    @Test
    void spec_returnsCorrectToolSpec() {
        var spec = tool.spec();
        assertThat(spec.name()).isEqualTo("update_memory");
        assertThat(spec.description()).isNotEmpty();
    }

    @Test
    void execute_missingMemory_returnsError() {
        ObjectNode emptyArgs = MAPPER.createObjectNode();
        String result = tool.execute(emptyArgs);
        assertThat(result).contains("ERROR");
        assertThat(result).contains("memory");
    }

    @Test
    void execute_emptyMemory_returnsError() {
        String result = tool.execute(args("  "));
        assertThat(result).contains("ERROR");
        assertThat(result).contains("memory");
    }

    @Test
    void execute_noUserId_returnsError() {
        UpdateMemoryTool.clearContext();
        String result = tool.execute(args("likes Python"));
        assertThat(result).contains("ERROR");
        assertThat(result).contains("No user context");
    }

    @Test
    void execute_validArgs_savesMemory() {
        String result = tool.execute(args("likes concise code style"));

        assertThat(result).contains("Memory updated successfully");
        assertThat(result).contains("chars");

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).userId()).isEqualTo("user-1");
        assertThat(saved.get(0).content()).isEqualTo("likes concise code style");
        assertThat(saved.get(0).sessionId()).isEqualTo("session-1");
    }

    @Test
    void execute_saverThrows_returnsError() {
        UpdateMemoryTool failingTool = new UpdateMemoryTool((userId, content, sessionId) -> {
            throw new RuntimeException("DB connection failed");
        });
        UpdateMemoryTool.setCurrentUserId("user-1");

        String result = failingTool.execute(args("likes English"));
        assertThat(result).contains("ERROR");
        assertThat(result).contains("DB connection failed");
    }

    @Test
    void execute_multipleSaves_allPersisted() {
        tool.execute(args("likes English"));
        tool.execute(args("prefers concise tone"));
        tool.execute(args("uses 4 spaces indentation"));

        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).content()).isEqualTo("likes English");
        assertThat(saved.get(1).content()).isEqualTo("prefers concise tone");
        assertThat(saved.get(2).content()).isEqualTo("uses 4 spaces indentation");
    }
}
