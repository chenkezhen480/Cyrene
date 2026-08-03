package com.harness.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ai.model.ChatModelProvider;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.graph.build.GraphBuildRequest;
import com.harness.graph.build.GraphBuildSourceType;
import com.harness.graph.config.GraphProvider;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphNode;
import com.harness.graph.schema.GraphNodeTypeDefinition;
import com.harness.graph.schema.GraphPropertyDefinition;
import com.harness.graph.schema.GraphPropertyType;
import com.harness.graph.schema.GraphRelationTypeDefinition;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaMode;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.KnowledgeGraphStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmGraphDataConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsValidatedDraftAndReusesVisibleExistingNode() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse chatResponse = mock(ChatResponse.class);
        when(chatResponse.aiMessage()).thenReturn(AiMessage.from("""
                {
                  "nodes": [
                    {
                      "nodeId": "capability-language",
                      "labels": ["Capability"],
                      "properties": {"name": "Language expression"}
                    }
                  ],
                  "relations": [
                    {
                      "relationId": "student-1-has-language",
                      "sourceNodeId": "student-1",
                      "targetNodeId": "capability-language",
                      "relationType": "HAS_CAPABILITY",
                      "properties": {}
                    }
                  ]
                }
                """));
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(chatResponse);
        ChatModelProvider chatModelProvider = mock(ChatModelProvider.class);
        when(chatModelProvider.chatModel()).thenReturn(chatModel);

        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        when(graphStore.listNodes(any())).thenReturn(new PageResponse<>(
                List.of(new GraphNode(
                        "student-1",
                        Set.of("Student"),
                        Map.of("name", "Xiaoming", "privateNote", "private-value")
                )),
                new PageInfo(20, "", false)
        ));

        GraphSchemaRegistry schemaRegistry = new GraphSchemaRegistry();
        schemaRegistry.register(schema());
        LlmGraphDataConverter converter = new LlmGraphDataConverter(
                chatModelProvider,
                graphStore,
                schemaRegistry,
                settings(),
                objectMapper
        );

        var draft = converter.convert(new GraphBuildRequest(
                "request-1",
                "graph-1",
                "student-capability-v1",
                GraphBuildSourceType.NATURAL_LANGUAGE,
                LlmGraphDataConverter.CONVERTER_ID,
                objectMapper.getNodeFactory().textNode(
                        "Xiaoming has language-expression capability")
        ));

        assertThat(draft.nodes()).extracting(GraphNode::nodeId)
                .containsExactly("capability-language");
        assertThat(draft.relations()).singleElement().satisfies(relation -> {
            assertThat(relation.sourceNodeId()).isEqualTo("student-1");
            assertThat(relation.targetNodeId()).isEqualTo("capability-language");
        });

        ArgumentCaptor<dev.langchain4j.model.chat.request.ChatRequest> requestCaptor =
                ArgumentCaptor.forClass(dev.langchain4j.model.chat.request.ChatRequest.class);
        verify(chatModel).chat(requestCaptor.capture());
        UserMessage userMessage = (UserMessage) requestCaptor.getValue().messages().get(1);
        assertThat(userMessage.singleText()).contains("student-1", "Xiaoming");
        assertThat(userMessage.singleText()).doesNotContain("private-value");
    }

    private static GraphSchemaDefinition schema() {
        GraphPropertyDefinition name = new GraphPropertyDefinition(
                "name", GraphPropertyType.STRING, true, false, true, true);
        GraphPropertyDefinition privateNote = new GraphPropertyDefinition(
                "privateNote", GraphPropertyType.STRING, false, true, false, false);
        return new GraphSchemaDefinition(
                "student-capability-v1",
                1,
                GraphSchemaMode.STRICT,
                Map.of(
                        "Student", new GraphNodeTypeDefinition(
                                "Student", Map.of("name", name, "privateNote", privateNote)),
                        "Capability", new GraphNodeTypeDefinition(
                                "Capability", Map.of("name", name))
                ),
                Map.of("HAS_CAPABILITY", new GraphRelationTypeDefinition(
                        "HAS_CAPABILITY",
                        Set.of("Student"),
                        Set.of("Capability"),
                        Map.of()
                )),
                1,
                2
        );
    }

    private static GraphSettings settings() {
        return new GraphSettings(
                GraphProvider.NONE,
                "", "", "", "",
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                20,
                20,
                200,
                1,
                2,
                20,
                12_000
        );
    }
}
