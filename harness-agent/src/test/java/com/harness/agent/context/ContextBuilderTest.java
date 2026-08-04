package com.harness.agent.context;

import com.harness.tool.rag.VectorStore;
import com.harness.tool.rerank.Reranker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextBuilderTest {

    @Test
    void buildRagForToolPreservesScoreEvidenceWithoutRejectedDocuments() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.providerName()).thenReturn("test");
        when(vectorStore.searchTextWithEvidence(anyString(), anyString(), anyInt()))
                .thenReturn(new VectorStore.SearchResult(List.of(), 0.45, 5));
        ContextBuilder contextBuilder = new ContextBuilder(vectorStore, new Reranker(null));

        ContextBuilder.ContextResult result = contextBuilder.buildRagForTool("standalone query");

        assertThat(result.hasContext()).isFalse();
        assertThat(result.ragHitIds()).isEmpty();
        assertThat(result.bestObservedScore()).isEqualTo(0.45);
        assertThat(result.observedCandidateCount()).isEqualTo(5);
    }
}
