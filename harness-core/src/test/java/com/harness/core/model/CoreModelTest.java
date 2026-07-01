package com.harness.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batch tests for simple model records/enums not covered by dedicated test files.
 */
class CoreModelTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- ToolSpec ----

    @Test
    void toolSpec_convenienceConstructor_setsDefaults() {
        ObjectNode params = MAPPER.createObjectNode();
        ToolSpec spec = new ToolSpec("search", "Search tool", params);

        assertThat(spec.name()).isEqualTo("search");
        assertThat(spec.description()).isEqualTo("Search tool");
        assertThat(spec.parameters()).isEqualTo(params);
        assertThat(spec.tags()).isEmpty();
        assertThat(spec.requiresConfirmation()).isFalse();
    }

    @Test
    void toolSpec_fullConstructor() {
        ObjectNode params = MAPPER.createObjectNode();
        ToolSpec spec = new ToolSpec("db_exec", "Execute SQL", params, Set.of("dangerous"), true);

        assertThat(spec.tags()).containsExactly("dangerous");
        assertThat(spec.requiresConfirmation()).isTrue();
    }

    @Test
    void toolSpec_recordEquality() {
        ObjectNode params = MAPPER.createObjectNode();
        ToolSpec a = new ToolSpec("x", "desc", params);
        ToolSpec b = new ToolSpec("x", "desc", params);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // ---- ToolCall ----

    @Test
    void toolCall_of_generatesId() {
        ObjectNode args = MAPPER.createObjectNode();
        ToolCall call = ToolCall.of("search", args);

        assertThat(call.toolName()).isEqualTo("search");
        assertThat(call.arguments()).isEqualTo(args);
        assertThat(call.id()).isNotBlank();
    }

    // ---- Session ----

    @Test
    void session_recordFields() {
        Instant now = Instant.now();
        Session s = new Session("id1", "user1", "My Chat", now, now, null, Session.SessionStatus.active);

        assertThat(s.id()).isEqualTo("id1");
        assertThat(s.userId()).isEqualTo("user1");
        assertThat(s.title()).isEqualTo("My Chat");
        assertThat(s.status()).isEqualTo(Session.SessionStatus.active);
        assertThat(s.endedAt()).isNull();
    }

    @Test
    void sessionStatus_enumValues() {
        assertThat(Session.SessionStatus.values()).containsExactly(
                Session.SessionStatus.active, Session.SessionStatus.ended, Session.SessionStatus.timeout);
    }

    // ---- Preference ----

    @Test
    void preference_recordFields() {
        Instant now = Instant.now();
        Preference p = new Preference(1L, "user1", "style", "Be concise", "sess1", now, now);

        assertThat(p.id()).isEqualTo(1L);
        assertThat(p.userId()).isEqualTo("user1");
        assertThat(p.category()).isEqualTo("style");
        assertThat(p.content()).isEqualTo("Be concise");
    }

    // ---- ReActStep ----

    @Test
    void reactStep_recordFields() {
        var inspection = new ReActStep.InspectionResult(
                ReActStep.InspectionResult.InspectionStatus.PASS, "ok");
        ReActStep step = new ReActStep(1, "I need to search", "web_search",
                List.of(), List.of(), "Found results", inspection);

        assertThat(step.stepNumber()).isEqualTo(1);
        assertThat(step.thought()).isEqualTo("I need to search");
        assertThat(step.action()).isEqualTo("web_search");
        assertThat(step.inspection().status()).isEqualTo(ReActStep.InspectionResult.InspectionStatus.PASS);
    }

    @Test
    void inspectionStatus_enumValues() {
        assertThat(ReActStep.InspectionResult.InspectionStatus.values()).containsExactly(
                ReActStep.InspectionResult.InspectionStatus.PASS,
                ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR,
                ReActStep.InspectionResult.InspectionStatus.WRONG_TOOL,
                ReActStep.InspectionResult.InspectionStatus.INSUFFICIENT,
                ReActStep.InspectionResult.InspectionStatus.LOOP_DETECTED);
    }

    // ---- ParsedContent ----

    @Test
    void parsedContent_recordFields() {
        ParsedContent pc = new ParsedContent("hello world", ParsedContent.ParseStrategy.DIRECT, 1, Map.of());

        assertThat(pc.text()).isEqualTo("hello world");
        assertThat(pc.strategy()).isEqualTo(ParsedContent.ParseStrategy.DIRECT);
        assertThat(pc.chunkCount()).isEqualTo(1);
    }

    @Test
    void parseStrategy_enumValues() {
        assertThat(ParsedContent.ParseStrategy.values()).containsExactly(
                ParsedContent.ParseStrategy.DIRECT, ParsedContent.ParseStrategy.CHUNKED_REDUCE);
    }
}
