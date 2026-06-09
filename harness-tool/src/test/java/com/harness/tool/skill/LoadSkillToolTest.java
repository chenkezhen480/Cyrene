package com.harness.tool.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.Skill;
import com.harness.core.model.SkillIndex;
import com.harness.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoadSkillToolTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock SkillRegistry skillRegistry;
    @Mock ToolRegistry toolRegistry;

    LoadSkillTool tool;

    @BeforeEach
    void setUp() {
        tool = new LoadSkillTool(skillRegistry, toolRegistry);
        LoadSkillTool.setCurrentSession("test-session");
    }

    @AfterEach
    void tearDown() {
        LoadSkillTool.clearCurrentSession();
    }

    private ObjectNode args(String name, String query) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", name);
        if (query != null) node.put("query", query);
        return node;
    }

    private Skill sampleSkill() {
        return new Skill(
                "code-review",
                "Code review skill",
                "1.0.0",
                "# Code Review\n\n## Step 1: Read the code\nRead carefully.\n\n## Step 2: Check security\nLook for vulnerabilities.\n\n## Step 3: Check performance\nIdentify bottlenecks.",
                List.of("web_search"),
                Map.of("focus", "security")
        );
    }

    @Test
    void spec_returnsCorrectToolSpec() {
        var spec = tool.spec();
        assertThat(spec.name()).isEqualTo("load_skill");
        assertThat(spec.description()).isNotEmpty();
    }

    @Test
    void execute_missingName_returnsError() {
        ObjectNode emptyArgs = MAPPER.createObjectNode();
        String result = tool.execute(emptyArgs);
        assertThat(result).contains("Error");
        assertThat(result).contains("name");
    }

    @Test
    void execute_blankName_returnsError() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("name", "  ");
        String result = tool.execute(args);
        assertThat(result).contains("Error");
    }

    @Test
    void execute_skillNotFound_returnsError() {
        when(skillRegistry.get(eq("nonexistent"), eq("test-session"))).thenReturn(null);
        when(skillRegistry.listAll(eq("test-session"))).thenReturn(List.of());

        String result = tool.execute(args("nonexistent", null));
        assertThat(result).contains("Error");
        assertThat(result).contains("nonexistent");
        assertThat(result).contains("not found");
    }

    @Test
    void execute_fullLoad_returnsContent() {
        Skill skill = sampleSkill();
        SkillIndex index = new SkillIndex("code-review", "Code review skill", null);

        when(skillRegistry.get(eq("code-review"), eq("test-session"))).thenReturn(index);
        when(skillRegistry.getFull(eq("code-review"), eq("test-session"))).thenReturn(skill);
        when(toolRegistry.contains("web_search")).thenReturn(true);

        String result = tool.execute(args("code-review", null));

        assertThat(result).contains("[Skill: code-review]");
        assertThat(result).contains("Code review");
        assertThat(result).contains("[Version: 1.0.0]");
        assertThat(result).contains("Step 1: Read the code");
        assertThat(result).contains("[Bound Tools: web_search]");
        assertThat(result).contains("[Parameters: focus=security]");
    }

    @Test
    void execute_fullLoad_missingTool_warns() {
        Skill skill = sampleSkill();
        SkillIndex index = new SkillIndex("code-review", "Code review skill", null);

        when(skillRegistry.get(eq("code-review"), eq("test-session"))).thenReturn(index);
        when(skillRegistry.getFull(eq("code-review"), eq("test-session"))).thenReturn(skill);
        when(toolRegistry.contains("web_search")).thenReturn(false);

        String result = tool.execute(args("code-review", null));

        assertThat(result).contains("WARNING");
        assertThat(result).contains("web_search");
        assertThat(result).contains("not registered");
    }

    @Test
    void execute_searchMode_returnsMatches() {
        Skill skill = sampleSkill();
        SkillIndex index = new SkillIndex("code-review", "Code review skill", null);

        when(skillRegistry.get(eq("code-review"), eq("test-session"))).thenReturn(index);
        when(skillRegistry.getFull(eq("code-review"), eq("test-session"))).thenReturn(skill);

        String result = tool.execute(args("code-review", "security"));

        assertThat(result).contains("[Skill Search: code-review]");
        assertThat(result).contains("[Query: security]");
        assertThat(result).contains("security");
    }

    @Test
    void execute_searchMode_noMatches() {
        Skill skill = sampleSkill();
        SkillIndex index = new SkillIndex("code-review", "Code review skill", null);

        when(skillRegistry.get(eq("code-review"), eq("test-session"))).thenReturn(index);
        when(skillRegistry.getFull(eq("code-review"), eq("test-session"))).thenReturn(skill);

        String result = tool.execute(args("code-review", "xyznonexistent"));

        assertThat(result).contains("No matches found");
    }

    @Test
    void execute_searchMode_invalidRegex_returnsError() {
        Skill skill = sampleSkill();
        SkillIndex index = new SkillIndex("code-review", "Code review skill", null);

        when(skillRegistry.get(eq("code-review"), eq("test-session"))).thenReturn(index);
        when(skillRegistry.getFull(eq("code-review"), eq("test-session"))).thenReturn(skill);

        String result = tool.execute(args("code-review", "[invalid"));

        assertThat(result).contains("Error");
        assertThat(result).contains("invalid regex");
    }

    @Test
    void execute_fullLoad_getFullReturnsNull_returnsError() {
        SkillIndex index = new SkillIndex("code-review", "desc", null);

        when(skillRegistry.get(eq("code-review"), eq("test-session"))).thenReturn(index);
        when(skillRegistry.getFull(eq("code-review"), eq("test-session"))).thenReturn(null);

        String result = tool.execute(args("code-review", null));
        assertThat(result).contains("Error");
        assertThat(result).contains("failed to load");
    }
}
