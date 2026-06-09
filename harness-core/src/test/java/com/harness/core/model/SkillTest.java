package com.harness.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillTest {

    @Test
    void constructor_nullTools_defaultsToEmptyList() {
        var skill = new Skill("name", "desc", "1.0.0", "prompt", null, Map.of());
        assertThat(skill.tools()).isEmpty();
    }

    @Test
    void constructor_nullParameters_defaultsToEmptyMap() {
        var skill = new Skill("name", "desc", "1.0.0", "prompt", List.of(), null);
        assertThat(skill.parameters()).isEmpty();
    }

    @Test
    void constructor_nullVersion_defaultsTo1_0_0() {
        var skill = new Skill("name", "desc", null, "prompt", List.of(), Map.of());
        assertThat(skill.version()).isEqualTo("1.0.0");
    }

    @Test
    void constructor_blankVersion_defaultsTo1_0_0() {
        var skill = new Skill("name", "desc", "  ", "prompt", List.of(), Map.of());
        assertThat(skill.version()).isEqualTo("1.0.0");
    }

    @Test
    void constructor_validFields_preserved() {
        var tools = List.of("web_search", "code_execution");
        var params = Map.<String, Object>of("style", "restful");
        var skill = new Skill("test", "description", "2.0.0", "# Prompt", tools, params);

        assertThat(skill.name()).isEqualTo("test");
        assertThat(skill.description()).isEqualTo("description");
        assertThat(skill.version()).isEqualTo("2.0.0");
        assertThat(skill.systemPrompt()).isEqualTo("# Prompt");
        assertThat(skill.tools()).containsExactly("web_search", "code_execution");
        assertThat(skill.parameters()).containsEntry("style", "restful");
    }
}
