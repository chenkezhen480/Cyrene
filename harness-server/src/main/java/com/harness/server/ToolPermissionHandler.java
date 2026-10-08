package com.harness.server;

import com.harness.core.model.AgentContext;
import com.harness.core.model.ToolSpec;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.tool.ToolRegistry;
import com.harness.tool.ToolGroup;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Admin surface for tenant + identity tool permissions.
 *
 * <p>The tool list is read live from {@link ToolRegistry} at request time, so a newly
 * registered tool appears here without any synchronisation step, and a name stored in
 * {@code disabled_tools_json} that no longer exists simply has no effect.</p>
 */
public final class ToolPermissionHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionHandler.class);

    private final ToolPermissionService service;
    private final ToolRegistry toolRegistry;
    private final ApiRequestAuthenticator authenticator;

    public ToolPermissionHandler(
            ToolPermissionService service,
            ToolRegistry toolRegistry,
            ApiRequestAuthenticator authenticator
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /**
     * These routes decide which tools every caller may use, so they sit behind the same
     * boundary as chat: without it anyone who can reach the port could grant themselves the
     * registry, or deny a whole tenant.
     */
    private boolean authorized(Context ctx) {
        try {
            authenticator.authenticate(ctx);
            var principal = authenticator.principal(ctx);
            String tenantId = ctx.queryParam("tenantId");
            if ("PUT".equals(ctx.method().name())) {
                ToolPermissionRequest request = ctx.bodyAsClass(ToolPermissionRequest.class);
                tenantId = request.tenantId();
                String identity = request.identity() == null ? AgentContext.DEFAULT_IDENTITY : required(request.identity(), "identity");
                if (principal.identity().equalsIgnoreCase(identity)
                        || (AgentContext.DEFAULT_IDENTITY.equalsIgnoreCase(identity)
                        && service.store().findDisabledTools(principal.tenantId(), principal.identity()).isEmpty())) {
                    throw new SecurityException("Callers cannot edit their own tool permissions");
                }
            }
            if (tenantId != null && !principal.tenantId().equals(tenantId.trim())) {
                throw new SecurityException("Cross-tenant tool permission management is forbidden");
            }
            return true;
        } catch (ApiRequestAuthenticator.RequestAuthenticationException e) {
            log.warn("[ToolPermission] Rejected unauthenticated admin request: {}", e.getMessage());
            ApiResponses.error(ctx, 401, ApiErrorCode.UNAUTHORIZED, e.getMessage());
            return false;
        }
    }

    public void get(Context ctx) {
        if (!authorized(ctx)) {
            return;
        }
        try {
            List<String> tenants = List.of(authenticator.principal(ctx).tenantId());
            String tenantId = trimmed(ctx.queryParam("tenantId"));
            if (tenantId == null) {
                ctx.json(new ToolPermissionView(
                        null, null, tenants, false, List.of(), registeredTools()));
                return;
            }
            String identity = trimmed(ctx.queryParam("identity"));
            String profileIdentity = identity != null ? identity : AgentContext.DEFAULT_IDENTITY;
            // Reads through the same lookup enforcement uses, so the page cannot claim an
            // identity is unrestricted while a request for it is actually limited.
            List<ToolView> tools = registeredTools();
            Set<String> registered = new LinkedHashSet<>();
            for (ToolView tool : tools) {
                registered.add(tool.name());
            }
            // Only registered names are reported: a stored name the registry no longer has cannot
            // be unchecked in the UI, and echoing it would make Save fail because PUT rejects it.
            List<String> disabled = service.resolveDisabledTools(tenantId, profileIdentity)
                    .orElse(Set.of()).stream()
                    .filter(registered::contains)
                    .sorted()
                    .toList();
            ctx.json(new ToolPermissionView(
                    tenantId,
                    profileIdentity,
                    tenants,
                    !disabled.isEmpty(),
                    disabled,
                    tools));
        } catch (ToolPermissionException e) {
            log.error("[ToolPermission] Read failed: {}", e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    public void identities(Context ctx) {
        if (!authorized(ctx)) return;
        try {
            requireTable();
            String tenantId = required(ctx.queryParam("tenantId"), "tenantId");
            String limit = ctx.queryParam("limit");
            ctx.json(service.store().listIdentities(tenantId, trimmed(ctx.queryParam("cursor")),
                    limit == null ? 50 : Integer.parseInt(limit)));
        } catch (ToolPermissionException e) {
            ApiResponses.error(ctx, 503, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    public void save(Context ctx) {
        if (!authorized(ctx)) {
            return;
        }
        try {
            ToolPermissionRequest request = ctx.bodyAsClass(ToolPermissionRequest.class);
            String tenantId = required(request.tenantId(), "tenantId");
            String identity = required(
                    request.identity() == null ? AgentContext.DEFAULT_IDENTITY : request.identity(),
                    "identity");
            requireTable();
            if (request.disabledTools() == null) {
                throw new IllegalArgumentException("disabledTools is required");
            }
            // A saved name that no longer exists in the registry would disable nothing while
            // still showing in the admin list, so reject it here where it is fixable. Taken from
            // the same list get() reports, so a page that round-trips what it was given can always
            // save it back.
            Set<String> registered = new LinkedHashSet<>();
            for (ToolView tool : registeredTools()) {
                registered.add(tool.name());
            }
            Set<String> unknown = new LinkedHashSet<>(request.disabledTools());
            unknown.removeAll(registered);
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(
                        "Unknown tool names: " + String.join(", ", unknown));
            }
            service.store().saveProfile(
                    tenantId, identity, new LinkedHashSet<>(request.disabledTools()));
            log.info("[ToolPermission] Saved profile tenant={} identity={} disabled={}",
                    tenantId, identity, request.disabledTools().size());
            ctx.json(new SavedToolPermission(tenantId, identity, request.disabledTools().size()));
        } catch (IllegalArgumentException | ToolPermissionException e) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        }
    }

    /**
     * Writes need the table; reads degrade to "feature off" without it. Answering a write with a
     * raw JDBC error would look like a validation failure instead of the missing migration it is.
     */
    private void requireTable() {
        if (!service.tablePresent()) {
            throw new ToolPermissionException(
                    ToolPermissionStore.PROFILE_TABLE + " is missing; apply the idempotent "
                            + "sql/schema-mysql.sql to this database first");
        }
    }

    /** The same permission vocabulary is displayed by GET and validated by PUT. */
    List<ToolView> registeredTools() {
        List<ToolView> tools = new ArrayList<>();
        var catalog = toolRegistry.snapshot();
        for (ToolSpec spec : catalog.getAll()) {
            tools.add(toolView(spec, null));
            if (catalog.get(spec.name()) instanceof ToolGroup group) {
                group.actionSpecs().forEach(action -> tools.add(toolView(action, spec.name())));
            }
        }
        return List.copyOf(tools);
    }

    private static ToolView toolView(ToolSpec spec, String groupName) {
        return new ToolView(spec.name(), spec.description(), spec.capability().name(), groupName);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ToolPermissionRequest(
            String tenantId,
            String identity,
            List<String> disabledTools
    ) {
    }

    public record ToolView(String name, String description, String capability, String groupName) {
    }

    public record ToolPermissionView(
            String tenantId,
            String identity,
            List<String> tenants,
            boolean restricted,
            List<String> disabledTools,
            List<ToolView> tools
    ) {
    }

    public record SavedToolPermission(String tenantId, String identity, int disabledToolCount) {
    }
}
