package com.harness.env;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvConfigTest {

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of(
                "HARNESS_TEST_STRING", "hello",
                "HARNESS_TEST_INT", "42",
                "HARNESS_TEST_LONG", "1234567890",
                "HARNESS_TEST_DOUBLE", "3.14",
                "HARNESS_TEST_BOOL", "true",
                "HARNESS_TEST_LIST", "a, b, c",
                "HARNESS_TEST_BLANK", "  "
        ));
    }

    @Test
    void getString_returnsValue() {
        assertThat(EnvConfig.get().getString("HARNESS_TEST_STRING", "")).isEqualTo("hello");
    }

    @Test
    void getString_missingKey_returnsDefault() {
        assertThat(EnvConfig.get().getString("HARNESS_NONEXISTENT", "fallback")).isEqualTo("fallback");
    }

    @Test
    void getInt_parsesInteger() {
        assertThat(EnvConfig.get().getInt("HARNESS_TEST_INT", 0)).isEqualTo(42);
    }

    @Test
    void getInt_missingKey_returnsDefault() {
        assertThat(EnvConfig.get().getInt("HARNESS_NONEXISTENT", 99)).isEqualTo(99);
    }

    @Test
    void getInt_blankValue_returnsDefault() {
        assertThat(EnvConfig.get().getInt("HARNESS_TEST_BLANK", 7)).isEqualTo(7);
    }

    @Test
    void getLong_parsesLong() {
        assertThat(EnvConfig.get().getLong("HARNESS_TEST_LONG", 0L)).isEqualTo(1234567890L);
    }

    @Test
    void getLong_missingKey_returnsDefault() {
        assertThat(EnvConfig.get().getLong("HARNESS_NONEXISTENT", -1L)).isEqualTo(-1L);
    }

    @Test
    void getDouble_parsesDouble() {
        assertThat(EnvConfig.get().getDouble("HARNESS_TEST_DOUBLE", 0.0)).isCloseTo(3.14, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void getDouble_missingKey_returnsDefault() {
        assertThat(EnvConfig.get().getDouble("HARNESS_NONEXISTENT", 1.0)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void getBool_parsesTrue() {
        assertThat(EnvConfig.get().getBool("HARNESS_TEST_BOOL", false)).isTrue();
    }

    @Test
    void getBool_missingKey_returnsDefault() {
        assertThat(EnvConfig.get().getBool("HARNESS_NONEXISTENT", true)).isTrue();
    }

    @Test
    void getCommaList_splitsAndTrims() {
        assertThat(EnvConfig.get().getCommaList("HARNESS_TEST_LIST"))
                .containsExactly("a", "b", "c");
    }

    @Test
    void getCommaList_missingKey_returnsEmptyList() {
        assertThat(EnvConfig.get().getCommaList("HARNESS_NONEXISTENT")).isEmpty();
    }

    @Test
    void requireString_presentKey_returnsValue() {
        assertThat(EnvConfig.get().requireString("HARNESS_TEST_STRING")).isEqualTo("hello");
    }

    @Test
    void requireString_missingKey_throwsIllegalState() {
        assertThatThrownBy(() -> EnvConfig.get().requireString("HARNESS_NONEXISTENT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required env var not set");
    }

    @Test
    void requireString_blankValue_throwsIllegalState() {
        assertThatThrownBy(() -> EnvConfig.get().requireString("HARNESS_TEST_BLANK"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void set_overridesValue() {
        EnvConfig.get().set("HARNESS_TEST_STRING", "overridden");
        assertThat(EnvConfig.get().getString("HARNESS_TEST_STRING", "")).isEqualTo("overridden");
    }

    @Test
    void all_returnsUnmodifiableMap() {
        var all = EnvConfig.get().all();
        assertThat(all).isNotEmpty();
        assertThatThrownBy(() -> all.put("key", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void init_withNull_createsEmptyConfig() {
        EnvConfig.init(null);
        assertThat(EnvConfig.get().getString("any-key", "default")).isEqualTo("default");
    }
}
