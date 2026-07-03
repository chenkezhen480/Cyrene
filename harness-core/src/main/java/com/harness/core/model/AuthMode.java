package com.harness.core.model;

/**
 * Authentication mode for project API endpoints.
 * <ul>
 *   <li>{@link #BOT} — framework holds a service-level credential, all callers share one identity</li>
 *   <li>{@link #USER_PASSTHROUGH} — forwards the actual user's token from {@code AgentContext.credentials}</li>
 * </ul>
 */
public enum AuthMode {
    BOT,
    USER_PASSTHROUGH
}
