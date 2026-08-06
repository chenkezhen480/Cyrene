package com.harness.input.multimodal.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.PDResources;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects scanned PDF pages: pages whose content is essentially one or more
 * large embedded raster images (typical of scanned documents), even when a thin
 * hidden text layer carries only watermark or metadata.
 *
 * A page is flagged when its single largest content image covers at least
 * {@code imageAreaThreshold} of the page media box, measured as image pixel
 * area against page point area. Full-page scans (>= 100 DPI) exceed the ratio
 * by a wide margin, while headers or small photos do not.
 */
public final class ScannedPageDetector {

    private final double imageAreaThreshold;

    public ScannedPageDetector(double imageAreaThreshold) {
        if (imageAreaThreshold <= 0 || imageAreaThreshold > 1) {
            throw new IllegalArgumentException("imageAreaThreshold must be in range (0, 1]");
        }
        this.imageAreaThreshold = imageAreaThreshold;
    }

    /**
     * @return 0-based page indices that look like scanned pages; empty for non-PDF.
     */
    public List<Integer> detectScannedPages(ExtractedDocument document) {
        if (!PdfStructuredDocumentExtractor.MIME_TYPE.equalsIgnoreCase(document.mimeType())) {
            return List.of();
        }
        try (PDDocument pdf = Loader.loadPDF(document.sourceData())) {
            List<Integer> scannedPages = new ArrayList<>();
            for (int pageIndex = 0; pageIndex < pdf.getNumberOfPages(); pageIndex++) {
                PDPage page = pdf.getPage(pageIndex);
                PDRectangle mediaBox = page.getMediaBox();
                double pageArea = mediaBox.getWidth() * mediaBox.getHeight();
                if (pageArea <= 0) {
                    continue;
                }
                long largestImagePixelArea = maxContentImagePixelArea(
                        page.getResources(), new HashSet<>());

                if (largestImagePixelArea >= imageAreaThreshold * pageArea) {
                    scannedPages.add(pageIndex);
                }
            }
            return List.copyOf(scannedPages);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to detect scanned pages in PDF", e);
        }
    }

    private static long maxContentImagePixelArea(
            PDResources resources,
            Set<COSStream> visitedForms
    ) throws Exception {
        if (resources == null) {
            return 0;
        }
        long maxArea = 0;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDImageXObject image) {
                if (!isContentImage(image)) {
                    continue;
                }
                long area = (long) image.getWidth() * image.getHeight();
                if (area > maxArea) {
                    maxArea = area;
                }
            } else if (xObject instanceof PDFormXObject form) {
                COSStream formStream = form.getCOSObject();
                if (visitedForms.add(formStream)) {
                    maxArea = Math.max(maxArea,
                            maxContentImagePixelArea(form.getResources(), visitedForms));
                }
            }
        }
        return maxArea;
    }

    private static boolean isContentImage(PDImageXObject image) {
        if (image.isStencil()) {
            return false;
        }
        COSDictionary dict = image.getCOSObject();
        return !dict.containsKey(COSName.SMASK);
    }
}
