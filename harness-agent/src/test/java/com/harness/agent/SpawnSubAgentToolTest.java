package com.harness.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.CancellationToken;
import com.harness.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpawnSubAgentToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearContext() {
        SpawnSubAgentTool.clearCurrentRunContext();
    }

    @Test
    void parsesOptionalCompletionContractAndExposesItInToolSchema() throws Exception {
        SubAgentManager manager = mock(SubAgentManager.class);
        SpawnSubAgentTool tool = new SpawnSubAgentTool(manager);
        AgentRunContext runContext = new AgentRunContext(
                "run-1", "session-1", new CancellationToken(), null,
                new ToolRegistry().snapshot());
        SpawnSubAgentTool.setCurrentRunContext(runContext);
        when(manager.submitTask(eq(runContext), any(), eq("session-1")))
                .thenAnswer(invocation -> {
                    SubAgentTask task = invocation.getArgument(1);
                    return new SubAgentTaskRecord(
                            task.taskId(), runContext.runId(), runContext.sessionId(),
                            task, new CancellationToken());
                });
        var arguments = objectMapper.readTree("""
                {
                  "persona":"reporter",
                  "system_prompt":"create a verified report",
                  "task_description":"build report",
                  "context":"customer context",
                  "tools":["report_tool"],
                  "completion_contract":{
                    "required_successful_tools":["report_tool"],
                    "required_artifacts":[{
                      "artifact_type":"DOCUMENT",
                      "allowed_mime_types":["application/pdf"],
                      "min_count":1
                    }],
                    "output_schema":{
                      "type":"object",
                      "properties":{"summary":{"type":"string"}},
                      "required":["summary"],
                      "additionalProperties":false
                    }
                  }
                }
                """);

        String response = tool.execute(arguments);

        ArgumentCaptor<SubAgentTask> taskCaptor = ArgumentCaptor.forClass(SubAgentTask.class);
        verify(manager).submitTask(eq(runContext), taskCaptor.capture(), eq("session-1"));
        SubAgentCompletionContract contract = taskCaptor.getValue().completionContract();
        assertThat(contract.requiredSuccessfulTools()).containsExactly("report_tool");
        assertThat(contract.requiredArtifacts()).containsExactly(
                new RequiredArtifact("DOCUMENT", java.util.Set.of("application/pdf"), 1));
        assertThat(contract.outputSchema().path("type").asText()).isEqualTo("object");
        assertThat(objectMapper.readTree(response).path("accepted").asBoolean()).isTrue();
        assertThat(tool.spec().parameters().path("properties")
                .has("completion_contract")).isTrue();
    }
}
