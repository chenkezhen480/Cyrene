package com.harness.input.multimodal;

/**
 * Extracts plain text from binary file data based on MIME type.
 */
public interface TextExtractor {

    boolean supports(String mimeType);

    String extract(byte[] data, String mimeType) throws Exception;
}
