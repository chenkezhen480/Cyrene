package com.harness.graph.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationBatch;
import com.harness.graph.model.GraphMutationResult;
import com.harness.graph.model.GraphNode;
import com.harness.graph.store.KnowledgeGraphStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphBuildServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsCanonicalJsonAndCommitsThroughGraphStore() throws Exception {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        when(graphStore.upsertBatch(any())).thenReturn(
                new GraphMutationResult("request-1", true, 1, 0));
        GraphBuildService service = new GraphBuildService(
                graphStore, GraphDataConverterRegistry.withDefaults(objectMapper));
        GraphBuildRequest request = new GraphBuildRequest(
                "request-1",
                "graph-1",
                "student-capability-v1",
                GraphBuildSourceType.STRUCTURED,
                CanonicalJsonGraphDataConverter.CONVERTER_ID,
                objectMapper.readTree("""
                        {
                          "nodes": [
                            {
                              "nodeId": "student-1",
                              "labels": ["Student"],
                              "properties": {"name": "小明"}
                            }
                          ],
                          "relations": []
                        }
                        """)
        );

        GraphBuildResult result = service.build(request);

        assertThat(result.committed()).isTrue();
        assertThat(result.nodeCount()).isEqualTo(1);
        ArgumentCaptor<GraphMutationBatch> mutationCaptor =
                ArgumentCaptor.forClass(GraphMutationBatch.class);
        verify(graphStore).upsertBatch(mutationCaptor.capture());
        GraphMutationBatch mutation = mutationCaptor.getValue();
        assertThat(mutation.graphId()).isEqualTo("graph-1");
        assertThat(mutation.schemaId()).isEqualTo("student-capability-v1");
        assertThat(mutation.nodes()).extracting(GraphNode::nodeId).containsExactly("student-1");
    }

    @Test
    void appliesJsonDeletionsAndUpsertsAsOneChangeSet() throws Exception {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        when(graphStore.applyChanges(any())).thenReturn(
                new GraphMutationResult("request-delete", true, 0, 0));
        GraphBuildService service = new GraphBuildService(
                graphStore, GraphDataConverterRegistry.withDefaults(objectMapper));
        GraphBuildRequest request = new GraphBuildRequest(
                "request-delete",
                "graph-1",
                "student-capability-v1",
                GraphBuildSourceType.STRUCTURED,
                CanonicalJsonGraphDataConverter.CONVERTER_ID,
                objectMapper.readTree("""
                        {"nodes": [], "relations": []}
                        """),
                Set.of("student-1"),
                Set.of("relation-1")
        );

        GraphBuildResult result = service.build(request);

        assertThat(result.committed()).isTrue();
        ArgumentCaptor<GraphChangeSet> changeCaptor =
                ArgumentCaptor.forClass(GraphChangeSet.class);
        verify(graphStore).applyChanges(changeCaptor.capture());
        assertThat(changeCaptor.getValue().deleteNodeIds()).containsExactly("student-1");
        assertThat(changeCaptor.getValue().deleteRelationIds()).containsExactly("relation-1");
        verify(graphStore, never()).upsertBatch(any());
    }

    @Test
    void rejectsStructuredBuildWithoutAnyChanges() throws Exception {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphBuildService service = new GraphBuildService(
                graphStore, GraphDataConverterRegistry.withDefaults(objectMapper));

        assertThatThrownBy(() -> service.build(new GraphBuildRequest(
                "request-empty",
                "graph-1",
                "student-capability-v1",
                GraphBuildSourceType.STRUCTURED,
                CanonicalJsonGraphDataConverter.CONVERTER_ID,
                objectMapper.readTree("""
                        {"nodes": [], "relations": []}
                        """)
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one change");
    }

    @Test
    void doesNotTreatInvalidStructuredInputAsNaturalLanguage() {
        assertThatThrownBy(() -> new GraphBuildRequest(
                "request-1",
                "graph-1",
                "schema-1",
                GraphBuildSourceType.STRUCTURED,
                CanonicalJsonGraphDataConverter.CONVERTER_ID,
                objectMapper.getNodeFactory().textNode("not-json")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object or array");
    }

    @Test
    void requiresExplicitNaturalLanguageConverter() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphBuildService service = new GraphBuildService(
                graphStore, GraphDataConverterRegistry.withDefaults(objectMapper));
        GraphBuildRequest request = new GraphBuildRequest(
                "request-2",
                "graph-1",
                "schema-1",
                GraphBuildSourceType.NATURAL_LANGUAGE,
                "llm-extraction",
                objectMapper.getNodeFactory().textNode("小明正在练习主动表达需求")
        );

        assertThatThrownBy(() -> service.preview(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("natural-language/llm-extraction");
        verify(graphStore, never()).upsertBatch(any());
    }

    @Test
    void supportsProgrammaticallyInjectedNaturalLanguageConverter() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphDataConverter naturalLanguageConverter = new GraphDataConverter() {
            @Override
            public String converterId() {
                return "test-natural-language";
            }

            @Override
            public GraphBuildSourceType sourceType() {
                return GraphBuildSourceType.NATURAL_LANGUAGE;
            }

            @Override
            public GraphMutationDraft convert(GraphBuildRequest request) {
                return new GraphMutationDraft(
                        List.of(new GraphNode(
                                "student-1",
                                Set.of("Student"),
                                Map.of("name", "小明"))),
                        List.of()
                );
            }
        };
        GraphDataConverterRegistry registry =
                new GraphDataConverterRegistry(List.of(naturalLanguageConverter));
        GraphBuildService service = new GraphBuildService(graphStore, registry);

        GraphBuildPreviewResult result = service.preview(new GraphBuildRequest(
                "request-3",
                "graph-1",
                "schema-1",
                GraphBuildSourceType.NATURAL_LANGUAGE,
                "test-natural-language",
                objectMapper.getNodeFactory().textNode("学生小明")
        ));

        assertThat(result.nodes()).extracting(GraphNode::nodeId).containsExactly("student-1");
        verify(graphStore, never()).upsertBatch(any());
    }

    @Test
    void rejectsDirectNaturalLanguageCommitEvenWhenConverterExists() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphDataConverter naturalLanguageConverter = mock(GraphDataConverter.class);
        when(naturalLanguageConverter.sourceType()).thenReturn(GraphBuildSourceType.NATURAL_LANGUAGE);
        when(naturalLanguageConverter.converterId()).thenReturn("test-natural-language");
        GraphBuildService service = new GraphBuildService(
                graphStore, new GraphDataConverterRegistry(List.of(naturalLanguageConverter)));
        GraphBuildRequest request = new GraphBuildRequest(
                "request-4",
                "graph-1",
                "schema-1",
                GraphBuildSourceType.NATURAL_LANGUAGE,
                "test-natural-language",
                objectMapper.getNodeFactory().textNode("student Xiaoming")
        );

        assertThatThrownBy(() -> service.build(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("previewed and confirmed");
        verify(graphStore, never()).upsertBatch(any());
    }
}
