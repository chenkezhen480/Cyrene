package com.harness.server.security;

import com.harness.core.security.ApiEndpointDescriptor;
import com.harness.core.security.RequestPrincipal;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.harness.core.security.ApiEndpointDescriptor.ResourcePolicy.*;
import static com.harness.core.security.RequestPrincipal.AuthenticationType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalApiAuthorizationTest {
    @Test
    void absenceDeniesAndRevocationTakesEffectOnTheNextRequest() {
        var store = mock(MysqlInternalApiPermissionStore.class);
        var routes = new InternalApiRouteRegistry();
        var service = new InternalApiPermissionService(store, routes, true);
        var principal = new RequestPrincipal("u1", "t1", "reader", JWT);
        var endpoint = endpoint("trace.read", "GET", "/api/trace/{id}", TRACE);
        when(store.isAllowed("t1", "reader", "trace.read")).thenReturn(false, true, false);
        assertThatThrownBy(() -> service.authorize(principal, endpoint)).isInstanceOf(SecurityException.class);
        assertThatCode(() -> service.authorize(principal, endpoint)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.authorize(principal, endpoint)).isInstanceOf(SecurityException.class);
    }

    @Test
    void bootstrapIsRestrictedEvenWhenAuthorizationIsDisabled() {
        var service = new InternalApiPermissionService(mock(MysqlInternalApiPermissionStore.class),
                new InternalApiRouteRegistry(), false);
        var principal = new RequestPrincipal(null, "t1", "bootstrap", ADMIN_BOOTSTRAP);
        assertThatThrownBy(() -> service.authorize(principal,
                endpoint("chat.create", "POST", "/api/chat", USER))).isInstanceOf(SecurityException.class);
    }

    @Test
    void permissionEditorsCannotEscalateTheirOwnIdentityOrAnotherTenant() {
        var store = mock(MysqlInternalApiPermissionStore.class);
        var routes = new InternalApiRouteRegistry();
        routes.register(endpoint("internalApiPermission.update", "PUT", "/api/internal-api-permissions", BOOTSTRAP));
        var service = new InternalApiPermissionService(store, routes, true);
        var manager = new RequestPrincipal("u1", "t1", "manager", JWT);
        when(store.isAllowed("t1", "manager", "internalApiPermission.update")).thenReturn(true);
        assertThatThrownBy(() -> service.replacePermissions(manager, "t1", "manager", Set.of()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.replacePermissions(manager, "t2", "reader", Set.of()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.replacePermissions(manager, "t1", "reader", Set.of("unknown")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void globalManagementGrantsCannotExposeOtherTenantData() {
        var store = mock(MysqlInternalApiPermissionStore.class);
        var service = new InternalApiPermissionService(store, new InternalApiRouteRegistry(), true, "management");
        when(store.isAllowed("tenant-1", "manager", "knowledge.read")).thenReturn(true);
        var endpoint = endpoint("knowledge.read", "GET", "/api/knowledge/{collection}/{id}", GLOBAL_MANAGEMENT);
        assertThatThrownBy(() -> service.authorize(new RequestPrincipal("u1", "tenant-1", "manager", JWT), endpoint))
                .isInstanceOf(SecurityException.class);
        when(store.isAllowed("management", "manager", "knowledge.read")).thenReturn(true);
        assertThatCode(() -> service.authorize(new RequestPrincipal("u1", "management", "manager", JWT), endpoint))
                .doesNotThrowAnyException();
    }

    @Test
    void storeFailureNeverBecomesAnAllowDecision() {
        var store = new MysqlInternalApiPermissionStore(() -> { throw new java.sql.SQLException("unavailable"); });
        var service = new InternalApiPermissionService(store, new InternalApiRouteRegistry(), true);
        assertThatThrownBy(() -> service.authorize(new RequestPrincipal("u1", "t1", "reader", JWT),
                endpoint("trace.read", "GET", "/api/trace/{id}", TRACE)))
                .isInstanceOf(MysqlInternalApiPermissionStore.PermissionStoreException.class);
    }

    private static ApiEndpointDescriptor endpoint(String key, String method, String path,
                                                   ApiEndpointDescriptor.ResourcePolicy policy) {
        return new ApiEndpointDescriptor(key, key, method, path, "test", policy);
    }
}
