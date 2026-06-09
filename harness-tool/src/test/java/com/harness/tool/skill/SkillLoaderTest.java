package com.harness.tool.skill;

import com.harness.core.model.Skill;
import com.harness.core.model.SkillIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillLoaderTest {

    private static final String VALID_SKILL = """
            ---
            name: test-skill
            description: A test skill for unit testing
            version: "2.0.0"
            tools:
              - web_search
              - code_execution
            parameters:
              style: restful
            ---

            # Test Skill

            ## Steps

            1. Do the thing
            2. Verify the thing
            """;

    private static final String NO_FRONTMATTER = """
            # Just a regular markdown file

            No YAML frontmatter here.
            """;

    private static final String INCOMPLETE_SKILL = """
            ---
            name: incomplete-skill
            ---

            Missing description field.
            """;

    // ---- loadFromContent ----

    @Test
    void loadFromContent_validSkill_parsesAllFields() {
        Skill skill = SkillLoader.loadFromContent(VALID_SKILL);

        assertThat(skill).isNotNull();
        assertThat(skill.name()).isEqualTo("test-skill");
        assertThat(skill.description()).isEqualTo("A test skill for unit testing");
        assertThat(skill.version()).isEqualTo("2.0.0");
        assertThat(skill.tools()).containsExactly("web_search", "code_execution");
        assertThat(skill.parameters()).containsEntry("style", "restful");
        assertThat(skill.systemPrompt()).contains("# Test Skill");
    }

    @Test
    void loadFromContent_missingFrontmatter_returnsNull() {
        assertThat(SkillLoader.loadFromContent(NO_FRONTMATTER)).isNull();
    }

    @Test
    void loadFromContent_emptyString_returnsNull() {
        assertThat(SkillLoader.loadFromContent("")).isNull();
    }

    @Test
    void loadFromContent_nullInput_returnsNull() {
        assertThat(SkillLoader.loadFromContent(null)).isNull();
    }

    @Test
    void loadFromContent_missingDescription_returnsNull() {
        assertThat(SkillLoader.loadFromContent(INCOMPLETE_SKILL)).isNull();
    }

    // ---- isSkillFile ----

    @Test
    void isSkillFile_validSkill_returnsTrue() {
        assertThat(SkillLoader.isSkillFile(VALID_SKILL)).isTrue();
    }

    @Test
    void isSkillFile_noFrontmatter_returnsFalse() {
        assertThat(SkillLoader.isSkillFile(NO_FRONTMATTER)).isFalse();
    }

    @Test
    void isSkillFile_missingDescription_returnsFalse() {
        assertThat(SkillLoader.isSkillFile(INCOMPLETE_SKILL)).isFalse();
    }

    @Test
    void isSkillFile_nullInput_returnsFalse() {
        assertThat(SkillLoader.isSkillFile(null)).isFalse();
    }

    @Test
    void isSkillFile_blankString_returnsFalse() {
        assertThat(SkillLoader.isSkillFile("   ")).isFalse();
    }

    // ---- scanIndex ----

    @Test
    void scanIndex_validFiles_returnsIndexes(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("skill1.md"), VALID_SKILL);
        Files.writeString(tempDir.resolve("skill2.md"), """
                ---
                name: another-skill
                description: Another skill
                ---
                # Another
                """);

        List<SkillIndex> indexes = SkillLoader.scanIndex(tempDir);

        assertThat(indexes).hasSize(2);
        assertThat(indexes).extracting(SkillIndex::name)
                .containsExactlyInAnyOrder("test-skill", "another-skill");
    }

    @Test
    void scanIndex_mixedValidAndInvalid_onlyReturnsValid(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("valid.md"), VALID_SKILL);
        Files.writeString(tempDir.resolve("invalid.md"), NO_FRONTMATTER);
        Files.writeString(tempDir.resolve("incomplete.md"), INCOMPLETE_SKILL);

        List<SkillIndex> indexes = SkillLoader.scanIndex(tempDir);

        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).name()).isEqualTo("test-skill");
    }

    @Test
    void scanIndex_nonExistentDir_returnsEmptyList() {
        List<SkillIndex> indexes = SkillLoader.scanIndex(Path.of("/nonexistent/path"));
        assertThat(indexes).isEmpty();
    }

    @Test
    void scanIndex_emptyDir_returnsEmptyList(@TempDir Path tempDir) {
        List<SkillIndex> indexes = SkillLoader.scanIndex(tempDir);
        assertThat(indexes).isEmpty();
    }

    @Test
    void scanIndex_ignoresNonMdFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("readme.txt"), "not a skill");
        Files.writeString(tempDir.resolve("valid.md"), VALID_SKILL);

        List<SkillIndex> indexes = SkillLoader.scanIndex(tempDir);

        assertThat(indexes).hasSize(1);
    }

    // ---- loadFull ----

    @Test
    void loadFull_readsFromDisk(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("skill.md");
        Files.writeString(file, VALID_SKILL);

        SkillIndex index = new SkillIndex("test-skill", "A test skill", file.toString());
        Skill skill = SkillLoader.loadFull(index);

        assertThat(skill).isNotNull();
        assertThat(skill.name()).isEqualTo("test-skill");
    }

    @Test
    void loadFull_nullFilePath_returnsNull() {
        SkillIndex index = new SkillIndex("test", "desc", null);
        assertThat(SkillLoader.loadFull(index)).isNull();
    }
}
