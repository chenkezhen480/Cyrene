package com.harness.server.security;

import com.harness.core.security.ApiEndpointDescriptor;
import com.harness.core.security.RequestPrincipal;
import com.harness.core.model.AgentContext;

import java.util.Set;

import static com.harness.core.security.ApiEndpointDescriptor.ResourcePolicy.*;
import static com.harness.core.security.RequestPrincipal.AuthenticationType.*;

public final class InternalApiPermissionService {
    private final MysqlInternalApiPermissionStore store;
    private final InternalApiRouteRegistry routes;
    private final boolean enabled;
    private final String managementTenantId;

    public InternalApiPermissionService(MysqlInternalApiPermissionStore store,
                                        InternalApiRouteRegistry routes, boolean enabled) {
        this(store, routes, enabled, AgentContext.DEFAULT_TENANT_ID);
    }

    public InternalApiPermissionService(MysqlInternalApiPermissionStore store,
                                        InternalApiRouteRegistry routes, boolean enabled, String managementTenantId) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.routes = java.util.Objects.requireNonNull(routes, "routes");
        this.enabled = enabled;
        this.managementTenantId = java.util.Objects.requireNonNull(managementTenantId, "managementTenantId");
    }

    public void authorize(RequestPrincipal principal, ApiEndpointDescriptor endpoint) {
        if (endpoint.resourcePolicy() == PUBLIC) return;
        InternalApiRouteRegistry.requireAuthenticated(principal, endpoint);
        if (principal.authenticationType() == ADMIN_BOOTSTRAP) return;
        if (!enabled) return;
        if (principal.authenticationType() == ANONYMOUS) throw new SecurityException("Authentication is required");
        if (endpoint.resourcePolicy() == GLOBAL_MANAGEMENT && !hasGlobalManagementScope(principal, managementTenantId)) {
            throw new SecurityException("Global management is restricted to the configured management tenant");
        }
        if (!store.isAllowed(principal.tenantId(), principal.identity(), endpoint.endpointKey())) {
            throw new SecurityException("Internal API permission denied: " + endpoint.endpointKey());
        }
    }

    public static boolean hasGlobalManagementScope(RequestPrincipal principal, String managementTenantId) {
        return principal != null && (principal.authenticationType() == ANONYMOUS
                || (principal.authenticationType() != ADMIN_BOOTSTRAP && managementTenantId.equals(principal.tenantId())));
    }

    public void requireTenant(RequestPrincipal manager, String tenantId) {
        if (!manager.tenantId().equals(tenantId)) throw new SecurityException("Cross-tenant permission management is forbidden");
    }

    public void replacePermissions(RequestPrincipal manager, String tenantId, String identity, Set<String> keys) {
        requireTenant(manager, tenantId);
        authorize(manager, routes.resolve("PUT", "/api/internal-api-permissions"));
        if (manager.authenticationType() != ADMIN_BOOTSTRAP && manager.identity().equals(identity)) {
            throw new SecurityException("Callers cannot edit their own identity permissions");
        }
        new RequestPrincipal(null, tenantId, identity, SERVICE_TOKEN);
        if (keys == null) throw new IllegalArgumentException("allowedEndpointKeys is required");
        Set<String> known = new java.util.HashSet<>();
        String cursor = null;
        do {
            var page = routes.page(cursor, 100);
            page.items().forEach(endpoint -> known.add(endpoint.endpointKey()));
            cursor = page.pageInfo().hasMore() ? page.pageInfo().nextCursor() : null;
        } while (cursor != null);
        if (!known.containsAll(keys)) throw new IllegalArgumentException("Unknown internal API endpoint keys");
        store.replace(tenantId, identity, Set.copyOf(keys));
    }
}
