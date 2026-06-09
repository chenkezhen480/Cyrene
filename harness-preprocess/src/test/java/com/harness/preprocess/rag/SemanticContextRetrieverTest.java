package com.harness.preprocess.rag;

import com.harness.env.EnvConfig;
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
class SemanticContextRetrieverTest {

    @Mock PgVectorRagRetriever pgVectorRetriever;

    SemanticContextRetriever retriever;

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of("HARNESS_RAG_CONTEXT_LOOKBACK_MAX", "2"));
        retriever = new SemanticContextRetriever(pgVectorRetriever);
    }

    private RagRetriever.RagDocument doc(String id, String content) {
        return new RagRetriever.RagDocument(id, content, "test.pdf", 0.9);
    }

    // ---- Complete chunks (no lookback needed) ----

    @Test
    void enhance_null_returnsEmpty() {
        assertThat(retriever.enhance(null)).isEmpty();
    }

    @Test
    void enhance_emptyList_returnsEmpty() {
        assertThat(retriever.enhance(List.of())).isEmpty();
    }

    @Test
    void enhance_completeChunk_noLookback() {
        // Ends with terminal punctuation — complete
        RagRetriever.RagDocument chunk = doc("c1", "This is a complete sentence.");
        var result = retriever.enhance(List.of(chunk));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).lookbackCount()).isEqualTo(0);
        assertThat(result.get(0).document().content()).isEqualTo("This is a complete sentence.");
    }

    @Test
    void enhance_chineseComplete_noLookback() {
        RagRetriever.RagDocument chunk = doc("c1", "这是一句完整的话。");
        var result = retriever.enhance(List.of(chunk));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).lookbackCount()).isEqualTo(0);
    }

    @Test
    void enhance_structuralEnding_noLookback() {
        // Ends with '}' — structural complete
        RagRetriever.RagDocument chunk = doc("c1", "int main() { return 0; }");
        var result = retriever.enhance(List.of(chunk));

        assertThat(result.get(0).lookbackCount()).isEqualTo(0);
    }

    // ---- Incomplete chunks (triggers lookback) ----

    @Test
    void enhance_truncatedChunk_lookback() {
        // Chunk starts with lowercase 'and' — continuation, incomplete
        RagRetriever.RagDocument incomplete = doc("c2", "and then the system crashed");
        RagRetriever.RagDocument prev = doc("c1", "The server was running normally");

        when(pgVectorRetriever.getPrevChunkId("c2")).thenReturn("c1");
        when(pgVectorRetriever.retrieveById("c1")).thenReturn(prev);

        var result = retriever.enhance(List.of(incomplete));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).lookbackCount()).isEqualTo(1);
        assertThat(result.get(0).lookbackChunkIds()).containsExactly("c1");
        assertThat(result.get(0).document().content()).contains("The server was running normally");
        assertThat(result.get(0).document().content()).contains("and then the system crashed");
    }

    @Test
    void enhance_chineseContinuation_lookback() {
        RagRetriever.RagDocument incomplete = doc("c2", "但是还有其他因素");
        RagRetriever.RagDocument prev = doc("c1", "主要原因是温度过高");

        when(pgVectorRetriever.getPrevChunkId("c2")).thenReturn("c1");
        when(pgVectorRetriever.retrieveById("c1")).thenReturn(prev);

        var result = retriever.enhance(List.of(incomplete));

        assertThat(result.get(0).lookbackCount()).isEqualTo(1);
        assertThat(result.get(0).document().content()).contains("主要原因是温度过高");
    }

    @Test
    void enhance_commaStart_lookback() {
        RagRetriever.RagDocument incomplete = doc("c2", ", which was unexpected");
        RagRetriever.RagDocument prev = doc("c1", "The result was negative");

        when(pgVectorRetriever.getPrevChunkId("c2")).thenReturn("c1");
        when(pgVectorRetriever.retrieveById("c1")).thenReturn(prev);

        var result = retriever.enhance(List.of(incomplete));

        assertThat(result.get(0).lookbackCount()).isEqualTo(1);
    }

    @Test
    void enhance_noPrevId_stops() {
        RagRetriever.RagDocument incomplete = doc("c2", "and then nothing");
        when(pgVectorRetriever.getPrevChunkId("c2")).thenReturn(null);

        var result = retriever.enhance(List.of(incomplete));

        assertThat(result.get(0).lookbackCount()).isEqualTo(0);
    }

    @Test
    void enhance_maxLookbackRespected() {
        // With fix: uses currentId (prev chunk) for each iteration, enabling multi-level lookback
        RagRetriever.RagDocument c3 = doc("c3", "however this is incomplete");
        RagRetriever.RagDocument c2 = doc("c2", "still not complete");
        RagRetriever.RagDocument c1 = doc("c1", "The beginning.");

        when(pgVectorRetriever.getPrevChunkId("c3")).thenReturn("c2");
        when(pgVectorRetriever.retrieveById("c2")).thenReturn(c2);
        when(pgVectorRetriever.getPrevChunkId("c2")).thenReturn("c1");
        when(pgVectorRetriever.retrieveById("c1")).thenReturn(c1);

        var result = retriever.enhance(List.of(c3));

        assertThat(result.get(0).lookbackCount()).isEqualTo(2);
        assertThat(result.get(0).document().content()).contains("The beginning.");
    }

    @Test
    void enhance_multipleChunks_independent() {
        RagRetriever.RagDocument complete = doc("c1", "Complete sentence.");
        RagRetriever.RagDocument incomplete = doc("c2", "but not complete");
        RagRetriever.RagDocument prev = doc("c0", "Something before");

        when(pgVectorRetriever.getPrevChunkId("c2")).thenReturn("c0");
        when(pgVectorRetriever.retrieveById("c0")).thenReturn(prev);

        var result = retriever.enhance(List.of(complete, incomplete));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).lookbackCount()).isEqualTo(0);
        assertThat(result.get(1).lookbackCount()).isEqualTo(1);
    }
}
