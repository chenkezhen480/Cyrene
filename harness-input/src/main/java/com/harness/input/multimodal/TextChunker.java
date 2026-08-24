package com.harness.input.multimodal;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.text.TextTokenEstimator;
import com.harness.core.text.UnicodeAwareTextTokenEstimator;

import java.util.List;
import java.util.Objects;

/**
 * Markdown-aware, single-pass chunking facade.
 *
 * <p>The instance API uses the tokenizer associated with the configured embedding provider.
 * Static methods remain for generic input and memory callers and use the deterministic
 * Unicode-aware estimator.</p>
 */
public final class TextChunker {

    private final TextTokenEstimator tokenEstimator;
    private final MarkdownBlockParser blockParser;
    private final MarkdownBlockPacker blockPacker;

    public TextChunker(TextTokenEstimator tokenEstimator) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        this.blockParser = new MarkdownBlockParser();
        this.blockPacker = new MarkdownBlockPacker(tokenEstimator);
    }

    public List<MarkdownChunk> chunk(String markdown, int chunkTokenSize) {
        return blockPacker.pack(blockParser.parse(markdown), chunkTokenSize);
    }

    public String tokenEstimatorStrategy() {
        return tokenEstimator.strategyName();
    }

    public static List<String> split(String text) {
        return split(text, defaultChunkTokenSize());
    }

    public static List<String> split(String text, int chunkTokenSize) {
        return new TextChunker(UnicodeAwareTextTokenEstimator.INSTANCE)
                .chunk(text, chunkTokenSize)
                .stream()
                .map(MarkdownChunk::content)
                .toList();
    }

    public static int estimateTokens(String text) {
        return UnicodeAwareTextTokenEstimator.INSTANCE.estimate(text);
    }

    private static int defaultChunkTokenSize() {
        return EnvConfig.get().getInt(EnvKey.INPUT_CHUNK_TOKEN_SIZE, 1024);
    }
}
