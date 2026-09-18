package com.harness.server;

import com.harness.core.model.AgentContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolPermissionServiceTest {

    private static final String TENANT = "100001";

    @Test
    void identityComesFromTheRequestAndDefaultsToDefault() {
        ToolPermissionService service = ToolPermissionStub.empty().service();

        assertThat(service.identityOf(context(Map.of(AgentContext.KEY_IDENTITY, "teacher"))))
                .isEqualTo("teacher");
        assertThat(service.identityOf(context(Map.of())))
                .isEqualTo(AgentContext.DEFAULT_IDENTITY);
        assertThat(service.identityOf(context(Map.of(AgentContext.KEY_IDENTITY, "  "))))
                .isEqualTo(AgentContext.DEFAULT_IDENTITY);
    }

    @Test
    void tenantFallsBackToTheStandaloneDefault() {
        ToolPermissionService service = ToolPermissionStub.empty().service();

        assertThat(service.tenantOf(context(Map.of()))).isEqualTo("000000");
        assertThat(service.tenantOf(context(Map.of(AgentContext.KEY_TENANT_ID, "100001"))))
                .isEqualTo("100001");
    }

    @Test
    void aStoredListDisablesExactlyThoseTools() {
        ToolPermissionService service =
                ToolPermissionStub.empty().profile(TENANT, "teacher", "image_generation").service();

        AgentContext resolved = service.apply(context(Map.of(
                AgentContext.KEY_TENANT_ID, TENANT,
                AgentContext.KEY_IDENTITY, "teacher")));

        assertThat(resolved.toolDenylist()).containsExactly("image_generation");
    }

    /**
     * Profiles outlive tool names. A row written before the code tools were merged says {@code edit},
     * which no longer matches a registered tool — leaving it alone would hand that tenant back the
     * write access it had explicitly been denied.
     *
     * <p>It collapses to the tool rather than to one action so the admin page, which manages one
     * entry per tool, reports the same thing the runtime enforces.</p>
     */
    @Test
    void rewritesPreMergeCodeToolNamesToTheToolThatNowCarriesThem() {
        ToolPermissionService service = ToolPermissionStub.empty()
                .profile(TENANT, "teacher", "edit", "write", "image_generation")
                .service();

        assertThat(service.resolveDisabledTools(TENANT, "teacher").orElseThrow())
                .containsExactlyInAnyOrder("code_workspace", "image_generation");
    }

    @Test
    void rewritesTheNamesOnTheDefaultRowToo() {
        ToolPermissionService service = ToolPermissionStub.empty()
                .profile(TENANT, AgentContext.DEFAULT_IDENTITY, "read")
                .service();

        assertThat(service.resolveDisabledTools(TENANT, "unknown-identity").orElseThrow())
                .containsExactly("code_workspace");
    }

    @Test
    void aStoredEmptyListDisablesNothing() {
        // The documented meaning of an empty list: nothing is banned, so every tool stays on.
        ToolPermissionService service =
                ToolPermissionStub.empty().profile(TENANT, "guest").service();

        AgentContext resolved = service.apply(context(Map.of(
                AgentContext.KEY_TENANT_ID, TENANT,
                AgentContext.KEY_IDENTITY, "guest")));

        assertThat(resolved.toolDenylist()).isEmpty();
        assertThat(resolved.data()).doesNotContainKey(AgentContext.KEY_TOOL_DENYLIST);
    }

    @Test
    void anUnknownIdentityFallsBackToTheTenantsDefaultRow() {
        ToolPermissionService service =
                ToolPermissionStub.empty()
                        .profile(TENANT, "DEFAULT", "python_sandbox", "file_write")
                        .service();

        assertThat(service.resolveDisabledTools(TENANT, "principal"))
                .contains(Set.of("python_sandbox", "file_write"));
    }

    @Test
    void anExactRowWinsOverTheDefaultRow() {
        ToolPermissionService service =
                ToolPermissionStub.empty()
                        .profile(TENANT, "DEFAULT", "python_sandbox")
                        .profile(TENANT, "teacher", "image_generation")
                        .service();

        assertThat(service.resolveDisabledTools(TENANT, "teacher"))
                .contains(Set.of("image_generation"));
    }

    @Test
    void aBlankTenantResolvesTheStandaloneDefaultInsteadOfMatchingNothing() {
        // The detached-resume turn passes the session row's tenant, which is null in a standalone
        // deployment. Unnormalized it would match no row and hand the resumed turn every tool
        // the identity had disabled.
        ToolPermissionService service =
                ToolPermissionStub.empty()
                        .profile("000000", "DEFAULT", "image_generation")
                        .service();

        assertThat(service.resolveDisabledTools(null, "DEFAULT"))
                .contains(Set.of("image_generation"));
        assertThat(service.resolveDisabledTools("  ", "DEFAULT"))
                .contains(Set.of("image_generation"));
    }

    @Test
    void aTenantWithoutAnyRowStaysUnrestricted() {
        ToolPermissionService service = ToolPermissionStub.empty().service();
        AgentContext context = context(Map.of(AgentContext.KEY_TENANT_ID, TENANT));

        assertThat(service.apply(context)).isSameAs(context);
    }

    @Test
    void aDeploymentWithoutThePermissionTableIsLeftAlone() {
        ToolPermissionService service = ToolPermissionStub.absent().service();
        AgentContext context = context(Map.of(AgentContext.KEY_TENANT_ID, TENANT));

        assertThat(service.apply(context)).isSameAs(context);
        assertThat(service.resolveDisabledTools(TENANT, "teacher")).isEmpty();
    }

    @Test
    void rejectsAnIdentityTooLongToBeAStoredProfileKey() {
        ToolPermissionService service = ToolPermissionStub.empty().service();

        assertThatThrownBy(() -> service.identityOf(
                context(Map.of(AgentContext.KEY_IDENTITY, "x".repeat(129)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
    }

    private static AgentContext context(Map<String, Object> data) {
        return AgentContext.of(data);
    }
}
