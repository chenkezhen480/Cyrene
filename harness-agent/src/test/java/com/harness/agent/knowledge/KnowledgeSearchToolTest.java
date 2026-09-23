package com.harness.agent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.knowledge.KnowledgeHandleCodec;
import com.harness.core.knowledge.KnowledgeSearchOptions;
import com.harness.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeSearchToolTest {
    @Test
    void agentChoosesWeightAndTopKButCannotOverrideServerThresholds() throws Exception {
        var mapper = new ObjectMapper();
        var router = mock(KnowledgeDiscoveryRouter.class);
        var tool = new KnowledgeSearchTool(router, mock(KnowledgeHandleCodec.class), mapper);
        var properties = tool.spec().parameters().get("properties");
        assertThat(properties.has("denseThreshold")).isFalse();
        assertThat(properties.has("sparseThreshold")).isFalse();
        assertThat(properties.has("bm25Weight")).isTrue();
        KnowledgeToolRuntimeContext.activate(null, "alice", null, null, new ToolRegistry().snapshot());
        try {
            tool.execute(mapper.readTree("{\"query\":\"cache\",\"limit\":8,\"candidateTopK\":30,\"bm25Weight\":1,\"rerank\":false}"));
            var captured = org.mockito.ArgumentCaptor.forClass(KnowledgeSearchOptions.class);
            verify(router).search(eq("cache"), anySet(), captured.capture(), any());
            assertThat(captured.getValue().bm25Weight()).isEqualTo(1);
            assertThat(captured.getValue().limit()).isEqualTo(8);
            assertThat(captured.getValue().candidateTopK()).isEqualTo(30);
            assertThatThrownBy(() -> tool.execute(mapper.readTree("{\"query\":\"cache\",\"denseThreshold\":0}")))
                    .hasMessageContaining("configured by the server");
            assertThatThrownBy(() -> tool.execute(mapper.readTree("{\"query\":\"cache\",\"limit\":10,\"candidateTopK\":2}")))
                    .hasMessageContaining("candidateTopK");
        } finally { KnowledgeToolRuntimeContext.clear(); }
    }
}
