package com.harness.tool.filesystem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Find files by glob pattern, one cursor-ordered page at a time.
 *
 * <p>Unlike the {@code code_glob} it replaces, the root is not pinned to the discovered project:
 * {@code path} may be any absolute directory. It also no longer hides {@code .env} / {@code *.key} /
 * {@code application.yml} — reading real configuration is the point, and hiding names while
 * {@code read} can open the same file is security theatre, not a boundary.
 *
 * <p>Paging is by cursor rather than offset because the underlying tree changes underneath us: an
 * offset page boundary shifts when a file is added or removed, silently skipping or repeating
 * entries. A cursor names the last key actually delivered, so it stays correct. To make "after key"
 * meaningful the results are ordered by path relative to the search root — a total order both
 * backends agree on.
 */
public final class GlobTool implements Tool {

    public static final String TOOL_NAME = "glob";

    /** Cursor payload version, so a cursor from an older layout is rejected rather than misread. */
    private static final int CURSOR_VERSION = 1;

    /** Hard ceiling on one page, independent of how the operator configured the default. */
    private static final int MAX_PAGE = 200;

    private final FileSystemAccessPolicy policy;
    private final FileSystemWorkspace workspace;

    public GlobTool(FileSystemAccessPolicy policy, FileSystemWorkspace workspace) {
        this.policy = policy;
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        FileSystemAccessPolicy.Settings settings = policy.settings();
        int maxPage = maxPage(settings);
        ObjectNode schema = ToolArguments.objectSchema();
        ToolArguments.stringProperty(schema, "pattern",
                "Glob pattern matched against the path relative to the search root, "
                        + "e.g. '**/*.java', '**/application*.yml', '*.sql'.");
        ToolArguments.stringProperty(schema, "path",
                "Directory to search. Absolute, or relative to the workspace root. "
                        + "Defaults to " + workspace.root() + ".");
        ToolArguments.stringProperty(schema, "cursor",
                "next_cursor from the previous call, to fetch the following page. "
                        + "Only valid for the same pattern and path.");
        ToolArguments.intProperty(schema, "limit",
                "Page size. Defaults to " + settings.maxResults() + ", maximum " + maxPage + ".");
        ToolArguments.required(schema, "pattern");
        return new ToolSpec(
                TOOL_NAME,
                "Find files by glob pattern. Returns one page of at most "
                        + settings.maxResults() + " absolute paths, ordered by path, skipping .git, "
                        + "target, node_modules, build and dist. When the result carries a "
                        + "next_cursor, pass it back to continue; for an overview of a project's "
                        + "shape prefer tree.",
                schema,
                ToolCapability.RETRIEVAL);
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        String pattern = ToolArguments.requiredText(TOOL_NAME, arguments, "pattern");
        String pathArgument = ToolArguments.optionalText(arguments, "path");
        Path root = policy.resolveReadable(TOOL_NAME, pathArgument == null
                ? workspace.root()
                : workspace.resolve(TOOL_NAME, pathArgument));
        if (!Files.isDirectory(root)) {
            throw new ToolExecutionException(TOOL_NAME, "not a directory: " + root);
        }

        int limit = pageLimit(arguments);
        String cursor = ToolArguments.optionalText(arguments, "cursor");
        String after = cursor == null ? null : decodeCursor(cursor, pattern, root);

        FileSearchBackend.Page page = policy.searchBackend().glob(root, pattern, after, limit);
        List<Path> matches = page.paths();
        if (matches.isEmpty()) {
            return ToolExecutionOutcome.succeeded(
                    ToolOutput.text("No files found matching pattern: " + pattern + " under " + root),
                    ResultStatus.EMPTY);
        }

        StringBuilder text = new StringBuilder();
        text.append("Found ").append(matches.size()).append(" files under ").append(root);
        text.append(page.hasMore() ? " (more available):\n" : ":\n");
        for (Path match : matches) {
            text.append(match).append('\n');
        }
        if (page.hasMore()) {
            text.append("next_cursor: ")
                    .append(encodeCursor(pattern, root, relativeKey(root, matches.get(matches.size() - 1))))
                    .append('\n');
        }
        return ToolExecutionOutcome.succeeded(ToolOutput.text(text.toString()), ResultStatus.AVAILABLE);
    }

    // ────────────────────────────────────────────────────────────────── paging

    /** Must match what the backends order by, or the cursor would name the wrong position. */
    private static String relativeKey(Path root, Path file) {
        return NioSearchBackend.relativeKey(root, file);
    }

    private int pageLimit(JsonNode arguments) {
        int limit = ToolArguments.optionalInt(TOOL_NAME, arguments, "limit",
                policy.settings().maxResults());
        int maxPage = maxPage(policy.settings());
        if (limit < 1 || limit > maxPage) {
            throw new ToolExecutionException(TOOL_NAME, "limit must be between 1 and " + maxPage
                    + "; narrow the pattern rather than asking for one enormous page");
        }
        return limit;
    }

    /** The configured default is always reachable, and 200 is the floor under the ceiling. */
    private static int maxPage(FileSystemAccessPolicy.Settings settings) {
        return Math.max(MAX_PAGE, settings.maxResults());
    }

    private static String encodeCursor(String pattern, Path root, String afterRelative) {
        ObjectNode node = ToolArguments.MAPPER.createObjectNode();
        node.put("v", CURSOR_VERSION);
        node.put("h", queryHash(pattern, root));
        node.put("after", afterRelative);
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ToolArguments.MAPPER.writeValueAsBytes(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode glob cursor", e);
        }
    }

    /**
     * @return the key to resume after
     * @throws ToolExecutionException if the cursor is malformed or was issued for a different query
     */
    private static String decodeCursor(String cursor, String pattern, Path root) {
        JsonNode node;
        try {
            node = ToolArguments.MAPPER.readTree(Base64.getUrlDecoder().decode(cursor));
        } catch (IllegalArgumentException | java.io.IOException e) {
            throw invalidCursor();
        }
        if (node == null
                || node.path("v").asInt() != CURSOR_VERSION
                || !queryHash(pattern, root).equals(node.path("h").asText())) {
            throw invalidCursor();
        }
        String after = node.path("after").asText("");
        if (after.isEmpty()) {
            throw invalidCursor();
        }
        return after;
    }

    /**
     * Failing loudly matters here: silently restarting from the first page would look like progress
     * to the caller while it re-read the same entries forever.
     */
    private static ToolExecutionException invalidCursor() {
        return new ToolExecutionException(TOOL_NAME,
                "cursor is not valid for this pattern and path; drop cursor to start a new search");
    }

    private static String queryHash(String pattern, Path root) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((pattern + "\n" + root).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
