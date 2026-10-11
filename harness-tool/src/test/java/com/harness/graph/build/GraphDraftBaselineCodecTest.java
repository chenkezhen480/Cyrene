package com.harness.graph.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.graph.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GraphDraftBaselineCodecTest {
    @Test void baselineIgnoresLabelSetOrderButPreservesOrderedPropertyArrays() {
        var mapper = new ObjectMapper();
        var first = new GraphNode("node", new LinkedHashSet<>(List.of("B", "A")),
                Map.of("labels", List.of("first", "second")));
        var reorderedLabels = new GraphNode("node", new LinkedHashSet<>(List.of("A", "B")), first.properties());
        var reorderedProperty = new GraphNode("node", first.labels(), Map.of("labels", List.of("second", "first")));
        assertThat(GraphContentHash.of(first, mapper)).isEqualTo(GraphContentHash.of(reorderedLabels, mapper));
        assertThat(GraphContentHash.of(first, mapper)).isNotEqualTo(GraphContentHash.of(reorderedProperty, mapper));
    }
    @Test void sagaPayloadPreservesAbsentEntityBaselineAndDeterministicSetHash() {
        Map<String, GraphNode> nodes = new HashMap<>(); nodes.put("new", null);
        GraphMutationBaseline baseline = new GraphMutationBaseline("schema-hash", nodes, Map.of(), Map.of("deleted", Set.of("r1", "r2")));
        GraphChangeSet change = new GraphChangeSet("request", "graph", "schema", List.of(new GraphNode("new", Set.of("B", "A"), Map.of("name", "new"))), List.of(), Set.of(), Set.of(), baseline);
        GraphChangeSetCodec codec = new GraphChangeSetCodec();
        var encoded = codec.encode(change);
        var restored = codec.decode(encoded.canonicalPayload());
        assertThat(restored.baseline().nodes()).containsEntry("new", null);
        assertThat(codec.encode(restored).payloadHash()).isEqualTo(encoded.payloadHash());
        ObjectMapper mapper = new ObjectMapper();
        assertThat(GraphContentHash.of(List.of(1, 2), mapper)).isNotEqualTo(GraphContentHash.of(List.of(2, 1), mapper));
    }
}
