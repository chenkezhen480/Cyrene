package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import com.harness.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubAgentCompletionContractValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryArtifactStore artifactStore = new InMemoryArtifactStore();
    private final SubAgentCompletionContractValidator validator =
            new SubAgentCompletionContractValidator(artifactStore, objectMapper);

    @Test
    void satisfiesToolArtifactAndStructuredOutputContract() throws Exception {
        Artifact storedArtifact = new Artifact(
                "artifact-1", "session-1", "report.pdf",
                Artifact.ArtifactType.DOCUMENT, "application/pdf", 12,
                "ignored-in-parent-output", Instant.now());
        artifactStore.save(storedArtifact);
        Artifact reportedArtifact = new Artifact(
                storedArtifact.id(), null, null, Artifact.ArtifactType.OTHER,
                null, 0, null, Instant.EPOCH);
        JsonNode schema = strictResultSchema();
        SubAgentCompletionContract contract = new SubAgentCompletionContract(
                Set.of("report_tool"),
                List.of(new RequiredArtifact(
                        "document", Set.of("application/pdf"), 1)),
                schema);
        ReActStep step = stepWithResult(ToolResult.ok(
                "call-1", "report_tool", "ok", 5));

        SubAgentCompletionContractValidator.Evaluation evaluation = validator.evaluate(
                contract, List.of(step), List.of(reportedArtifact),
                "{\"summary\":\"complete\"}");

        assertThat(evaluation.contractValidation().status())
                .isEqualTo(ContractValidation.Status.SATISFIED);
        assertThat(evaluation.artifacts()).containsExactly(storedArtifact);
        assertThat(evaluation.structuredOutput().path("summary").asText())
                .isEqualTo("complete");
        assertThat(evaluation.toolExecutionSummary().tools().get("report_tool"))
                .extracting(
                        ToolExecutionSummary.ToolExecutionStats::attemptCount,
                        ToolExecutionSummary.ToolExecutionStats::successfulCount,
                        ToolExecutionSummary.ToolExecutionStats::failedCount)
                .containsExactly(1, 1, 0);
    }

    @Test
    void reportsContractViolationsWithoutRetryingOrTrustingReportedMetadata() throws Exception {
        Artifact reportedOnly = new Artifact(
                "missing", null, "claimed.pdf", Artifact.ArtifactType.DOCUMENT,
                "application/pdf", 99, "claimed/path", Instant.now());
        SubAgentCompletionContract contract = new SubAgentCompletionContract(
                Set.of("report_tool"),
                List.of(new RequiredArtifact("DOCUMENT", Set.of(), 1)),
                strictResultSchema());
        ReActStep failedStep = stepWithResult(ToolResult.fail(
                "call-1", "report_tool", "upstream rejected request", 5));

        SubAgentCompletionContractValidator.Evaluation evaluation = validator.evaluate(
                contract, List.of(failedStep), List.of(reportedOnly), "not-json");

        assertThat(evaluation.contractValidation().status())
                .isEqualTo(ContractValidation.Status.FAILED_CONTRACT);
        assertThat(evaluation.contractValidation().violations())
                .anyMatch(value -> value.contains("Required tool"))
                .anyMatch(value -> value.contains("Required artifact count"))
                .anyMatch(value -> value.contains("invalid JSON"));
        assertThat(evaluation.artifacts()).isEmpty();
    }

    @Test
    void validatesAllowedAndRequiredToolsAgainstParentCatalog() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("report_tool"));
        SubAgentTask validTask = task(
                List.of("report_tool"),
                new SubAgentCompletionContract(
                        Set.of("report_tool"), List.of(), strictResultSchema()));

        validator.validateTaskDefinition(validTask, registry.snapshot());

        assertThatThrownBy(() -> validator.validateTaskDefinition(
                task(List.of("report_tool"), new SubAgentCompletionContract(
                        Set.of("missing_tool"), List.of(), null)),
                registry.snapshot()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in allowed tools");
        assertThatThrownBy(() -> validator.validateTaskDefinition(
                task(List.of("spawn_subagent"), null), registry.snapshot()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orchestration tool");
        assertThatThrownBy(() -> validator.validateTaskDefinition(
                task(List.of("unknown_tool"), null), registry.snapshot()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent run catalog");
    }

    @Test
    void ordinaryTaskKeepsFreeFormCompletionSemantics() {
        SubAgentCompletionContractValidator.Evaluation evaluation = validator.evaluate(
                null, List.of(), List.of(), "free-form result");

        assertThat(evaluation.contractValidation())
                .isEqualTo(ContractValidation.notDeclared());
        assertThat(evaluation.structuredOutput()).isNull();
    }

    private JsonNode strictResultSchema() throws Exception {
        return objectMapper.readTree("""
                {
                  "type":"object",
                  "properties":{"summary":{"type":"string"}},
                  "required":["summary"],
                  "additionalProperties":false
                }
                """);
    }

    private static ReActStep stepWithResult(ToolResult result) {
        return new ReActStep(1, null, null, List.of(), List.of(result), null, null);
    }

    private static SubAgentTask task(
            List<String> tools, SubAgentCompletionContract contract) {
        return new SubAgentTask(
                "task-1", "description", "context", "persona", "prompt",
                tools, List.of(), contract);
    }

    private Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, name, objectMapper.createObjectNode());
            }

            @Override
            public String execute(JsonNode arguments) {
                return "ok";
            }
        };
    }

    private static final class InMemoryArtifactStore implements ArtifactStore {
        private final Map<String, Artifact> artifacts = new HashMap<>();

        @Override
        public void save(Artifact artifact) {
            artifacts.put(artifact.id(), artifact);
        }

        @Override
        public Optional<Artifact> get(String id) {
            return Optional.ofNullable(artifacts.get(id));
        }

        @Override
        public void delete(String id) {
            artifacts.remove(id);
        }

        @Override
        public List<Artifact> listBySession(String sessionId) {
            return artifacts.values().stream()
                    .filter(artifact -> sessionId.equals(artifact.sessionId()))
                    .toList();
        }
    }
}
