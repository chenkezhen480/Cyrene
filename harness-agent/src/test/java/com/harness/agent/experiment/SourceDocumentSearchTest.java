package com.harness.agent.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class SourceDocumentSearchTest {
    @Test
    void bothGroupsCannotReadLearnedAnswersFromOtherTrials() throws Exception {
        var captured = new AtomicReference<JsonNode>();
        var delegate = new Tool() {
            @Override public ToolSpec spec() { return null; }
            @Override public String execute(JsonNode args) { captured.set(args); return "accepted"; }
        };
        var mapper = new ObjectMapper();
        var args = mapper.readTree("{\"query\":\"policy\",\"knowledgeTypes\":[\"USER_EPISODE\",\"OPERATION_PLAYBOOK\"],\"limit\":2}");
        assertThat(new SourceDocumentSearch(delegate).execute(args)).isEqualTo("accepted");
        assertThat(captured.get().path("knowledgeTypes")).isEqualTo(mapper.readTree("[\"SOURCE_DOCUMENT\"]"));
        assertThat(captured.get().path("limit").intValue()).isEqualTo(2);
        assertThat(args.path("knowledgeTypes").get(0).asText()).isEqualTo("USER_EPISODE");
    }
}
