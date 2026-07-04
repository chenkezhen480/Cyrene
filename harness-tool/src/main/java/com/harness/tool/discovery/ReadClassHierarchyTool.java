package com.harness.tool.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Tool: read a class and its parent hierarchy (up to 2 levels),
 * return merged fields with JSON Schema type mapping.
 *
 * <p>Supports: Java, C#, C++, Python, JS/TS, PHP, Rust, C, Go.
 */
public class ReadClassHierarchyTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReadClassHierarchyTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path sourceRoot;
    private final ClassHierarchyReader reader;

    public ReadClassHierarchyTool(Path sourceRoot) {
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.reader = new ClassHierarchyReader(this.sourceRoot);
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "read_class_hierarchy",
                "Read a class/struct and its parent classes (up to 2 levels). " +
                "Returns merged fields with JSON Schema type mapping. " +
                "Supports Java, C#, C++, Python, JS/TS, PHP, Rust, C, Go. " +
                "Input: simple class name (e.g. 'UserDTO', 'CourseVO').",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<ObjectNode>set("className",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Simple class name, e.g. 'UserDTO', 'CourseVO', 'QueryForm'")))
                        .<ObjectNode>set("required",
                                mapper.createArrayNode().add("className"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String className = arguments.has("className") ? arguments.get("className").asText().trim() : null;
        if (className == null || className.isEmpty()) {
            return "ERROR: 'className' is required";
        }

        log.debug("[ReadClassHierarchy] Reading hierarchy for: {} in {}", className, sourceRoot);

        try {
            List<ClassHierarchyReader.ClassInfo> hierarchy = reader.readHierarchy(className);

            if (hierarchy.isEmpty()) {
                return "Class not found: " + className;
            }

            // Build output
            StringBuilder sb = new StringBuilder();

            // Show hierarchy chain
            sb.append("Class hierarchy: ");
            for (int i = 0; i < hierarchy.size(); i++) {
                if (i > 0) sb.append(" → ");
                ClassHierarchyReader.ClassInfo info = hierarchy.get(i);
                sb.append(info.name);
                if (info.depth > 0) sb.append(" (depth=").append(info.depth).append(")");
            }
            sb.append("\n\n");

            // Show merged fields (child overrides parent)
            List<ClassHierarchyReader.FieldInfo> mergedFields = reader.readMergedFields(className);
            sb.append("Merged fields (").append(mergedFields.size()).append("):\n");
            for (ClassHierarchyReader.FieldInfo field : mergedFields) {
                sb.append("  ").append(field.name)
                  .append(": ").append(field.type)
                  .append(" → ").append(field.jsonType);
                if (!field.annotations.isEmpty()) {
                    sb.append(" [").append(String.join(", ", field.annotations)).append("]");
                }
                if (!field.sourceClass.equals(className)) {
                    sb.append(" (from ").append(field.sourceClass).append(")");
                }
                sb.append("\n");
            }

            // Generate JSON Schema from merged fields
            sb.append("\nJSON Schema:\n");
            sb.append(generateJsonSchema(className, mergedFields));

            return sb.toString();

        } catch (Exception e) {
            log.error("[ReadClassHierarchy] Error reading {}: {}", className, e.getMessage(), e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Generate a JSON Schema object from merged fields.
     */
    private String generateJsonSchema(String className, List<ClassHierarchyReader.FieldInfo> fields) {
        try {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");

            ObjectNode properties = mapper.createObjectNode();
            var required = mapper.createArrayNode();

            for (ClassHierarchyReader.FieldInfo field : fields) {
                ObjectNode prop = mapper.createObjectNode();
                prop.put("type", field.jsonType);
                // x-sourceType removed — already shown in field list above

                // Map annotations to JSON Schema constraints
                for (String anno : field.annotations) {
                    if (anno.equals("NotNull") || anno.equals("NotBlank") || anno.equals("NotEmpty")) {
                        required.add(field.name);
                    } else if (anno.startsWith("Size(") || anno.startsWith("Length(")) {
                        // Parse @Size(min=1, max=50)
                        parseSizeAnnotation(anno, prop);
                    } else if (anno.startsWith("Min(")) {
                        String val = extractAnnotationValue(anno);
                        if (val != null) prop.put("minimum", tryParseLong(val));
                    } else if (anno.startsWith("Max(")) {
                        String val = extractAnnotationValue(anno);
                        if (val != null) prop.put("maximum", tryParseLong(val));
                    } else if (anno.startsWith("Pattern(")) {
                        String val = extractAnnotationValue(anno);
                        if (val != null) prop.put("pattern", val);
                    } else if (anno.equals("Email")) {
                        prop.put("format", "email");
                    }
                }

                properties.set(field.name, prop);
            }

            schema.set("properties", properties);
            if (!required.isEmpty()) {
                schema.set("required", required);
            }

            return mapper.writeValueAsString(schema);

        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    private void parseSizeAnnotation(String anno, ObjectNode prop) {
        String inner = anno.substring(anno.indexOf('(') + 1, anno.lastIndexOf(')'));
        for (String part : inner.split(",")) {
            String[] kv = part.trim().split("=");
            if (kv.length == 2) {
                String key = kv[0].trim();
                String val = kv[1].trim();
                if (key.equals("min")) prop.put("minLength", tryParseLong(val));
                if (key.equals("max")) prop.put("maxLength", tryParseLong(val));
            }
        }
    }

    private String extractAnnotationValue(String anno) {
        int start = anno.indexOf('(');
        int end = anno.lastIndexOf(')');
        if (start > 0 && end > start) {
            return anno.substring(start + 1, end).trim();
        }
        return null;
    }

    private long tryParseLong(String val) {
        try { return Long.parseLong(val); } catch (NumberFormatException e) { return 0; }
    }
}
