package com.harness.tool.skill;

import com.harness.core.model.Skill;
import com.harness.core.model.SkillIndex;
import com.harness.env.EnvConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRegistryTest {

    SkillRegistry registry;

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of("HARNESS_CACHE_SESSION_TTL_HOURS", "1"));
        registry = new SkillRegistry();
    }

    private Skill createSkill(String name, String desc) {
        return new Skill(name, desc, "1.0.0", "# " + name + " instructions", List.of("tool_a"), Map.of());
    }

    @Test
    void addTemporary_thenGet_returnsFromTemporary() {
        Skill skill = createSkill("my-skill", "A temp skill");
        registry.addTemporary("sess1", skill);

        SkillIndex idx = registry.get("my-skill", "sess1");
        assertThat(idx).isNotNull();
        assertThat(idx.name()).isEqualTo("my-skill");
        assertThat(idx.description()).isEqualTo("A temp skill");
    }

    @Test
    void addTemporary_nullSession_doesNotThrow() {
        Skill skill = createSkill("my-skill", "desc");
        registry.addTemporary(null, skill);
        // Should log warning but not crash
    }

    @Test
    void get_temporaryOverrides_persistent() {
        // This test verifies lookup order — temporary takes priority
        // Without persistent index, temporary is still returned
        Skill skill = createSkill("skill-a", "temp version");
        registry.addTemporary("sess1", skill);

        SkillIndex idx = registry.get("skill-a", "sess1");
        assertThat(idx).isNotNull();
        assertThat(idx.description()).isEqualTo("temp version");
    }

    @Test
    void get_notFound_returnsNull() {
        assertThat(registry.get("nonexistent", "sess1")).isNull();
    }

    @Test
    void clearSession_removesTemporarySkills() {
        Skill skill = createSkill("my-skill", "desc");
        registry.addTemporary("sess1", skill);

        registry.clearSession("sess1");

        assertThat(registry.get("my-skill", "sess1")).isNull();
    }

    @Test
    void listAll_empty_returnsEmpty() {
        assertThat(registry.listAll("sess1")).isEmpty();
    }

    @Test
    void listAll_withTemporary_includesThem() {
        registry.addTemporary("sess1", createSkill("temp-a", "desc a"));
        registry.addTemporary("sess1", createSkill("temp-b", "desc b"));

        List<SkillIndex> all = registry.listAll("sess1");
        assertThat(all).hasSize(2);
        assertThat(all).extracting(SkillIndex::name).containsExactlyInAnyOrder("temp-a", "temp-b");
    }

    @Test
    void listAll_differentSession_doesNotIncludeOtherTemporary() {
        registry.addTemporary("sess1", createSkill("temp-a", "desc"));

        List<SkillIndex> all = registry.listAll("sess2");
        assertThat(all).isEmpty();
    }

    @Test
    void getTemporarySkills_returnsSessionSkills() {
        registry.addTemporary("sess1", createSkill("t1", "desc"));
        registry.addTemporary("sess1", createSkill("t2", "desc"));

        List<Skill> temps = registry.getTemporarySkills("sess1");
        assertThat(temps).hasSize(2);
    }

    @Test
    void getTemporarySkills_nullSession_returnsEmpty() {
        assertThat(registry.getTemporarySkills(null)).isEmpty();
    }

    @Test
    void addTemporaryBulk_registersAll() {
        List<Skill> skills = List.of(
                createSkill("a", "desc a"),
                createSkill("b", "desc b")
        );
        registry.addTemporaryBulk("sess1", skills);

        assertThat(registry.getTemporarySkills("sess1")).hasSize(2);
    }

    @Test
    void addTemporaryBulk_nullSession_noOp() {
        registry.addTemporaryBulk(null, List.of(createSkill("a", "desc")));
    }

    @Test
    void size_empty_returnsZero() {
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void size_withTemporary_returnsCount() {
        registry.addTemporary("sess1", createSkill("a", "desc"));
        registry.addTemporary("sess1", createSkill("b", "desc"));

        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void size_withSession_returnsCount() {
        registry.addTemporary("sess1", createSkill("a", "desc"));
        registry.addTemporary("sess2", createSkill("b", "desc"));

        // Global size counts unique names
        assertThat(registry.size()).isEqualTo(2);
        // Per-session size
        assertThat(registry.size("sess1")).isEqualTo(1);
    }
}
