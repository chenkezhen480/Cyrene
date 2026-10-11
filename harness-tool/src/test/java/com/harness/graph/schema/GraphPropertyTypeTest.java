package com.harness.graph.schema;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Values;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphPropertyTypeTest {
    @Test
    void integerAcceptsNeo4jLongValuesOnlyWithinSigned32BitRange() {
        for (long value : List.of((long) Integer.MIN_VALUE, -1L, 0L, 10L, (long) Integer.MAX_VALUE)) {
            Object readBack = Values.value(value).asObject();
            assertThat(readBack).isInstanceOf(Long.class);
            assertThat(GraphPropertyType.INTEGER.accepts(readBack)).as("INTEGER accepts %s", value).isTrue();
        }
        for (var value : List.of((byte) 1, (short) 1, 1)) {
            assertThat(GraphPropertyType.INTEGER.accepts(value)).isTrue();
        }
    }

    @Test
    void integerRejects64BitValuesOutsideItsDeclaredRange() {
        for (long value : List.of((long) Integer.MIN_VALUE - 1, (long) Integer.MAX_VALUE + 1,
                Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertThat(GraphPropertyType.INTEGER.accepts(Values.value(value).asObject()))
                    .as("INTEGER rejects %s", value).isFalse();
        }
    }

    @Test
    void longAcceptsFullSigned64BitRangeAndNarrowerBoxedIntegers() {
        for (var value : List.of(Long.MIN_VALUE, Long.MAX_VALUE, (long) Integer.MIN_VALUE - 1,
                (long) Integer.MAX_VALUE + 1, (byte) 1, (short) 1, 1)) {
            assertThat(GraphPropertyType.LONG.accepts(value)).as("LONG accepts %s", value).isTrue();
        }
    }

    @Test
    void integerTypesRejectFloatingPointTextAndArbitraryPrecisionValues() {
        for (var type : List.of(GraphPropertyType.INTEGER, GraphPropertyType.LONG)) {
            for (var value : List.of(10.0, 10.0f, "10", new BigDecimal("10"), BigInteger.TEN,
                    BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE),
                    BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))) {
                assertThat(type.accepts(value)).as("%s rejects %s", type, value.getClass()).isFalse();
            }
            assertThat(type.accepts(null)).isFalse();
        }
    }
}
