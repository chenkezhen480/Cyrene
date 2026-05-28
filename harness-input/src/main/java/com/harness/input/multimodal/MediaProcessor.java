package com.harness.input.multimodal;

import java.util.Map;

/**
 * Processes media files (video, audio) to extract text content.
 * Reserved for future implementation.
 */
public interface MediaProcessor {

    boolean supports(String mimeType);

    ProcessingResult process(byte[] data, String fileName, String mimeType);

    record ProcessingResult(String extractedText, Map<String, Object> metadata) {}
}
