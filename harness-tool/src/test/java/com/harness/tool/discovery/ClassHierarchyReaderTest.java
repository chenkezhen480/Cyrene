package com.harness.tool.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test ClassHierarchyReader with cross-module parent class lookup.
 */
class ClassHierarchyReaderTest {

    // Test with the zhiduyuan module as sourceRoot — parent classes are in ruoyi-common
    private static final Path MODULE_ROOT = Path.of(
            "C:/projects/ZhiDuYuan/zhiduyuan/RuoYi-Vue-Plus-5.X/ruoyi-modules/zhiduyuan");

    @Test
    void testFindClassInModule() {
        ClassHierarchyReader reader = new ClassHierarchyReader(MODULE_ROOT);

        // AiSessionBo is in the zhiduyuan module itself
        List<ClassHierarchyReader.ClassInfo> hierarchy = reader.readHierarchy("AiSessionBo");

        System.out.println("=== AiSessionBo hierarchy ===");
        for (ClassHierarchyReader.ClassInfo info : hierarchy) {
            System.out.printf("  depth=%d: %s (%s) - %d fields, parent=%s%n",
                    info.depth, info.name, info.filePath, info.fields.size(),
                    info.parentClass != null ? info.parentClass : "none");
        }

        assertFalse(hierarchy.isEmpty(), "Should find AiSessionBo");
    }

    @Test
    void testCrossModuleParentLookup() {
        ClassHierarchyReader reader = new ClassHierarchyReader(MODULE_ROOT);

        // AiSessionBo extends BaseEntity which is in ruoyi-common (different module)
        List<ClassHierarchyReader.ClassInfo> hierarchy = reader.readHierarchy("AiSessionBo");

        System.out.println("\n=== AiSessionBo full hierarchy (cross-module) ===");
        for (ClassHierarchyReader.ClassInfo info : hierarchy) {
            System.out.printf("  depth=%d: %s (%s) - %d fields, parent=%s%n",
                    info.depth, info.name, info.filePath, info.fields.size(),
                    info.parentClass != null ? info.parentClass : "none");
            for (ClassHierarchyReader.FieldInfo field : info.fields) {
                System.out.printf("    - %s: %s → %s%n", field.name, field.type, field.jsonType);
            }
        }

        // Should find both AiSessionBo (depth=0) and BaseEntity (depth=1)
        assertTrue(hierarchy.size() >= 2,
                "Should find AiSessionBo + parent BaseEntity, got " + hierarchy.size());

        assertEquals("AiSessionBo", hierarchy.get(0).name);
        assertEquals(0, hierarchy.get(0).depth);

        assertEquals("BaseEntity", hierarchy.get(1).name);
        assertEquals(1, hierarchy.get(1).depth);
    }

    @Test
    void testMergedFields() {
        ClassHierarchyReader reader = new ClassHierarchyReader(MODULE_ROOT);

        List<ClassHierarchyReader.FieldInfo> merged = reader.readMergedFields("AiSessionBo");

        System.out.println("\n=== AiSessionBo merged fields ===");
        for (ClassHierarchyReader.FieldInfo field : merged) {
            System.out.printf("  %s: %s → %s (from %s)%n",
                    field.name, field.type, field.jsonType, field.sourceClass);
        }

        assertFalse(merged.isEmpty(), "Should have merged fields");

        // Should have fields from both AiSessionBo and BaseEntity
        boolean hasBaseEntityField = merged.stream()
                .anyMatch(f -> f.sourceClass.equals("BaseEntity"));
        assertTrue(hasBaseEntityField, "Should include fields from BaseEntity");
    }

    @Test
    void testJsonSchemaOutput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClassHierarchyReader reader = new ClassHierarchyReader(MODULE_ROOT);
        ReadClassHierarchyTool tool = new ReadClassHierarchyTool(MODULE_ROOT);

        String result = tool.execute(mapper.readTree("{\"className\": \"AiSessionBo\"}"));

        System.out.println("\n=== ReadClassHierarchyTool output ===");
        System.out.println(result);

        assertNotNull(result);
        assertFalse(result.contains("ERROR"), "Should not return error");
        assertTrue(result.contains("JSON Schema"), "Should contain JSON Schema");
        assertTrue(result.contains("BaseEntity"), "Should mention BaseEntity parent");
    }
}
