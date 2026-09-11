package com.harness.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.tool.ToolRegistry;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.authority.KnowledgeRevisionChange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SaveMemoryToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final Runnable signal = mock(Runnable.class);
    private final SaveMemoryTool tool = new SaveMemoryTool(repository, mapper, Clock.systemUTC(), signal);

    @AfterEach void clear() { KnowledgeToolRuntimeContext.clear(); }

    @Test void writesConversationMemoriesWithTrustedOwnershipAndAtomicOutbox() throws Exception {
        activate("user-a");
        for (String type : List.of("USER_EPISODE", "OPERATION_PLAYBOOK")) {
            var result = mapper.readTree(tool.execute(arguments(type)));
            assertThat(result.path("status").asText()).isEqualTo("pending");
        }
        ArgumentCaptor<List<KnowledgeRevisionChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).commitChanges(captor.capture());
        var episode = captor.getAllValues().get(0).getFirst();
        var operation = captor.getAllValues().get(1).getFirst();
        assertThat(episode.concept().userId()).isEqualTo("user-a");
        assertThat(operation.concept().userId()).isNull();
        assertThat(operation.concept().tenantId()).isEqualTo("tenant-a");
        assertThat(operation.concept().conceptType()).isEqualTo(KnowledgeConceptType.OPERATION_PLAYBOOK);
        assertThat(operation.revision().description()).isEqualTo("Searchable summary");
        assertThat(operation.revision().body()).isEqualTo("Observed procedure and verification");
        assertThat(operation.sources()).isEmpty();
        assertThat(operation.indexTasks()).singleElement().satisfies(task ->
                assertThat(task.revisionId()).isEqualTo(operation.revision().id()));
        verify(signal, times(2)).run();
    }

    @Test void rejectsPreferencesAnonymousEpisodesAndSecretsBeforeWriting() {
        activate(null);
        assertThatThrownBy(() -> tool.execute(arguments("USER_EPISODE"))).hasMessageContaining("authenticated user");
        activate("user-a");
        assertThatThrownBy(() -> tool.execute(arguments("USER_PREFERENCE"))).hasMessageContaining("Only user episodes");
        assertThatThrownBy(() -> tool.execute(arguments("OPERATION_PLAYBOOK")
                .put("content", "password=secret-value"))).hasMessageContaining("credentials");
        verify(repository, never()).commitChanges(any());
    }

    @Test void reportsPersistenceFailureAndDoesNotSignalIndex() {
        activate("user-a");
        doThrow(new IllegalStateException("transaction failed")).when(repository).commitChanges(any());
        assertThatThrownBy(() -> tool.execute(arguments("USER_EPISODE")))
                .hasMessageContaining("transaction failed");
        verifyNoInteractions(signal);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode arguments(String type) {
        return mapper.createObjectNode().put("memoryType", type).put("memoryKey", "observed-task")
                .put("title", "Title").put("summary", "Searchable summary")
                .put("content", "Observed procedure and verification");
    }

    private void activate(String userId) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        KnowledgeToolRuntimeContext.activate("tenant-a", userId, null, null, registry.snapshot());
    }
}
