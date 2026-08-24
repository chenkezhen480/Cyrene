package com.harness.input.multimodal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownBlockParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile(
            "^\\s{0,3}(?:(?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,}|={3,})\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^\\s*(?:[-+*]|\\d+[.)])\\s+(?:\\[[ xX]])?\\s*.*$");
    private static final Pattern TABLE_DELIMITER = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,}).*$");

    List<MarkdownBlock> parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<MarkdownBlock> blocks = new ArrayList<>();
        int lineIndex = 0;
        while (lineIndex < lines.length) {
            if (lines[lineIndex].isBlank()) {
                lineIndex++;
                continue;
            }

            Matcher fence = FENCE.matcher(lines[lineIndex]);
            if (fence.matches()) {
                String marker = fence.group(1);
                int end = lineIndex + 1;
                while (end < lines.length && !isClosingFence(lines[end], marker)) {
                    end++;
                }
                if (end < lines.length) {
                    end++;
                }
                add(blocks, MarkdownBlockType.FENCED_CODE, join(lines, lineIndex, end), 0, null);
                lineIndex = end;
                continue;
            }

            Matcher heading = HEADING.matcher(lines[lineIndex]);
            if (heading.matches()) {
                add(blocks, MarkdownBlockType.HEADING, lines[lineIndex].strip(),
                        heading.group(1).length(), heading.group(2).strip());
                lineIndex++;
                continue;
            }

            if (HORIZONTAL_RULE.matcher(lines[lineIndex]).matches()) {
                add(blocks, MarkdownBlockType.HORIZONTAL_RULE, lines[lineIndex].strip(), 0, null);
                lineIndex++;
                continue;
            }

            if (isTableStart(lines, lineIndex)) {
                int end = lineIndex + 2;
                while (end < lines.length && isTableRow(lines[end])) {
                    end++;
                }
                add(blocks, MarkdownBlockType.TABLE, join(lines, lineIndex, end), 0, null);
                lineIndex = end;
                continue;
            }

            if (LIST_ITEM.matcher(lines[lineIndex]).matches()) {
                int end = lineIndex + 1;
                while (end < lines.length && isListContinuation(lines[end])) {
                    end++;
                }
                add(blocks, MarkdownBlockType.LIST, join(lines, lineIndex, end), 0, null);
                lineIndex = end;
                continue;
            }

            if (lines[lineIndex].stripLeading().startsWith(">")) {
                int end = lineIndex + 1;
                while (end < lines.length && lines[end].stripLeading().startsWith(">")) {
                    end++;
                }
                add(blocks, MarkdownBlockType.BLOCKQUOTE, join(lines, lineIndex, end), 0, null);
                lineIndex = end;
                continue;
            }

            int end = lineIndex + 1;
            while (end < lines.length && !lines[end].isBlank() && !startsStructuralBlock(lines, end)) {
                end++;
            }
            add(blocks, MarkdownBlockType.PARAGRAPH, join(lines, lineIndex, end), 0, null);
            lineIndex = end;
        }
        return List.copyOf(blocks);
    }

    private static boolean startsStructuralBlock(String[] lines, int index) {
        String line = lines[index];
        return FENCE.matcher(line).matches()
                || HEADING.matcher(line).matches()
                || HORIZONTAL_RULE.matcher(line).matches()
                || LIST_ITEM.matcher(line).matches()
                || line.stripLeading().startsWith(">")
                || isTableStart(lines, index);
    }

    private static boolean isClosingFence(String line, String marker) {
        String stripped = line.stripLeading();
        char markerCharacter = marker.charAt(0);
        int count = 0;
        while (count < stripped.length() && stripped.charAt(count) == markerCharacter) {
            count++;
        }
        return count >= marker.length() && stripped.substring(count).isBlank();
    }

    private static boolean isTableStart(String[] lines, int index) {
        return index + 1 < lines.length
                && isTableRow(lines[index])
                && TABLE_DELIMITER.matcher(lines[index + 1]).matches();
    }

    private static boolean isTableRow(String line) {
        String stripped = line.strip();
        return !stripped.isEmpty() && stripped.contains("|");
    }

    private static boolean isListContinuation(String line) {
        if (line.isBlank()) {
            return false;
        }
        if (LIST_ITEM.matcher(line).matches()) {
            return true;
        }
        return Character.isWhitespace(line.charAt(0))
                && !FENCE.matcher(line).matches()
                && !HEADING.matcher(line).matches();
    }

    private static String join(String[] lines, int start, int end) {
        return String.join("\n", java.util.Arrays.copyOfRange(lines, start, end)).strip();
    }

    private static void add(
            List<MarkdownBlock> blocks,
            MarkdownBlockType type,
            String content,
            int headingLevel,
            String headingText
    ) {
        blocks.add(new MarkdownBlock(
                blocks.size(), type, content, headingLevel, headingText));
    }
}
