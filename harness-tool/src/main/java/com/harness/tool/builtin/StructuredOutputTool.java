package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.FinalOutputContract;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.core.structured.StructuredOutputValueValidator;
import com.harness.tool.TypedOutputTool;

import java.util.Objects;

/**
 * Emits a structured JSON block, or submits the terminal value for a strict
 * structured-output contract.
 */
public final class StructuredOutputTool implements TypedOutputTool {

    public static final String TOOL_NAME = "structured_output";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final StructuredOutputValueValidator VALUE_VALIDATOR =
            new StructuredOutputValueValidator(OBJECT_MAPPER);
    private static final JsonNode CHAT_PARAMETERS = createChatParameters();

    public enum Mode {
        CHAT_BLOCK,
        TERMINAL
    }

    private final Mode mode;
    private final ToolSpec specification;
    private final JsonNode outputSchema;
    private final StructuredOutputValueValidator valueValidator;

    private StructuredOutputTool(
            Mode mode,
            ToolSpec specification,
            JsonNode outputSchema,
            StructuredOutputValueValidator valueValidator
    ) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.specification = Objects.requireNonNull(specification, "specification");
        this.outputSchema = outputSchema != null ? outputSchema.deepCopy() : null;
        this.valueValidator = valueValidator;
    }

    /** Stable, provider-neutral tool used by ordinary Chat requests. */
    public static StructuredOutputTool chatBlock() {
        return new StructuredOutputTool(
                Mode.CHAT_BLOCK,
                new ToolSpec(
                        TOOL_NAME,
                        "Emit a user-visible structured JSON object block. Use this when "
                                + "machine-readable data is useful inside a normal chat reply. "
                                + "The arguments are delivered directly; do not repeat the JSON in prose.",
                        CHAT_PARAMETERS),
                null,
                null);
    }

    /** Request-scoped terminal tool whose argument schema is the output contract. */
    public static StructuredOutputTool terminal(
            FinalOutputContract.JsonSchema contract
    ) {
        Objects.requireNonNull(contract, "contract");
        return new StructuredOutputTool(
                Mode.TERMINAL,
                new ToolSpec(
                        TOOL_NAME,
                        "Submit the final structured response for schema '"
                                + contract.name()
                                + "'. Call this only after all information-gathering tools are "
                                + "finished. It must be the only tool call in its round.",
                        contract.schema()),
                contract.schema(),
                VALUE_VALIDATOR);
    }

    @Override
    public ToolSpec spec() {
        return specification;
    }

    @Override
    public ToolOutput executeOutput(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new ToolExecutionException(
                    TOOL_NAME, "Structured output arguments must be a JSON object");
        }
        if (mode == Mode.TERMINAL) {
            try {
                valueValidator.validate(arguments, outputSchema);
            } catch (RuntimeException e) {
                throw new ToolExecutionException(
                        TOOL_NAME, "Output does not match the requested schema: " + e.getMessage(), e);
            }
        }
        return ToolOutput.json(arguments);
    }

    private static JsonNode createChatParameters() {
        var schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", OBJECT_MAPPER.createObjectNode());
        schema.set("required", OBJECT_MAPPER.createArrayNode());
        schema.put("additionalProperties", true);
        return schema;
    }
}
