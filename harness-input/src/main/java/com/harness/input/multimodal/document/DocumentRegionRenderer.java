package com.harness.input.multimodal.document;

public interface DocumentRegionRenderer {

    boolean supports(String mimeType);

    RenderedDocumentRegion render(ExtractedDocument document, int pageIndex);
}
