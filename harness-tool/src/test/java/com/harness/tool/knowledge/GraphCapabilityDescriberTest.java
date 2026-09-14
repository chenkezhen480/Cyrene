package com.harness.tool.knowledge;

import com.harness.core.text.UnicodeAwareTextTokenEstimator;
import com.harness.graph.schema.GraphNodeTypeDefinition;
import com.harness.graph.schema.GraphPropertyDefinition;
import com.harness.graph.schema.GraphPropertyType;
import com.harness.graph.schema.GraphRelationTypeDefinition;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaMode;
import com.harness.provider.ChatModelProvider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class GraphCapabilityDescriberTest {
    private final ChatModelProvider provider = mock(ChatModelProvider.class);
    private final ChatModel model = mock(ChatModel.class);
    private final GraphCapabilityDescriber describer = new GraphCapabilityDescriber(
            () -> provider, UnicodeAwareTextTokenEstimator.INSTANCE);

    @Test void sendsOnlyCapabilityTypesEndpointsAndLimitsToTheModel() {
        activateModel("查询学生所属班级，使用 query_graph 获取实际关系。");
        assertThat(describer.describe(schema())).contains("学生所属班级", "query_graph");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model).chat(captor.capture());
        String input = ((UserMessage) captor.getValue().getLast()).singleText();
        assertThat(input).contains("Student", "Class", "BELONGS_TO: Student -> Class", "Max depth: 3")
                .doesNotContain("sensitiveProperty", "private-schema-id");
    }

    @Test void rejectsInvalidModelDescriptionsAndDisabledOrInsufficientModels() {
        activateModel("x".repeat(2049));
        assertThatThrownBy(() -> describer.describe(schema())).hasMessageContaining("1 to 2048 characters");
        when(model.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(" ")).build());
        assertThatThrownBy(() -> describer.describe(schema())).hasMessageContaining("1 to 2048 characters");
        clearInvocations(model);
        when(provider.contextWindow()).thenReturn(10);
        assertThatThrownBy(() -> describer.describe(schema())).hasMessageContaining("context window");
        when(provider.providerName()).thenReturn("none");
        assertThatThrownBy(() -> describer.describe(schema())).hasMessageContaining("enabled Chat provider");
        verifyNoInteractions(model);
    }

    private void activateModel(String text) {
        when(provider.providerName()).thenReturn("configured-model");
        when(provider.contextWindow()).thenReturn(10_000);
        when(provider.chatModel()).thenReturn(model);
        when(model.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
    }

    private static GraphSchemaDefinition schema() {
        return new GraphSchemaDefinition("private-schema-id", 1, GraphSchemaMode.STRICT,
                Map.of("Student", new GraphNodeTypeDefinition("Student", Map.of("sensitiveProperty",
                                new GraphPropertyDefinition("sensitiveProperty", GraphPropertyType.STRING,
                                        false, true, false, false))),
                        "Class", new GraphNodeTypeDefinition("Class", Map.of())),
                Map.of("BELONGS_TO", new GraphRelationTypeDefinition("BELONGS_TO",
                        Set.of("Student"), Set.of("Class"), Map.of())), 1, 3);
    }
}
