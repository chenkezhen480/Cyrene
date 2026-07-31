package com.harness.tool.web;

import com.harness.core.exception.ToolExecutionException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlSafetyPolicyTest {

    @Test
    void rejectsPrivateAndCredentialBearingUrls() {
        UrlSafetyPolicy policy = new UrlSafetyPolicy(false, List.of());

        assertThatThrownBy(() -> policy.validate("http://127.0.0.1/admin", "test"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("Private or local");
        assertThatThrownBy(() -> policy.validate(
                "https://user:password@example.com/", "test"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("embedded credentials");
    }

    @Test
    void rejectsConfiguredDomainAndItsSubdomains() {
        UrlSafetyPolicy policy = new UrlSafetyPolicy(
                true, List.of("blocked.example"));

        assertThatThrownBy(() -> policy.validate(
                "https://api.blocked.example/path", "test"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("blocked by policy");
    }

    @Test
    void permitsPrivateAddressOnlyWhenExplicitlyConfigured() {
        UrlSafetyPolicy policy = new UrlSafetyPolicy(true, List.of());

        assertThat(policy.validate("http://127.0.0.1:8080/path", "test").getHost())
                .isEqualTo("127.0.0.1");
    }
}
