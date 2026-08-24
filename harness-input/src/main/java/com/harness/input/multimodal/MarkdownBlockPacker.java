package com.harness.input.multimodal;

import com.harness.core.text.TextTokenEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class MarkdownBlockPacker {

    private static final String SENTENCE_ENDINGS = "。.！!？?；;";

    private final TextTokenEstimator tokenEstimator;

    MarkdownBlockPacker(TextTokenEstimator tokenEstimator) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
    }

    List<MarkdownChunk> pack(List<MarkdownBlock> blocks, int chunkTokenSize) {
        if (chunkTokenSize <= 0) {
            throw new IllegalArgumentException("chunkTokenSize must be positive");
        }
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }

        List<PackUnit> units = buildUnits(blocks);
        List<MarkdownChunk> chunks = new ArrayList<>();
        MutableChunk current = null;

        for (PackUnit unit : units) {
            if (unit.type() == MarkdownBlockType.HORIZONTAL_RULE) {
                // A horizontal rule is a semantic boundary, not retrievable content.
                // Flush the adjacent section and never create a rule-only chunk.
                if (current != null) {
                    chunks.add(current.toChunk(tokenEstimator));
                    current = null;
                }
                continue;
            }

            List<PackPiece> pieces = split(unit, chunkTokenSize);
            for (PackPiece piece : pieces) {
                if (!fits(piece.content(), chunkTokenSize)) {
                    throw new IllegalStateException(
                            "Markdown block splitter produced an oversized chunk");
                }
                if (current != null && fits(join(current.content(), piece.content()), chunkTokenSize)) {
                    current.append(piece.content(), piece.endBlockIndex());
                } else {
                    if (current != null) {
                        chunks.add(current.toChunk(tokenEstimator));
                    }
                    current = new MutableChunk(piece);
                }
            }
        }

        if (current != null) {
            chunks.add(current.toChunk(tokenEstimator));
        }
        return List.copyOf(chunks);
    }

    private List<PackUnit> buildUnits(List<MarkdownBlock> blocks) {
        List<PackUnit> units = new ArrayList<>();
        List<String> headingLevels = new ArrayList<>(List.of("", "", "", "", "", ""));
        List<MarkdownBlock> pendingHeadings = new ArrayList<>();

        for (MarkdownBlock block : blocks) {
            if (block.type() == MarkdownBlockType.HEADING) {
                updateHeadingPath(headingLevels, block.headingLevel(), block.headingText());
                pendingHeadings.add(block);
                continue;
            }

            if (block.type() == MarkdownBlockType.HORIZONTAL_RULE) {
                flushPendingHeadings(units, pendingHeadings, headingPath(headingLevels));
                units.add(new PackUnit(
                        block.type(), "", block.content(), block.index(), block.index(),
                        headingPath(headingLevels)));
                continue;
            }

            String headingPrefix = pendingHeadings.stream()
                    .map(MarkdownBlock::content)
                    .reduce((left, right) -> join(left, right))
                    .orElse("");
            int startIndex = pendingHeadings.isEmpty()
                    ? block.index()
                    : pendingHeadings.getFirst().index();
            units.add(new PackUnit(
                    block.type(), headingPrefix, block.content(), startIndex, block.index(),
                    headingPath(headingLevels)));
            pendingHeadings.clear();
        }
        flushPendingHeadings(units, pendingHeadings, headingPath(headingLevels));
        return units;
    }

    private static void flushPendingHeadings(
            List<PackUnit> units,
            List<MarkdownBlock> pendingHeadings,
            List<String> headingPath
    ) {
        if (pendingHeadings.isEmpty()) {
            return;
        }
        String content = pendingHeadings.stream()
                .map(MarkdownBlock::content)
                .reduce((left, right) -> join(left, right))
                .orElseThrow();
        units.add(new PackUnit(
                MarkdownBlockType.HEADING,
                "",
                content,
                pendingHeadings.getFirst().index(),
                pendingHeadings.getLast().index(),
                headingPath));
        pendingHeadings.clear();
    }

    private List<PackPiece> split(PackUnit unit, int chunkTokenSize) {
        String fullContent = unit.headingPrefix().isBlank()
                ? unit.body()
                : join(unit.headingPrefix(), unit.body());
        if (fits(fullContent, chunkTokenSize)) {
            return List.of(new PackPiece(
                    fullContent, unit.startBlockIndex(), unit.endBlockIndex(), unit.headingPath()));
        }

        if (!unit.headingPrefix().isBlank()) {
            int prefixTokens = tokenEstimator.estimate(unit.headingPrefix());
            if (prefixTokens >= chunkTokenSize) {
                throw new IllegalArgumentException(
                        "Markdown heading exceeds the configured chunk token budget");
            }
            int bodyBudget = chunkTokenSize - prefixTokens - tokenEstimator.estimate("\n\n");
            if (bodyBudget <= 0) {
                throw new IllegalArgumentException(
                        "Markdown heading leaves no token budget for its first content block");
            }
            List<String> bodyPieces = splitBlock(unit.type(), unit.body(), bodyBudget);
            List<PackPiece> pieces = new ArrayList<>(bodyPieces.size());
            for (int index = 0; index < bodyPieces.size(); index++) {
                String content = index == 0
                        ? join(unit.headingPrefix(), bodyPieces.get(index))
                        : bodyPieces.get(index);
                pieces.add(new PackPiece(
                        content, unit.startBlockIndex(), unit.endBlockIndex(), unit.headingPath()));
            }
            return List.copyOf(pieces);
        }

        return splitBlock(unit.type(), unit.body(), chunkTokenSize).stream()
                .map(content -> new PackPiece(
                        content, unit.startBlockIndex(), unit.endBlockIndex(), unit.headingPath()))
                .toList();
    }

    private List<String> splitBlock(
            MarkdownBlockType type, String content, int chunkTokenSize) {
        if (fits(content, chunkTokenSize)) {
            return List.of(content);
        }
        return switch (type) {
            case FENCED_CODE -> splitFencedCode(content, chunkTokenSize);
            case TABLE -> splitTable(content, chunkTokenSize);
            case LIST, BLOCKQUOTE -> splitLines(content, chunkTokenSize, "", "");
            case HEADING, PARAGRAPH -> splitParagraph(content, chunkTokenSize);
            case HORIZONTAL_RULE -> throw new IllegalArgumentException(
                    "Horizontal rules must be attached to a neighboring block");
        };
    }

    private List<String> splitFencedCode(String content, int chunkTokenSize) {
        String[] lines = content.split("\n", -1);
        if (lines.length < 2) {
            return splitByTokenWindow(content, chunkTokenSize);
        }
        String openingFence = lines[0];
        String closingFence = isFenceLine(lines[lines.length - 1])
                ? lines[lines.length - 1]
                : fenceMarker(openingFence);
        int contentEnd = isFenceLine(lines[lines.length - 1]) ? lines.length - 1 : lines.length;
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, contentEnd));
        return splitLines(body, chunkTokenSize, openingFence, closingFence);
    }

    private List<String> splitTable(String content, int chunkTokenSize) {
        String[] lines = content.split("\n", -1);
        if (lines.length < 2) {
            return splitByTokenWindow(content, chunkTokenSize);
        }
        String tableHeader = lines[0] + "\n" + lines[1];
        if (!fits(tableHeader, chunkTokenSize)) {
            return splitByTokenWindow(content, chunkTokenSize);
        }
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, 2, lines.length));
        if (body.isBlank()) {
            return List.of(tableHeader);
        }
        return splitLines(body, chunkTokenSize, tableHeader, "");
    }

    private List<String> splitLines(
            String content, int chunkTokenSize, String prefix, String suffix) {
        List<String> pieces = new ArrayList<>();
        String current = "";
        for (String line : content.split("\n", -1)) {
            String candidateBody = current.isEmpty() ? line : current + "\n" + line;
            String candidate = wrap(candidateBody, prefix, suffix);
            if (fits(candidate, chunkTokenSize)) {
                current = candidateBody;
                continue;
            }
            if (!current.isEmpty()) {
                pieces.add(wrap(current, prefix, suffix));
                current = "";
            }
            String singleLine = wrap(line, prefix, suffix);
            if (fits(singleLine, chunkTokenSize)) {
                current = line;
            } else {
                Function<String, String> wrapper = value -> wrap(value, prefix, suffix);
                for (String window : splitByTokenWindow(line, chunkTokenSize, wrapper)) {
                    pieces.add(wrap(window, prefix, suffix));
                }
            }
        }
        if (!current.isEmpty()) {
            pieces.add(wrap(current, prefix, suffix));
        }
        return List.copyOf(pieces);
    }

    private List<String> splitParagraph(String content, int chunkTokenSize) {
        List<String> sentences = splitSentences(content);
        List<String> pieces = new ArrayList<>();
        String current = "";
        for (String sentence : sentences) {
            if (!fits(sentence, chunkTokenSize)) {
                if (!current.isBlank()) {
                    pieces.add(current.strip());
                    current = "";
                }
                pieces.addAll(splitByTokenWindow(sentence, chunkTokenSize));
                continue;
            }
            String candidate = current + sentence;
            if (!current.isBlank() && !fits(candidate, chunkTokenSize)) {
                pieces.add(current.strip());
                current = sentence;
            } else {
                current = candidate;
            }
        }
        if (!current.isBlank()) {
            pieces.add(current.strip());
        }
        return List.copyOf(pieces);
    }

    private List<String> splitByTokenWindow(String content, int chunkTokenSize) {
        return splitByTokenWindow(content, chunkTokenSize, Function.identity());
    }

    private List<String> splitByTokenWindow(
            String content,
            int chunkTokenSize,
            Function<String, String> wrapper
    ) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<Integer> codePointBoundaries = new ArrayList<>();
        codePointBoundaries.add(0);
        for (int offset = 0; offset < content.length();) {
            offset += Character.charCount(content.codePointAt(offset));
            codePointBoundaries.add(offset);
        }
        List<String> windows = new ArrayList<>();
        int startBoundary = 0;
        while (startBoundary < codePointBoundaries.size() - 1) {
            int startOffset = codePointBoundaries.get(startBoundary);
            int low = startBoundary + 1;
            int high = codePointBoundaries.size() - 1;
            int bestBoundary = -1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int endOffset = codePointBoundaries.get(middle);
                String candidate = content.substring(startOffset, endOffset);
                if (fits(wrapper.apply(candidate), chunkTokenSize)) {
                    bestBoundary = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            if (bestBoundary <= startBoundary) {
                throw new IllegalArgumentException(
                        "chunkTokenSize cannot fit one Unicode code point");
            }
            String window = content.substring(
                    startOffset, codePointBoundaries.get(bestBoundary)).strip();
            if (!window.isEmpty()) {
                windows.add(window);
            }
            startBoundary = bestBoundary;
        }
        return List.copyOf(windows);
    }

    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            current.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
            if (SENTENCE_ENDINGS.indexOf(codePoint) >= 0) {
                sentences.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            sentences.add(current.toString());
        }
        return sentences;
    }

    private boolean fits(String content, int chunkTokenSize) {
        return tokenEstimator.estimate(content) <= chunkTokenSize;
    }

    private static String wrap(String body, String prefix, String suffix) {
        String result = body;
        if (!prefix.isBlank()) {
            result = result.isBlank() ? prefix : prefix + "\n" + result;
        }
        if (!suffix.isBlank()) {
            result = result.isBlank() ? suffix : result + "\n" + suffix;
        }
        return result.strip();
    }

    private static String join(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right.strip();
        }
        if (right == null || right.isBlank()) {
            return left.strip();
        }
        return left.strip() + "\n\n" + right.strip();
    }

    private static boolean isFenceLine(String line) {
        String stripped = line.strip();
        return stripped.matches("`{3,}|~{3,}");
    }

    private static String fenceMarker(String openingFence) {
        String stripped = openingFence.stripLeading();
        char marker = stripped.charAt(0);
        int count = 0;
        while (count < stripped.length() && stripped.charAt(count) == marker) {
            count++;
        }
        return String.valueOf(marker).repeat(count);
    }

    private static void updateHeadingPath(
            List<String> headingLevels, int level, String headingText) {
        headingLevels.set(level - 1, headingText);
        for (int index = level; index < headingLevels.size(); index++) {
            headingLevels.set(index, "");
        }
    }

    private static List<String> headingPath(List<String> headingLevels) {
        return headingLevels.stream().filter(value -> !value.isBlank()).toList();
    }

    private record PackUnit(
            MarkdownBlockType type,
            String headingPrefix,
            String body,
            int startBlockIndex,
            int endBlockIndex,
            List<String> headingPath
    ) {
    }

    private record PackPiece(
            String content,
            int startBlockIndex,
            int endBlockIndex,
            List<String> headingPath
    ) {
    }

    private static final class MutableChunk {
        private final StringBuilder content;
        private final int startBlockIndex;
        private int endBlockIndex;
        private final List<String> headingPath;

        private MutableChunk(PackPiece piece) {
            this.content = new StringBuilder(piece.content());
            this.startBlockIndex = piece.startBlockIndex();
            this.endBlockIndex = piece.endBlockIndex();
            this.headingPath = piece.headingPath();
        }

        private String content() {
            return content.toString();
        }

        private void append(String value, int blockIndex) {
            content.append("\n\n").append(value);
            endBlockIndex = Math.max(endBlockIndex, blockIndex);
        }

        private MarkdownChunk toChunk(TextTokenEstimator estimator) {
            String value = content.toString().strip();
            return new MarkdownChunk(
                    value,
                    startBlockIndex,
                    endBlockIndex,
                    headingPath,
                    estimator.estimate(value));
        }
    }
}
