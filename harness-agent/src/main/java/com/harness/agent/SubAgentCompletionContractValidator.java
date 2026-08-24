package com.harness.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolResult;
import com.harness.tool.RunToolCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates sub-agent contract declarations and evaluates completed runs. */
final class SubAgentCompletionContractValidator {

    static final Set<String> ORCHESTRATION_TOOLS = Set.of(
            "spawn_subagent", "await_subagents", "get_subagents", "cancel_subagents");

    private static final Set<String> SUPPORTED_SCHEMA_KEYWORDS = Set.of(
            "type", "description", "properties", "required",
            "additionalProperties", "items", "enum");
    private static final Set<String> SUPPORTED_SCHEMA_TYPES = Set.of(
            "object", "array", "string", "integer", "number", "boolean");
    private static final int MAX_SCHEMA_DEPTH = 12;
    private static final int MAX_SCHEMA_PROPERTIES = 200;
    private static final int MAX_OUTPUT_VIOLATIONS = 20;

    private final ArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    SubAgentCompletionContractValidator(ArtifactStore artifactStore, ObjectMapper objectMapper) {
        this.artifactStore = java.util.Objects.requireNonNull(artifactStore, "artifactStore");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    void validateTaskDefinition(SubAgentTask task, RunToolCatalog parentCatalog) {
        Set<String> allowedTools = Set.copyOf(task.tools());
        for (String toolName : allowedTools) {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("Allowed tool names must not be blank");
            }
            if (ORCHESTRATION_TOOLS.contains(toolName)) {
                throw new IllegalArgumentException(
                        "Sub-agent orchestration tool is not allowed: " + toolName);
            }
            if (!parentCatalog.contains(toolName)) {
                throw new IllegalArgumentException(
                        "Allowed tool is not available in the parent run catalog: " + toolName);
            }
        }

        SubAgentCompletionContract contract = task.completionContract();
        if (contract == null) {
            return;
        }
        for (String toolName : contract.requiredSuccessfulTools()) {
            if (!allowedTools.contains(toolName)) {
                throw new IllegalArgumentException(
                        "Required successful tool is not in allowed tools: " + toolName);
            }
            if (ORCHESTRATION_TOOLS.contains(toolName)) {
                throw new IllegalArgumentException(
                        "Sub-agent orchestration tool cannot be required: " + toolName);
            }
            if (!parentCatalog.contains(toolName)) {
                throw new IllegalArgumentException(
                        "Required successful tool is not available in the parent run catalog: "
                                + toolName);
            }
        }
        if (contract.outputSchema() != null) {
            SchemaCounter counter = new SchemaCounter();
            validateSchemaNode(contract.outputSchema(), "$", 1, counter);
            if (!"object".equals(contract.outputSchema().path("type").asText())) {
                throw new IllegalArgumentException("outputSchema root type must be object");
            }
        }
    }

    Evaluation evaluate(
            SubAgentCompletionContract contract,
            List<ReActStep> steps,
            List<Artifact> reportedArtifacts,
            String output
    ) {
        ToolExecutionSummary toolSummary = summarizeTools(steps);
        List<Artifact> verifiedArtifacts = resolveArtifacts(reportedArtifacts);
        if (contract == null) {
            return new Evaluation(
                    verifiedArtifacts,
                    toolSummary,
                    ContractValidation.notDeclared(),
                    null);
        }

        List<String> violations = new ArrayList<>();
        for (String requiredTool : contract.requiredSuccessfulTools()) {
            ToolExecutionSummary.ToolExecutionStats stats =
                    toolSummary.tools().get(requiredTool);
            if (stats == null || stats.successfulCount() == 0) {
                String latestError = stats != null ? stats.latestError() : null;
                violations.add("Required tool did not complete successfully: " + requiredTool
                        + (latestError == null ? "" : " (latest error: " + latestError + ")"));
            }
        }
        for (RequiredArtifact requirement : contract.requiredArtifacts()) {
            long count = verifiedArtifacts.stream()
                    .filter(artifact -> artifact.type().name().equals(requirement.artifactType()))
                    .filter(artifact -> requirement.allowedMimeTypes().isEmpty()
                            || (artifact.mimeType() != null && requirement.allowedMimeTypes().contains(
                                    artifact.mimeType().toLowerCase(Locale.ROOT))))
                    .count();
            if (count < requirement.minCount()) {
                violations.add("Required artifact count not met: type="
                        + requirement.artifactType() + ", expected=" + requirement.minCount()
                        + ", actual=" + count);
            }
        }

        JsonNode structuredOutput = null;
        if (contract.outputSchema() != null) {
            structuredOutput = parseStructuredOutput(output, violations);
            if (structuredOutput != null) {
                validateOutputNode(structuredOutput, contract.outputSchema(), "$", violations);
            }
        }

        ContractValidation validation = violations.isEmpty()
                ? new ContractValidation(true, true,
                        ContractValidation.Status.SATISFIED, List.of())
                : new ContractValidation(true, false,
                        ContractValidation.Status.FAILED_CONTRACT, violations);
        return new Evaluation(verifiedArtifacts, toolSummary, validation, structuredOutput);
    }

