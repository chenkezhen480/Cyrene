package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory tool-permission store. The real store's constructor only captures a connection
 * provider, so substituting it here needs no database.
 */
final class ToolPermissionStub extends ToolPermissionStore {

    private final Map<String, Set<String>> profiles = new LinkedHashMap<>();
    private boolean present = true;

    private ToolPermissionStub(boolean present) {
        super(() -> {
            throw new UnsupportedOperationException("no database in this test");
        }, new ObjectMapper());
        this.present = present;
    }

    /** A deployment whose database has no permission table yet. */
    static ToolPermissionStub absent() {
        return new ToolPermissionStub(false);
    }

    static ToolPermissionStub empty() {
        return new ToolPermissionStub(true);
    }

    ToolPermissionStub profile(String tenantId, String identity, String... disabledToolNames) {
        profiles.put(tenantId + "|" + identity, Set.of(disabledToolNames));
        return this;
    }

    ToolPermissionService service() {
        return new ToolPermissionService(this);
    }

    @Override
    boolean tablePresent() {
        return present;
    }

    @Override
    Optional<Set<String>> findDisabledTools(String tenantId, String identity) {
        return Optional.ofNullable(profiles.get(tenantId + "|" + identity));
    }
}
