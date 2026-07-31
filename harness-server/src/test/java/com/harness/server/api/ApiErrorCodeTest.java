package com.harness.server.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorCodeTest {

    @Test
    void mapsHttpStatusToStableErrorCode() {
        assertThat(ApiErrorCode.fromHttpStatus(400))
                .isEqualTo(ApiErrorCode.INVALID_REQUEST);
        assertThat(ApiErrorCode.fromHttpStatus(401))
                .isEqualTo(ApiErrorCode.UNAUTHORIZED);
        assertThat(ApiErrorCode.fromHttpStatus(403))
                .isEqualTo(ApiErrorCode.FORBIDDEN);
        assertThat(ApiErrorCode.fromHttpStatus(404))
                .isEqualTo(ApiErrorCode.NOT_FOUND);
        assertThat(ApiErrorCode.fromHttpStatus(409))
                .isEqualTo(ApiErrorCode.CONFLICT);
        assertThat(ApiErrorCode.fromHttpStatus(500))
                .isEqualTo(ApiErrorCode.INTERNAL_ERROR);
    }
}
