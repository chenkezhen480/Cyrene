package com.harness.input.multimodal.document;

public interface StructuredDocumentExtractor {

    boolean supports(String mimeType);

    ExtractedDocument extract(byte[] data, String fileName, String mimeType) throws Exception;
}
