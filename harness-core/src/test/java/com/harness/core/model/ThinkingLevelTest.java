package com.harness.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThinkingLevelTest {

    @Test
    void parse_acceptsAllFiveStopsIgnoringCase() {
        assertThat(ThinkingLevel.parse("off")).isEqualTo(ThinkingLevel.OFF);
        assertThat(ThinkingLevel.parse("LOW")).isEqualTo(ThinkingLevel.LOW);
        assertThat(ThinkingLevel.parse(" Medium ")).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(ThinkingLevel.parse("high")).isEqualTo(ThinkingLevel.HIGH);
        assertThat(ThinkingLevel.parse("xhigh")).isEqualTo(ThinkingLevel.XHIGH);
    }

    @Test
    void parse_rejectsUnknownValueWithAllowedList() {
        assertThatThrownBy(() -> ThinkingLevel.parse("sometimes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("off, low, medium, high, xhigh");
        assertThatThrownBy(() -> ThinkingLevel.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThinkingLevel.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseNullable_lenientForRequestLevel() {
        assertThat(ThinkingLevel.parseNullable(null)).isNull();
        assertThat(ThinkingLevel.parseNullable("  ")).isNull();
        assertThat(ThinkingLevel.parseNullable("nope")).isNull();
        assertThat(ThinkingLevel.parseNullable("xhigh")).isEqualTo(ThinkingLevel.XHIGH);
    }

    @Test
    void ordinalOrder_matchesSliderStops_forClamping() {
        assertThat(ThinkingLevel.OFF.ordinal()).isLessThan(ThinkingLevel.LOW.ordinal());
        assertThat(ThinkingLevel.LOW.ordinal()).isLessThan(ThinkingLevel.MEDIUM.ordinal());
        assertThat(ThinkingLevel.MEDIUM.ordinal()).isLessThan(ThinkingLevel.HIGH.ordinal());
        assertThat(ThinkingLevel.HIGH.ordinal()).isLessThan(ThinkingLevel.XHIGH.ordinal());
    }
}
