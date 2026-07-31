package com.harness.graph.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphModelTest {

    @Test
    void defensivelyCopiesNodeCollections() {
        GraphNode node = new GraphNode(
                "node-1",
                Set.of("Project"),
                Map.of("name", "Cyrene")
        );

        assertThat(node.labels()).containsExactly("Project");
        assertThat(node.properties()).containsEntry("name", "Cyrene");
        assertThatThrownBy(() -> node.properties().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nodeIdSupportsUnicodeBusinessIdentifiers() {
        GraphNode node = new GraphNode(
                "学生-小明",
                Set.of("Student"),
                Map.of("name", "小明")
        );

        assertThat(node.nodeId()).isEqualTo("学生-小明");
    }

    @Test
    void pathRequiresConsistentNodeAndRelationCounts() {
        GraphNode source = new GraphNode("source", Set.of("Person"), Map.of());
        GraphNode target = new GraphNode("target", Set.of("Project"), Map.of());
        GraphRelation relation = new GraphRelation(
                "relation", "source", "target", "WORKS_ON", Map.of());

        GraphPath path = new GraphPath(List.of(source, target), List.of(relation), 1);

        assertThat(path.depth()).isEqualTo(1);
        assertThatThrownBy(() -> new GraphPath(List.of(source), List.of(relation), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mutationMustContainData() {
        assertThatThrownBy(() -> new GraphMutationBatch(
                "request-1", "graph-1", "schema-v1", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain");
    }

    @Test
    void graphSpaceSummaryRejectsNegativeCounts() {
        assertThatThrownBy(() -> new GraphSpaceSummary("graph-1", "schema-v1", -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeCount");
    }
}
