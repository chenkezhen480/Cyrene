package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    ToolRegistry registry;
    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    private Tool createTool(String name, String description) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ToolSpec spec = new ToolSpec(name, description, params);
        return new Tool() {
            @Override
            public ToolSpec spec() { return spec; }

            @Override
            public String execute(JsonNode arguments) { return "ok"; }
        };
    }

    @Test
    void register_thenGet_returnsTool() {
        Tool tool = createTool("search", "Search tool");
        registry.register(tool);

        assertThat(registry.get("search")).isSameAs(tool);
    }

    @Test
    void get_notRegistered_returnsNull() {
        assertThat(registry.get("nonexistent")).isNull();
    }

    @Test
    void contains_registered_returnsTrue() {
        registry.register(createTool("search", "desc"));
        assertThat(registry.contains("search")).isTrue();
    }

    @Test
    void contains_notRegistered_returnsFalse() {
        assertThat(registry.contains("search")).isFalse();
    }

    @Test
    void size_empty_returnsZero() {
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void size_afterRegistrations_returnsCorrectCount() {
        registry.register(createTool("a", "desc a"));
        registry.register(createTool("b", "desc b"));
        registry.register(createTool("c", "desc c"));

        assertThat(registry.size()).isEqualTo(3);
    }

    @Test
    void register_overwritesSameName() {
        Tool first = createTool("search", "first version");
        Tool second = createTool("search", "second version");
        registry.register(first);
        registry.register(second);

        assertThat(registry.get("search")).isSameAs(second);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void getAll_returnsAllSpecs() {
        registry.register(createTool("alpha", "Alpha tool"));
        registry.register(createTool("beta", "Beta tool"));

        List<ToolSpec> specs = registry.getAll();
        assertThat(specs).hasSize(2);
        assertThat(specs).extracting(ToolSpec::name).containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    void getAll_empty_returnsEmptyList() {
        assertThat(registry.getAll()).isEmpty();
    }
}
