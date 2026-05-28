package com.harness.input.multimodal;

import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.model.VisionModelProvider;
import com.harness.ai.model.VoiceModelProvider;
import com.harness.core.model.ParsedContent;
import com.harness.input.multimodal.impl.TextExtractorRegistry;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Large file parser using a MapReduce-style summarization approach.
 * <ol>
 *   <li>Extract text via TextExtractorRegistry (supports PDF, DOCX, XLSX, etc.)</li>
 *   <li>Split by semantic boundaries via TextChunker</li>
 *   <li>Each chunk is independently summarized via ChatModelProvider</li>
 *   <li>Summaries are merged: flat merge for small sets, tree reduce for large sets</li>
 * </ol>
 */
public class LargeFileParser {

    private static final Logger log = LoggerFactory.getLogger(LargeFileParser.class);
    private static final String SUMMARIZE_PROMPT = "请对以下文本进行摘要，保留关键信息，去除冗余内容：";
    private static final String MERGE_PROMPT = "请将以下多个摘要合并为一份连贯的最终摘要，保留所有关键信息：";
    private static final int GROUP_SIZE = 8;

    private final ChatModelProvider chatProvider;

    public LargeFileParser(ChatModelProvider chatProvider, VisionModelProvider visionProvider, VoiceModelProvider voiceProvider) {
        this.chatProvider = chatProvider;
    }

    public ParsedContent parse(byte[] fileData, String fileName, String mimeType) {
        log.info("Large file parsing: file={}, size={}KB, mimeType={}", fileName, fileData.length / 1024, mimeType);

        // Step 1: Extract raw text via TextExtractorRegistry
        String rawText = TextExtractorRegistry.extract(fileData, fileName, mimeType);

        // Step 2: Split into chunks via TextChunker
        List<String> chunks = TextChunker.split(rawText);
        log.info("Split into {} chunks", chunks.size());

        // Step 3: Summarize each chunk
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String summary = summarizeChunk(chunks.get(i), i + 1, chunks.size());
            summaries.add(summary);
        }

        // Step 4: Merge summaries
        String finalText;
        if (summaries.size() <= GROUP_SIZE) {
            finalText = mergeSummaries(summaries);
        } else {
            List<String> groupSummaries = new ArrayList<>();
            for (int i = 0; i < summaries.size(); i += GROUP_SIZE) {
                List<String> group = summaries.subList(i, Math.min(i + GROUP_SIZE, summaries.size()));
                groupSummaries.add(mergeSummaries(new ArrayList<>(group)));
            }
            finalText = mergeSummaries(groupSummaries);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file_name", fileName);
        metadata.put("file_size_kb", fileData.length / 1024);
        metadata.put("original_chunks", chunks.size());

        return new ParsedContent(finalText, ParsedContent.ParseStrategy.CHUNKED_REDUCE, chunks.size(), metadata);
    }

    private String summarizeChunk(String chunk, int index, int total) {
        try {
            String prompt = SUMMARIZE_PROMPT + "\n\n[" + index + "/" + total + "]\n" + chunk;
            return chatProvider.chatModel().chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build()).aiMessage().text();
        } catch (Exception e) {
            log.warn("Failed to summarize chunk {}/{}: {}", index, total, e.getMessage());
            return chunk;
        }
    }

    private String mergeSummaries(List<String> summaries) {
        if (summaries.size() == 1) return summaries.get(0);
        try {
            StringBuilder prompt = new StringBuilder(MERGE_PROMPT);
            for (int i = 0; i < summaries.size(); i++) {
                prompt.append("\n\n--- 摘要 ").append(i + 1).append(" ---\n").append(summaries.get(i));
            }
            return chatProvider.chatModel().chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt.toString()))
                    .build()).aiMessage().text();
        } catch (Exception e) {
            log.warn("Failed to merge summaries: {}", e.getMessage());
            return String.join("\n\n", summaries);
        }
    }
}
