package com.harness.server;

import com.harness.core.model.AgentContext;
import com.harness.tool.filesystem.CodeWorkspaceTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves which tools one caller may use.
 *
 * <p>Identity is resolved from the request itself: {@code context.identity}, defaulting to
 * {@link AgentContext#DEFAULT_IDENTITY}. The resulting (tenantId, identity) pair selects a
 * stored profile, falling back to the tenant's {@code DEFAULT} row.</p>
 *
 * <p>The stored list names the tools this identity may not use, so an empty list is not a
 * restriction: both a stored empty list and an absent row leave every registered tool
 * available. The feature is opt-in — a tenant only loses tools once someone disables some for
 * it — and an un-migrated database behaves exactly like an unconfigured one.</p>
 */
public final class ToolPermissionService {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionService.class);

    private final ToolPermissionStore store;
    private volatile Boolean tablePresent;

    public ToolPermissionService() {
        this(new ToolPermissionStore());
    }

    ToolPermissionService(ToolPermissionStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Apply the caller's stored tool permission to a request context.
     *
     * @return the same context, with the resolved denylist attached when one applies
     */
    public AgentContext apply(AgentContext context) {
        Objects.requireNonNull(context, "context");
        if (!tablePresent()) {
            return context;
        }
        String tenantId = tenantOf(context);
        String identity = identityOf(context);
        return resolveDisabledTools(tenantId, identity)
                .filter(disabled -> !disabled.isEmpty())
                .map(context::withToolDenylist)
                .orElse(context);
    }

    /** The identity a request resolves to; the tenant + identity pair selects the profile. */
    public String identityOf(AgentContext context) {
        Object value = context.data().get(AgentContext.KEY_IDENTITY);
        if (value == null || value.toString().isBlank()) {
            return AgentContext.DEFAULT_IDENTITY;
        }
        String identity = value.toString().trim();
        if (identity.length() > 128) {
            throw new IllegalArgumentException(
                    "identity must not exceed 128 characters");
        }
        return identity;
    }

    /** The tenant a request resolves to. */
    public String tenantOf(AgentContext context) {
        return context.optionalTenantId().orElse(AgentContext.DEFAULT_TENANT_ID);
    }

    public ToolPermissionStore store() {
        return store;
    }

    public boolean tablePresent() {
        Boolean known = tablePresent;
        if (known == null) {
            synchronized (this) {
                known = tablePresent;
                if (known == null) {
                    known = store.tablePresent();
                    tablePresent = known;
                    log.info("[ToolPermission] {} present={}; {}",
                            ToolPermissionStore.PROFILE_TABLE,
                            known,
                            known
                                    ? "tenant + identity tool filtering is active"
                                    : "table missing, every tool stays available for every tenant");
                }
            }
        }
        return known;
    }

    /**
     * The tools disabled for one tenant and identity, falling back to the tenant's DEFAULT row.
     * Empty means nothing is disabled, whether that comes from an empty stored list or no row.
     *
     * <p>A blank tenant is normalized to {@link AgentContext#DEFAULT_TENANT_ID} here, not only in
     * {@link #apply}: the detached-resume resolver calls this with the session row's tenant, which
     * is null in a standalone deployment, and an unnormalized null would match nothing and hand
     * the resumed turn every tool its identity had disabled.</p>
     *
     * <p>Guarded by {@link #tablePresent()} because that same resume path would otherwise abort on
     * a missing table rather than simply leaving tools unfiltered.</p>
     */
    public Optional<Set<String>> resolveDisabledTools(String tenantId, String identity) {
        if (!tablePresent()) {
            return Optional.empty();
        }
        String tenant = tenantId == null || tenantId.isBlank()
                ? AgentContext.DEFAULT_TENANT_ID
                : tenantId.trim();
        String scopedIdentity = identity == null || identity.isBlank()
                ? AgentContext.DEFAULT_IDENTITY
                : identity.trim();
        Optional<Set<String>> exact = store.findDisabledTools(tenant, scopedIdentity);
        if (exact.isPresent()) {
            return exact.map(ToolPermissionService::modernize);
        }
        if (!AgentContext.DEFAULT_IDENTITY.equals(scopedIdentity)) {
            return store.findDisabledTools(tenant, AgentContext.DEFAULT_IDENTITY)
                    .map(ToolPermissionService::modernize);
        }
        return Optional.empty();
    }

    /**
     * Rewrite the pre-merge code-tool names in a stored profile to the tool that now carries them.
     *
     * <p>Profiles written before the six file tools were merged key on {@code read} / {@code edit} /
     * {@code write}. Those names match no registered tool, so leaving them alone would silently hand
     * a tenant that had disabled writes a tool that can write — the opposite of the fail-closed
     * direction this list exists for. Applied on both lookups so the exact and DEFAULT rows agree.</p>
     *
     * <p>They collapse to the whole tool, not to one action: the admin page manages permissions one
     * name per tool, and a {@code code_workspace.edit} it can neither show nor re-save would leave
     * the page claiming a tenant is unrestricted while its requests are filtered.</p>
     */
    private static Set<String> modernize(Set<String> disabledTools) {
        return disabledTools.stream()
                .map(CodeWorkspaceTool::ownerOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
