package com.harness.input.multimodal;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Static text chunking utility. Splits text by semantic boundaries
 * (paragraphs → lines → fixed token count).
 */
public final class TextChunker {

    private TextChunker() {}

    public static List<String> split(String text) {
        return split(text, defaultChunkTokenSize());
    }

    public static List<String> split(String text, int chunkTokenSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        String[] paragraphs = text.split("\\n\\n+");
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;

        for (String paragraph : paragraphs) {
            int paragraphTokens = estimateTokens(paragraph);
            if (currentTokens + paragraphTokens > chunkTokenSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
                currentTokens = 0;
            }
            currentChunk.append(paragraph).append("\n\n");
            currentTokens += paragraphTokens;
        }
        if (!currentChunk.toString().isBlank()) {
            chunks.add(currentChunk.toString().trim());
        }

        List<String> finalChunks = new ArrayList<>();
        for (String chunk : chunks) {
            if (estimateTokens(chunk) > chunkTokenSize * 1.5) {
                finalChunks.addAll(splitByLine(chunk, chunkTokenSize));
            } else {
                finalChunks.add(chunk);
            }
        }

        return finalChunks;
    }

    private static List<String> splitByLine(String text, int chunkTokenSize) {
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("\\n");
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String line : lines) {
            int lineTokens = estimateTokens(line);
            if (currentTokens + lineTokens > chunkTokenSize && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
                currentTokens = 0;
            }
            current.append(line).append("\n");
            currentTokens += lineTokens;
        }
        if (!current.toString().isBlank()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    static int estimateTokens(String text) {
        if (text == null) return 0;
        return text.length() / 3;
    }

    private static int defaultChunkTokenSize() {
        return EnvConfig.get().getInt(EnvKey.INPUT_CHUNK_TOKEN_SIZE, 1024);
    }
}
