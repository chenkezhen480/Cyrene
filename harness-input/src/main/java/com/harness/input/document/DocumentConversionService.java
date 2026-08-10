package com.harness.input.document;

/**
 * Converts an uploaded document into canonical Markdown.
 *
 * <p>Implementations are injected into document consumers so knowledge ingestion,
 * chat attachments and context files share the same conversion boundary.</p>
 */
public interface DocumentConversionService {

    DocumentConversionResult convert(DocumentConversionRequest request);

    default DocumentConversionResult convert(
            byte[] fileData,
            String fileName,
            String mimeType
    ) {
        return convert(new DocumentConversionRequest(fileData, fileName, mimeType));
    }
}
