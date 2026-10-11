package com.harness.input;

import com.harness.input.multimodal.MultimodalParser;

import java.util.List;

/** Stable input boundary for authentication, validation, and content parsing. */
public interface InputStage {

    default ProcessedInput processAuthenticated(com.harness.core.security.RequestPrincipal principal,
                                              String text, List<MultimodalParser.RawAttachment> attachments) {
        throw new UnsupportedOperationException("Input stage does not support verified console requests");
    }

    ProcessedInput process(
            String token,
            String text,
            List<MultimodalParser.RawAttachment> attachments,
            String contextUserId
    );
}
