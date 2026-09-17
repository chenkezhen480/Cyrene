package com.harness.tool.artifact;

/**
 * The session the tool call currently executing on this thread belongs to.
 *
 * <p>Tools are singletons shared by every session, so a tool that produces a durable artifact
 * has no other way to learn whose run it is serving — it is one more tool instance with no
 * per-run state. Without this the artifact is stored unowned and never shows up under the
 * session that produced it.</p>
 *
 * <p>Set and cleared by {@code ToolExecutor} around the single point where a tool actually
 * runs, mirroring how it already scopes the current tool call id.</p>
 */
public final class ArtifactSessionContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ArtifactSessionContext() {
    }

    /** No-op on {@code null}: an unowned artifact is still better than a wrong owner. */
    public static void set(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(sessionId);
        }
    }

    /** The current session id, or {@code null} outside a run. */
    public static String current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
