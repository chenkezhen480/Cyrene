package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SavePreferenceToolTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    record SavedPref(String userId, String category, String content, String sessionId) {}

    List<SavedPref> saved;
    SavePreferenceTool tool;

    @BeforeEach
    void setUp() {
        saved = new ArrayList<>();
        tool = new SavePreferenceTool((userId, category, content, sessionId) ->
                saved.add(new SavedPref(userId, category, content, sessionId)));
        SavePreferenceTool.setCurrentUserId("user-1");
        SavePreferenceTool.setCurrentSessionId("session-1");
    }

    @AfterEach
    void tearDown() {
        SavePreferenceTool.clearContext();
    }

    private ObjectNode args(String category, String content) {
        ObjectNode node = MAPPER.createObjectNode();
        if (category != null) node.put("category", category);
        if (content != null) node.put("content", content);
        return node;
    }

    @Test
    void spec_returnsCorrectToolSpec() {
        var spec = tool.spec();
        assertThat(spec.name()).isEqualTo("save_preference");
        assertThat(spec.description()).isNotEmpty();
    }

    @Test
    void execute_missingCategory_returnsError() {
        ObjectNode emptyArgs = MAPPER.createObjectNode();
        emptyArgs.put("content", "some content");
        String result = tool.execute(emptyArgs);
        assertThat(result).contains("ERROR");
        assertThat(result).contains("category");
    }

    @Test
    void execute_missingContent_returnsError() {
        ObjectNode emptyArgs = MAPPER.createObjectNode();
        emptyArgs.put("category", "language");
        String result = tool.execute(emptyArgs);
        assertThat(result).contains("ERROR");
        assertThat(result).contains("content");
    }

    @Test
    void execute_noUserId_returnsError() {
        SavePreferenceTool.clearContext();
        String result = tool.execute(args("language", "prefer English"));
        assertThat(result).contains("ERROR");
        assertThat(result).contains("No user context");
    }

    @Test
    void execute_validArgs_savesPreference() {
        String result = tool.execute(args("language", "prefer concise code style"));

        assertThat(result).contains("Preference saved");
        assertThat(result).contains("language");
        assertThat(result).contains("prefer concise code style");

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).userId()).isEqualTo("user-1");
        assertThat(saved.get(0).category()).isEqualTo("language");
        assertThat(saved.get(0).content()).isEqualTo("prefer concise code style");
        assertThat(saved.get(0).sessionId()).isEqualTo("session-1");
    }

    @Test
    void execute_categoryIsLowercased() {
        tool.execute(args("Code_Style", "use 4 spaces indentation"));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).category()).isEqualTo("code_style");
    }

    @Test
    void execute_saverThrows_returnsError() {
        SavePreferenceTool failingTool = new SavePreferenceTool((userId, category, content, sessionId) -> {
            throw new RuntimeException("DB connection failed");
        });
        SavePreferenceTool.setCurrentUserId("user-1");

        String result = failingTool.execute(args("language", "English"));
        assertThat(result).contains("ERROR");
        assertThat(result).contains("DB connection failed");
    }

    @Test
    void execute_multipleSaves_allPersisted() {
        tool.execute(args("language", "English"));
        tool.execute(args("tone", "concise"));
        tool.execute(args("code_style", "4 spaces"));

        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).category()).isEqualTo("language");
        assertThat(saved.get(1).category()).isEqualTo("tone");
        assertThat(saved.get(2).category()).isEqualTo("code_style");
    }
}
