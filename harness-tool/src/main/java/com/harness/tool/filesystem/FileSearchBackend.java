package com.harness.tool.filesystem;

import java.nio.file.Path;
import java.util.List;

/**
 * Glob and grep over one directory.
 *
 * <p>Two implementations exist because ripgrep is fast but not guaranteed to be installed, and the
 * fallback has to return the same answers or the agent's behaviour would silently depend on whether
 * an external binary happens to be on PATH. Both are expected to search hidden files, ignore
 * gitignore/ignore files, and skip the same build directories.
 */
public interface FileSearchBackend {

    /** Name reported in tool output, e.g. {@code ripgrep} or {@code nio}. */
    String name();

    /**
     * One page of glob results, ordered by path relative to {@code root}.
     *
     * <p>Ordering is the whole reason this is a page rather than a truncation: a cursor is only
     * meaningful against a total order, so both backends must sort by the same key. Relative (not
     * absolute) and with {@code /} separators, so a page boundary means the same thing everywhere.
     *
     * @param afterRelative return only paths strictly after this key; null for the first page
     * @return at most {@code limit} paths, and whether anything was left over
     */
    Page glob(Path root, String pattern, String afterRelative, int limit);

    /**
     * Content matches under {@code root}, in file order, at most {@code maxResults} matching lines
     * and at most {@code maxOutputBytes} of collected text in total.
     *
     * @param fileGlob       optional additional filename filter, or null
     * @param contextLines   lines of context to carry around each match
     * @param maxOutputBytes ceiling on the text collected, so a broad pattern over a large tree
     *                       cannot pull an unbounded result set into memory
     */
    GrepResult grep(Path root, String pattern, String fileGlob,
                    int contextLines, int maxResults, int maxOutputBytes);

    /** One page of glob matches, plus whether the search root holds more beyond it. */
    record Page(List<Path> paths, boolean hasMore) {
    }

    /** Matches inside one file, in ascending line order. */
    record GrepFileResult(Path path, List<GrepLine> matches) {
    }

    /** One matching line, 1-based, with up to {@code contextLines} neighbours attached. */
    record GrepLine(int lineNumber, String text, List<String> before, List<String> after) {
    }

    /**
     * Grep matches plus whether the byte ceiling cut collection short. Reported rather than silently
     * trimmed: a partial result that looks complete is worse than one that says it is partial.
     */
    record GrepResult(List<GrepFileResult> files, boolean truncated) {
    }

    /**
     * Collection budget shared by both backends so they stop at the same place.
     *
     * <p>Exhausting the match ceiling is not "truncated" — the caller already reports that against
     * {@code maxResults}. Only the byte ceiling sets the flag.
     */
    final class Budget {

        private final int maxMatches;
        private final long maxBytes;
        private int matches;
        private long bytes;
        private boolean truncated;

        public Budget(int maxMatches, long maxBytes) {
            this.maxMatches = maxMatches;
            this.maxBytes = maxBytes;
        }

        /**
         * Charge one collected line.
         *
         * @return false when collection must stop, in which case the caller must not keep the line
         */
        public boolean add(boolean isMatch, int chars) {
            if (isMatch) {
                if (matches >= maxMatches) {
                    return false;
                }
                matches++;
            }
            bytes += chars + 1L;
            if (bytes > maxBytes) {
                truncated = true;
                return false;
            }
            return true;
        }

        public int matches() {
            return matches;
        }

        public boolean truncated() {
            return truncated;
        }

        /** True once no further line can be accepted, whichever ceiling was reached first. */
        public boolean exhausted() {
            return matches >= maxMatches || truncated;
        }
    }

    /**
     * Keeps the {@code limit} smallest keys greater than {@code after}, in O(limit) memory.
     *
     * <p>A cursor page is the first {@code limit} entries of a total order, so it cannot be answered
     * by stopping early the way a plain cap can — every candidate has to be seen. Holding them all
     * would make an unbounded read; holding only the best {@code limit} in a bounded max-heap makes
     * it O(1) in the size of the tree.
     */
    final class PageCollector {

        private final String after;
        private final int limit;
        /** Max-heap: {@code peek()} is the largest key retained, i.e. the first to evict. */
        private final java.util.PriorityQueue<String> kept =
                new java.util.PriorityQueue<>(java.util.Comparator.reverseOrder());
        private boolean hasMore;

        public PageCollector(String afterRelative, int limit) {
            this.after = afterRelative;
            this.limit = limit;
        }

        /**
         * @param key path relative to the search root, {@code /} separated
         */
        public void offer(String key) {
            if (after != null && key.compareTo(after) <= 0) {
                // Belongs to an earlier page; it is not evidence of anything more.
                return;
            }
            if (kept.size() < limit) {
                kept.add(key);
                return;
            }
            // Either this key falls past the page, or it evicts one that does — both mean more.
            hasMore = true;
            if (key.compareTo(kept.peek()) < 0) {
                kept.poll();
                kept.add(key);
            }
        }

        public List<String> ascending() {
            List<String> keys = new java.util.ArrayList<>(kept);
            keys.sort(java.util.Comparator.naturalOrder());
            return keys;
        }

        public boolean hasMore() {
            return hasMore;
        }
    }
}
