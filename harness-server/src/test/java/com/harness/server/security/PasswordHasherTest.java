package com.harness.server.security;

import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher(new SecureRandom());

    @Test void saltsNewPasswordsAndRejectsWrongPasswordsAndMalformedHashes() {
        String hash = hasher.hash("密码 with spaces");
        assertThat(hash).startsWith("pbkdf2-sha256$600000$").isNotEqualTo(hasher.hash("密码 with spaces"));
        assertThat(hasher.matches("密码 with spaces", hash)).isTrue();
        assertThat(hasher.matches("wrong", hash)).isFalse();
        assertThat(hasher.matches("test", "pbkdf2-sha256$999999999$bad$bad")).isFalse();
        assertThat(hasher.matches("test", "pbkdf2-sha256$bad$bad$bad")).isFalse();
    }

    @Test void readsExistingSha256PasswordsWithoutCreatingMoreLegacyHashes() {
        String legacy = "937e8d5fbb48bd4949536cd65b8d35c426b80d2f830c5c308e2cdec422ae2244";
        assertThat(hasher.matches("test1234", legacy)).isTrue();
        assertThat(hasher.matches("wrong", legacy)).isFalse();
    }
}
