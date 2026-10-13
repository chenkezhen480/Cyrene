package com.harness.agent.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Re-scores preserved paid answers without constructing providers or making network calls. */
class ExperimentReevaluationIT {
    @Test
    void reevaluateSavedNativeAnswers() throws Exception {
        String rowsProperty = System.getProperty("cyrene.experiment.reevaluate.rows");
        assumeTrue(rowsProperty != null && !rowsProperty.isBlank(), "Explicit saved result path required");
        Path rows = Path.of(rowsProperty);
        Path tasks = Path.of(System.getProperty("cyrene.experiment.reevaluate.tasks"));
        Path output = Path.of(System.getProperty("cyrene.experiment.reevaluate.output"));
        var mapper = new ObjectMapper();
        var expected = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        for (var task : mapper.readTree(tasks.toFile()).get("tasks")) expected.put(task.get("id").asText(), task.get("expectations"));
        String sourceHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(rows)));
        String taskHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tasks)));
        Files.createFile(output);
        int count = 0;
        int failures = 0;
        try (var lines = Files.lines(rows, StandardCharsets.UTF_8)) {
            for (String line : lines.toList()) {
                var original = mapper.readTree(line);
                if (!"bare_langchain4j".equals(original.path("condition").asText())) continue;
                var expectations = expected.get(original.path("taskId").asText());
                if (expectations == null) throw new IllegalArgumentException("No current expectations for saved task");
                var diagnostics = ExperimentEvaluation.validate(expectations, original.path("answer").asText(),
                        original.get("initialFixtureState"), original.get("finalFixtureState"));
                var evaluated = mapper.createObjectNode().put("taskId", original.path("taskId").asText())
                        .put("condition", "bare_langchain4j").put("evaluatedAt", Instant.now().toString())
                        .put("sourceRowsSha256", sourceHash).put("taskFileSha256", taskHash)
                        .put("originalPassed", original.path("passed").asBoolean()).put("passed", diagnostics.isEmpty());
                evaluated.set("validationDiagnostics", mapper.valueToTree(diagnostics));
                Files.writeString(output, mapper.writeValueAsString(evaluated) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                count++;
                if (!diagnostics.isEmpty()) failures++;
            }
        }
        System.out.println("Saved native re-evaluation: trials=" + count + " passed=" + (count - failures) + " failed=" + failures + " newModelCalls=0");
    }
}
