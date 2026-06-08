package com.harness.tool.skill;

import com.harness.core.model.Skill;
import com.harness.core.model.SkillIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses Markdown files with YAML frontmatter to load skill definitions.
 * Supports two modes: index scan (lightweight) and full load.
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);
    private static final Yaml YAML = new Yaml();

    /**
     * Scan a directory for .md skill files, parse only frontmatter name+description.
     * Returns lightweight SkillIndex list without reading full content.
     */
    public static List<SkillIndex> scanIndex(Path dir) {
        List<SkillIndex> indexes = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            log.warn("Skill directory not found: {}", dir);
            return indexes;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path file : stream) {
                try {
                    String content = Files.readString(file);
                    Map<String, Object> frontmatter = parseFrontmatter(content);
                    if (frontmatter == null) continue;

                    String name = getString(frontmatter, "name");
                    String description = getString(frontmatter, "description");
                    if (name == null || description == null) {
                        log.warn("Skipping skill file (missing name or description): {}", file);
                        continue;
                    }

                    indexes.add(new SkillIndex(name, description, file.toString()));
                    log.debug("Skill indexed: {} — {}", name, description);
                } catch (Exception e) {
                    log.warn("Failed to parse skill file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan skill directory {}: {}", dir, e.getMessage());
        }

        return indexes;
    }

    /**
     * Load full skill content from a SkillIndex (reads file from disk).
     */
    public static Skill loadFull(SkillIndex index) {
        if (index.filePath() == null) {
            log.warn("Cannot load skill from null filePath: {}", index.name());
            return null;
        }
        try {
            String content = Files.readString(Path.of(index.filePath()));
            return parseSkill(content);
        } catch (IOException e) {
            log.warn("Failed to read skill file {}: {}", index.filePath(), e.getMessage());
            return null;
        }
    }

    /**
     * Parse a skill from raw content string (for upload scenario).
     */
    public static Skill loadFromContent(String content) {
        return parseSkill(content);
    }

    /**
     * Check if content looks like a skill file (has YAML frontmatter with name+description).
     */
    public static boolean isSkillFile(String content) {
        if (content == null || content.isBlank()) return false;
        Map<String, Object> frontmatter = parseFrontmatter(content);
        if (frontmatter == null) return false;
        String name = getString(frontmatter, "name");
        String description = getString(frontmatter, "description");
        return name != null && description != null;
    }

    // ---- internal parsing ----

    private static Skill parseSkill(String content) {
        Map<String, Object> frontmatter = parseFrontmatter(content);
        if (frontmatter == null) {
            log.warn("No YAML frontmatter found in skill content");
            return null;
        }

        String name = getString(frontmatter, "name");
        String description = getString(frontmatter, "description");
        if (name == null || description == null) {
            log.warn("Skill missing required fields (name, description)");
            return null;
        }

        String version = getString(frontmatter, "version");
        List<String> tools = getStringList(frontmatter, "tools");
        Map<String, Object> parameters = getMap(frontmatter, "parameters");
        String body = extractBody(content);

        return new Skill(name, description, version, body, tools, parameters);
    }

    /**
     * Parse YAML frontmatter between --- delimiters.
     * Returns null if no valid frontmatter found.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseFrontmatter(String content) {
        if (content == null || content.isBlank()) return null;

        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("---")) return null;

        int end = trimmed.indexOf("---", 3);
        if (end < 0) return null;

        String yamlStr = trimmed.substring(3, end).strip();
        if (yamlStr.isBlank()) return null;

        try {
            Object parsed = YAML.load(yamlStr);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to parse YAML frontmatter: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract markdown body after the second --- delimiter.
     */
    private static String extractBody(String content) {
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("---")) return trimmed;

        int end = trimmed.indexOf("---", 3);
        if (end < 0) return "";

        String body = trimmed.substring(end + 3);
        // Skip leading newline after closing ---
        if (body.startsWith("\r\n")) body = body.substring(2);
        else if (body.startsWith("\n")) body = body.substring(1);
        return body.stripTrailing();
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        String s = val.toString().strip();
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) result.add(item.toString());
            }
            return result;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }
}
