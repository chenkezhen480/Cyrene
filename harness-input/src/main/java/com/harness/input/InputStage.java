package com.harness.input;

import com.harness.input.multimodal.MultimodalParser;

import java.util.List;

/** Stable input boundary for authentication, validation, and content parsing. */
public interface InputStage {

    ProcessedInput process(
            String token,
            String text,
            List<MultimodalParser.RawAttachment> attachments,
            String contextUserId
    );
}
