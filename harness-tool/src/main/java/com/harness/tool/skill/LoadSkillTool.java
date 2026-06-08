package com.harness.tool.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.Skill;
import com.harness.core.model.SkillIndex;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import com.harness.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Built-in tool that the LLM calls to load skill content on demand.
 * Supports two modes:
 * - Full load: returns complete skill instructions (when query is omitted)
 * - Search: returns matching sections with context (when query is provided)
 */
public class LoadSkillTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(LoadSkillTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONTEXT_LINES = 3;
    private static final int MAX_MATCHES = 5;

    /** ThreadLocal for session-scoped temporary skill lookup. */
    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();

    private final SkillRegistry skillRegistry;
    private final ToolRegistry toolRegistry;

    public LoadSkillTool(SkillRegistry skillRegistry, ToolRegistry toolRegistry) {
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
    }

    public static void setCurrentSession(String sessionId) {
        CURRENT_SESSION_ID.set(sessionId);
    }

    public static void clearCurrentSession() {
        CURRENT_SESSION_ID.remove();
    }

    @Override
    public ToolSpec spec() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode nameProp = MAPPER.createObjectNode();
        nameProp.put("type", "string");
        nameProp.put("description", "要加载的 skill 名称");
        properties.set("name", nameProp);

        ObjectNode queryProp = MAPPER.createObjectNode();
        queryProp.put("type", "string");
        queryProp.put("description", "搜索关键词或正则表达式（可选，省略则返回全文）");
        properties.set("query", queryProp);

        params.set("properties", properties);

        var required = MAPPER.createArrayNode();
        required.add("name");
        params.set("required", required);

        return new ToolSpec(
                "load_skill",
                "加载 skill 内容。仅传 name 返回全文；传 name + query 返回匹配的片段（推荐，更高效）。",
                params
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String skillName = arguments.has("name") ? arguments.get("name").asText() : null;
        String query = arguments.has("query") ? arguments.get("query").asText() : null;

        if (skillName == null || skillName.isBlank()) {
            return "Error: missing required parameter 'name'";
        }

        String sessionId = CURRENT_SESSION_ID.get();

        // Look up skill
        SkillIndex index = skillRegistry.get(skillName, sessionId);
        if (index == null) {
            return "Error: skill '" + skillName + "' not found. Available skills: " +
                    skillRegistry.listAll(sessionId).stream().map(SkillIndex::name).toList();
        }

        Skill skill = skillRegistry.getFull(skillName, sessionId);
        if (skill == null) {
            return "Error: failed to load skill '" + skillName + "'";
        }

        // Track loaded skill for re-injection after major compression
        skillRegistry.markLoaded(sessionId, skillName, skill);

        // Search mode: return matching sections
        if (query != null && !query.isBlank()) {
            return executeSearch(skill, query);
        }

        // Full load mode: return complete content
        return executeFullLoad(skill);
    }

    private String executeFullLoad(Skill skill) {
        // Validate tools against ToolRegistry
        List<String> warnings = new ArrayList<>();
        if (skill.tools() != null && !skill.tools().isEmpty()) {
            for (String toolName : skill.tools()) {
                if (!toolRegistry.contains(toolName)) {
                    warnings.add("[WARNING: tool '" + toolName + "' not registered in ToolRegistry]");
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Skill: ").append(skill.name()).append("]\n");
        sb.append("[Description: ").append(skill.description()).append("]\n");
        if (skill.version() != null) {
            sb.append("[Version: ").append(skill.version()).append("]\n");
        }
        sb.append("\n[Instructions]\n");
        sb.append(skill.systemPrompt()).append("\n");

        if (skill.tools() != null && !skill.tools().isEmpty()) {
            sb.append("\n[Bound Tools: ").append(String.join(", ", skill.tools())).append("]\n");
        }

        for (String warning : warnings) {
            sb.append(warning).append("\n");
        }

        if (skill.parameters() != null && !skill.parameters().isEmpty()) {
            sb.append("[Parameters: ");
            List<String> paramPairs = new ArrayList<>();
            for (Map.Entry<String, Object> entry : skill.parameters().entrySet()) {
                paramPairs.add(entry.getKey() + "=" + entry.getValue());
            }
            sb.append(String.join(", ", paramPairs));
            sb.append("]\n");
        }

        log.info("Skill loaded (full): {} (tools={}, warnings={})",
                skill.name(),
                skill.tools() != null ? skill.tools().size() : 0,
                warnings.size());

        return sb.toString();
    }

    private String executeSearch(Skill skill, String query) {
        String content = skill.systemPrompt();
        if (content == null || content.isBlank()) {
            return "Skill '" + skill.name() + "' has no content to search.";
        }

        // Compile regex pattern
        Pattern pattern;
        try {
            pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return "Error: invalid regex pattern: " + e.getMessage();
        }

        String[] lines = content.split("\n");
        List<Integer> matchingLineIndices = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                matchingLineIndices.add(i);
            }
        }

        if (matchingLineIndices.isEmpty()) {
            return "No matches found for '" + query + "' in skill '" + skill.name() + "'.";
        }

        // Merge overlapping context windows
        List<int[]> windows = mergeWindows(matchingLineIndices, lines.length);

        // Build response
        StringBuilder sb = new StringBuilder();
        sb.append("[Skill Search: ").append(skill.name()).append("]\n");
        sb.append("[Query: ").append(query).append("]\n");
        sb.append("[Matches: ").append(Math.min(windows.size(), MAX_MATCHES)).append("]\n\n");

        int count = 0;
        for (int[] window : windows) {
            if (count >= MAX_MATCHES) break;

            int start = window[0];
            int end = window[1];

            sb.append("--- Match ").append(count + 1).append(" (line ").append(start + 1).append(") ---\n");
            for (int i = start; i <= end; i++) {
                if (i >= 0 && i < lines.length) {
                    if (i > start) sb.append("\n");
                    sb.append(lines[i]);
                }
            }
            sb.append("\n\n");
            count++;
        }

        log.info("Skill search: {} (query={}, matches={})", skill.name(), query, count);
        return sb.toString();
    }

    private List<int[]> mergeWindows(List<Integer> lineIndices, int totalLines) {
        List<int[]> windows = new ArrayList<>();
        if (lineIndices.isEmpty()) return windows;

        int start = Math.max(0, lineIndices.get(0) - CONTEXT_LINES);
        int end = Math.min(totalLines - 1, lineIndices.get(0) + CONTEXT_LINES);

        for (int i = 1; i < lineIndices.size(); i++) {
            int lineStart = Math.max(0, lineIndices.get(i) - CONTEXT_LINES);
            int lineEnd = Math.min(totalLines - 1, lineIndices.get(i) + CONTEXT_LINES);

            if (lineStart <= end + 1) {
                end = lineEnd;
            } else {
                windows.add(new int[]{start, end});
                start = lineStart;
                end = lineEnd;
            }
        }
        windows.add(new int[]{start, end});

        return windows;
    }
}
