package com.harness.agent.context;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.AgentException;
import com.harness.core.model.AgentContext;
import com.harness.core.model.GraphRequestContext;
import com.harness.core.model.ParsedContent;
import com.harness.core.model.Preference;
import com.harness.core.model.SkillIndex;
import com.harness.input.document.DocumentConversionService;
import com.harness.tool.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Builds the model-facing input and system prompt from trusted request state. */
public final class AgentPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(AgentPromptBuilder.class);

    private final SkillRegistry skillRegistry;
    private final DocumentConversionService documentConversionService;

    public AgentPromptBuilder(
            SkillRegistry skillRegistry,
            DocumentConversionService documentConversionService
    ) {
        this.skillRegistry = skillRegistry;
        this.documentConversionService = java.util.Objects.requireNonNull(
                documentConversionService, "documentConversionService");
    }

    public String enhanceUserText(
            String text,
            List<ParsedContent> parsedContents,
            AgentContext agentContext
    ) {
        StringBuilder enhancedText = new StringBuilder(text == null ? "" : text);
        for (ParsedContent parsedContent : parsedContents) {
            if (parsedContent == null) {
                continue;
            }
            enhancedText.append("\n\n[File: ")
                    .append(parsedContent.metadata().get("file_name"))
                    .append("]\n")
                    .append(parsedContent.text());
        }

        boolean hasReferenceHeader = false;
        for (String filePath : contextFilePaths(agentContext)) {
            Path diskPath = resolveContextFile(filePath);
            if (!hasReferenceHeader) {
                enhancedText.append("\n\n[参考文件 / Reference Files]");
                hasReferenceHeader = true;
            }
            String name = diskPath.getFileName().toString();
            if (isAudioFile(diskPath)) {
                enhancedText.append("\n\n[Audio File: ")
                        .append(name)
                        .append("]\nReference: ")
                        .append(filePath)
                        .append("\nUse the transcribe_audio tool to read this audio file.");
            } else {
                enhancedText.append("\n\n[File: ")
                        .append(name)
                        .append("]\n")
                        .append(extractContextFileContent(filePath, diskPath));
            }
        }
        return enhancedText.toString();
    }

    public String buildSystemPrompt(
            List<Preference> longtermPreferences,
            String systemPromptOverride,
            String sessionId,
            boolean needsKnowledgeBase,
            boolean knowledgeGraphToolEnabled,
            GraphRequestContext graphRequestContext,
            boolean needsWebSearch
    ) {
        StringBuilder prompt = new StringBuilder();
        String basePrompt = systemPromptOverride != null && !systemPromptOverride.isBlank()
                ? systemPromptOverride
                : EnvConfig.get().getString(
                        EnvKey.SYSTEM_PROMPT,
                        "You are a helpful AI assistant with access to tools. Use tools when needed to answer questions. Think step by step. If a tool fails, try an alternative approach.");
        prompt.append(basePrompt).append("\n\n");
        prompt.append("IMPORTANT: After image/video generation tools succeed, do NOT include download links, file paths, image markdown syntax (![name](url)), or descriptive repetitions of the image in your text reply. The frontend automatically renders generated content as inline cards. Your text reply should only contain natural language commentary (e.g. style notes, asking if adjustments are needed).\n\n");

        appendKnowledgeBaseGuidance(prompt, needsKnowledgeBase);
        appendKnowledgeGraphGuidance(
                prompt, knowledgeGraphToolEnabled, graphRequestContext);
        appendWebSearchGuidance(prompt, needsWebSearch);
        appendSkills(prompt, sessionId);
        appendLongtermMemory(prompt, longtermPreferences);
        return prompt.toString();
    }

    private static void appendKnowledgeBaseGuidance(StringBuilder prompt, boolean enabled) {
        if (!enabled) {
            return;
        }
        prompt.append("Internal knowledge-base search is available and route analysis indicates it may help. "
                + "Use knowledge_base_search first when retrieved internal documents would improve the answer. "
                + "Its query must be a complete, standalone question without context-dependent references. "
                + "If a returned chunk already contains enough evidence, answer without reading more context. "
                + "Only when the hit clearly lacks a definition, prerequisite, or following step, call "
                + "knowledge_context_read with the exact documentId and chunkIndex returned by the search hit. "
                + "The context window defaults to one chunk before and after the anchor; enlarge it only when "
                + "necessary and within the tool limits. Never guess an anchor or repeat an identical window.\n\n");
    }

    private static void appendKnowledgeGraphGuidance(
            StringBuilder prompt,
            boolean enabled,
            GraphRequestContext requestContext
    ) {
        if (!enabled) {
            return;
        }
        prompt.append("Structured knowledge-graph search is available. "
                + "Call knowledge_graph_search when the question requires concrete entities or relationships. ");
        if (requestContext != null && requestContext.hasSubjectScope()) {
            prompt.append("The server has already supplied and authorized the graph space and subject nodes for this "
                    + "request. Call knowledge_graph_search at most once with findNeighborhood; do not call "
                    + "listGraphSpaces or findNodes and do not provide graphId, schemaId, or subjectIds. ");
        } else if (requestContext != null) {
            prompt.append("The server has already supplied and authorized the graph space for this request. Call "
                    + "findNodes once for the named entity, then call findNeighborhood once with the returned "
                    + "subjectIds. Do not call listGraphSpaces and do not provide graphId or schemaId. ");
        } else {
            prompt.append("Use the shortest retrieval sequence: discover graph spaces once, choose the closest "
                    + "description, find the named entity once, and retrieve its neighborhood once. ");
        }
        prompt.append("Do not repeat a failed call with identical arguments; use the error to correct the graph space "
                + "or stop graph retrieval. Treat graph nodes, relations, and paths as structured records; do not "
                + "describe them as document chunks. Use only identifiers returned by the tool and never generate "
                + "Cypher.\n\n");
    }

    private static void appendWebSearchGuidance(StringBuilder prompt, boolean enabled) {
        if (enabled) {
            prompt.append("Route analysis indicates that current information may be important. "
                    + "Use web_search to verify time-sensitive claims when the answer depends on fresh information.\n\n");
        }
    }

    private void appendSkills(StringBuilder prompt, String sessionId) {
        if (skillRegistry.size(sessionId) == 0) {
            return;
        }
        prompt.append("你有以下技能可以使用（通过 load_skill 工具加载）：\n");
        for (SkillIndex skill : skillRegistry.listAll(sessionId)) {
            prompt.append("- ").append(skill.name()).append("：")
                    .append(skill.description()).append("\n");
        }
        prompt.append("\nload_skill 用法：\n")
                .append("  - load_skill(name): 返回完整内容\n")
                .append("  - load_skill(name, query): 搜索并返回匹配片段（推荐，更高效）\n\n");
    }

    private static void appendLongtermMemory(
            StringBuilder prompt,
            List<Preference> longtermPreferences
    ) {
        if (longtermPreferences.isEmpty()) {
            return;
        }
        int maxChars = EnvConfig.get().getInt(EnvKey.MEMORY_LONGTERM_MAX_TOKENS, 800) * 3;
        StringBuilder memory = new StringBuilder("[User Memory]\n");
        for (Preference preference : longtermPreferences) {
            memory.append(preference.content()).append("\n");
        }
        if (memory.length() > maxChars) {
            memory.setLength(maxChars);
            memory.append("...\n");
            log.debug("Long-term memory truncated to {} chars", maxChars);
        }
        prompt.append(memory).append("\n");
    }

    private static List<String> contextFilePaths(AgentContext agentContext) {
        if (agentContext == null || agentContext.data() == null) {
            return List.of();
        }
        Object fileValue = agentContext.data().get("File");
        if (fileValue instanceof String path) {
            return List.of(path);
        }
        if (!(fileValue instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private static Path resolveContextFile(String filePath) {
        try {
            String uploadDir = EnvConfig.get().getString(
                    EnvKey.KNOWLEDGE_UPLOAD_DIR, "./knowledge-uploads");
            String relativePath = filePath.startsWith("/files/")
                    ? filePath.substring("/files/".length())
                    : filePath;
            Path uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
            Path diskPath = uploadRoot.resolve(relativePath).normalize();
            if (!diskPath.startsWith(uploadRoot)) {
                throw new AgentException("context.File resolves outside the upload directory: " + filePath);
            }
            if (!Files.exists(diskPath)) {
                throw new AgentException("context.File not found: " + filePath);
            }
            return diskPath;
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException(
                    "Failed to resolve context.File " + filePath + ": " + e.getMessage(), e);
        }
    }

    private String extractContextFileContent(String filePath, Path diskPath) {
        try {
            String fileName = diskPath.getFileName().toString();
            String mimeType = Files.probeContentType(diskPath);
            return documentConversionService.convert(
                    Files.readAllBytes(diskPath), fileName, mimeType).markdown();
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException(
                    "Failed to convert context.File " + filePath + ": " + e.getMessage(), e);
        }
    }

    private static boolean isAudioFile(Path path) {
        try {
            String mimeType = Files.probeContentType(path);
            if (mimeType != null && mimeType.toLowerCase(java.util.Locale.ROOT)
                    .startsWith("audio/")) {
                return true;
            }
        } catch (java.io.IOException e) {
            log.debug("Unable to probe context file type for {}: {}", path, e.getMessage());
        }
        String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return fileName.endsWith(".mp3")
                || fileName.endsWith(".m4a")
                || fileName.endsWith(".wav")
                || fileName.endsWith(".audio.webm")
                || fileName.endsWith(".ogg");
    }
}