    private List<Artifact> resolveArtifacts(List<Artifact> reportedArtifacts) {
        if (reportedArtifacts == null || reportedArtifacts.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Artifact> verified = new LinkedHashMap<>();
        for (Artifact reported : reportedArtifacts) {
            if (reported != null && reported.id() != null) {
                artifactStore.get(reported.id())
                        .ifPresent(actual -> verified.put(actual.id(), actual));
            }
        }
        return List.copyOf(verified.values());
    }

    private static ToolExecutionSummary summarizeTools(List<ReActStep> steps) {
        LinkedHashMap<String, MutableToolStats> stats = new LinkedHashMap<>();
        int total = 0;
        if (steps != null) {
            for (ReActStep step : steps) {
                if (step.toolResults() == null) {
                    continue;
                }
                for (ToolResult result : step.toolResults()) {
                    total++;
                    MutableToolStats toolStats = stats.computeIfAbsent(
                            result.toolName(), ignored -> new MutableToolStats());
                    toolStats.attemptCount++;
                    if (result.success()) {
                        toolStats.successfulCount++;
                    } else {
                        toolStats.failedCount++;
                        toolStats.latestError = result.error();
                    }
                }
            }
        }
        LinkedHashMap<String, ToolExecutionSummary.ToolExecutionStats> immutableStats =
                new LinkedHashMap<>();
        stats.forEach((toolName, value) -> immutableStats.put(toolName,
                new ToolExecutionSummary.ToolExecutionStats(
                        value.attemptCount,
                        value.successfulCount,
                        value.failedCount,
                        value.latestError)));
        return new ToolExecutionSummary(total, immutableStats);
    }

    private JsonNode parseStructuredOutput(String output, List<String> violations) {
        if (output == null || output.isBlank()) {
            violations.add("Structured output is empty");
            return null;
        }
        try {
            return objectMapper.readTree(output);
        } catch (JsonProcessingException e) {
            violations.add("Structured output is invalid JSON: " + e.getOriginalMessage());
            return null;
        }
    }

    private static void validateSchemaNode(
            JsonNode schema, String path, int depth, SchemaCounter counter) {
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException(path + ": schema node must be an object");
        }
        if (depth > MAX_SCHEMA_DEPTH) {
            throw new IllegalArgumentException(path + ": schema depth exceeds " + MAX_SCHEMA_DEPTH);
        }
        Iterator<String> names = schema.fieldNames();
        while (names.hasNext()) {
            String keyword = names.next();
            if (!SUPPORTED_SCHEMA_KEYWORDS.contains(keyword)) {
                throw new IllegalArgumentException(
                        path + ": unsupported outputSchema keyword: " + keyword);
            }
        }
        JsonNode type = schema.get("type");
        if (type == null || !type.isTextual() || !SUPPORTED_SCHEMA_TYPES.contains(type.asText())) {
            throw new IllegalArgumentException(path + ": unsupported or missing schema type");
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && (!enumNode.isArray() || enumNode.isEmpty())) {
            throw new IllegalArgumentException(path + ": enum must be a non-empty array");
        }
        if (enumNode != null) {
            for (JsonNode enumValue : enumNode) {
                if (!enumValue.isTextual()) {
                    throw new IllegalArgumentException(
                            path + ": only string enum values are supported");
                }
            }
        }
        if ("object".equals(type.asText())) {
            JsonNode properties = schema.get("properties");
            JsonNode required = schema.get("required");
            if (properties == null || !properties.isObject()
                    || required == null || !required.isArray()) {
                throw new IllegalArgumentException(
                        path + ": object schema requires properties and required");
            }
            Set<String> requiredNames = new HashSet<>();
            required.forEach(value -> {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException(path + ": required entries must be strings");
                }
                requiredNames.add(value.asText());
            });
            Iterator<Map.Entry<String, JsonNode>> propertiesIterator =
                    properties.properties().iterator();
            while (propertiesIterator.hasNext()) {
                Map.Entry<String, JsonNode> property = propertiesIterator.next();
                if (++counter.properties > MAX_SCHEMA_PROPERTIES) {
                    throw new IllegalArgumentException(
                            path + ": schema property count exceeds " + MAX_SCHEMA_PROPERTIES);
                }
                validateSchemaNode(property.getValue(),
                        path + "/properties/" + property.getKey(), depth + 1, counter);
            }
            for (String requiredName : requiredNames) {
                if (!properties.has(requiredName)) {
                    throw new IllegalArgumentException(
                            path + ": required references unknown property " + requiredName);
                }
            }
            if (!schema.path("additionalProperties").isBoolean()
                    || schema.path("additionalProperties").asBoolean()) {
                throw new IllegalArgumentException(
                        path + ": strict object schema requires additionalProperties=false");
            }
            if (requiredNames.size() != properties.size()) {
                throw new IllegalArgumentException(
                        path + ": strict object schema requires every property");
            }
        } else if ("array".equals(type.asText())) {
            if (!schema.has("items")) {
                throw new IllegalArgumentException(path + ": array schema requires items");
            }
            validateSchemaNode(schema.get("items"), path + "/items", depth + 1, counter);
        } else if (schema.has("properties") || schema.has("required")
                || schema.has("additionalProperties") || schema.has("items")) {
            throw new IllegalArgumentException(
                    path + ": scalar schema cannot define object or array keywords");
        }
    }

    private static void validateOutputNode(
            JsonNode value, JsonNode schema, String path, List<String> violations) {
        if (violations.size() >= MAX_OUTPUT_VIOLATIONS) {
            return;
        }
        String type = schema.path("type").asText();
        boolean matches = switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> false;
        };
        if (!matches) {
            violations.add(path + ": expected " + type + ", got " + value.getNodeType());
            return;
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null) {
            boolean allowed = false;
            for (JsonNode candidate : enumNode) {
                if (candidate.equals(value)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                violations.add(path + ": value is not in enum");
            }
        }
        if (value.isObject()) {
            JsonNode properties = schema.get("properties");
            Set<String> required = new LinkedHashSet<>();
            schema.get("required").forEach(node -> required.add(node.asText()));
            required.stream()
                    .filter(name -> !value.has(name))
                    .forEach(name -> violations.add(
                            path + "/" + name + ": required property is missing"));
            Iterator<Map.Entry<String, JsonNode>> fields =
                    value.properties().iterator();
            while (fields.hasNext() && violations.size() < MAX_OUTPUT_VIOLATIONS) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode propertySchema = properties.get(field.getKey());
                if (propertySchema == null) {
                    violations.add(path + "/" + field.getKey()
                            + ": additional property is not allowed");
                } else {
                    validateOutputNode(field.getValue(), propertySchema,
                            path + "/" + field.getKey(), violations);
                }
            }
        } else if (value.isArray()) {
            int index = 0;
            for (JsonNode item : value) {
                validateOutputNode(item, schema.get("items"),
                        path + "/" + index++, violations);
            }
        }
    }

    record Evaluation(
            List<Artifact> artifacts,
            ToolExecutionSummary toolExecutionSummary,
            ContractValidation contractValidation,
            JsonNode structuredOutput
    ) {}

    private static final class MutableToolStats {
        private int attemptCount;
        private int successfulCount;
        private int failedCount;
        private String latestError;
    }

    private static final class SchemaCounter {
        private int properties;
    }
}
