package com.harness.input.multimodal.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public final class PdfPptxDocumentRegionRenderer implements DocumentRegionRenderer {

    private final float pdfDpi;
    private final double pptScale;

    public PdfPptxDocumentRegionRenderer(float pdfDpi, double pptScale) {
        if (pdfDpi <= 0 || pptScale <= 0) {
            throw new IllegalArgumentException("render DPI and scale must be positive");
        }
        this.pdfDpi = pdfDpi;
        this.pptScale = pptScale;
    }

    @Override
    public boolean supports(String mimeType) {
        return PdfStructuredDocumentExtractor.MIME_TYPE.equalsIgnoreCase(mimeType)
                || PptxStructuredDocumentExtractor.MIME_TYPE.equalsIgnoreCase(mimeType);
    }

    @Override
    public RenderedDocumentRegion render(ExtractedDocument document, int pageIndex) {
        if (!supports(document.mimeType())) {
            throw new IllegalArgumentException(
                    "Document rendering is not supported for MIME type: " + document.mimeType());
        }
        try {
            BufferedImage image = PdfStructuredDocumentExtractor.MIME_TYPE.equalsIgnoreCase(document.mimeType())
                    ? renderPdfPage(document.sourceData(), pageIndex)
                    : renderPptxSlide(document.sourceData(), pageIndex);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG image writer is unavailable");
            }
            return new RenderedDocumentRegion(pageIndex, output.toByteArray(), "image/png");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to render document page " + (pageIndex + 1), e);
        }
    }

    private BufferedImage renderPdfPage(byte[] sourceData, int pageIndex) throws Exception {
        try (var document = Loader.loadPDF(sourceData)) {
            if (pageIndex >= document.getNumberOfPages()) {
                throw new IllegalArgumentException("PDF page index is out of range: " + pageIndex);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            return renderer.renderImageWithDPI(pageIndex, pdfDpi, ImageType.RGB);
        }
    }

    private BufferedImage renderPptxSlide(byte[] sourceData, int pageIndex) throws Exception {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(sourceData))) {
            if (pageIndex >= slideShow.getSlides().size()) {
                throw new IllegalArgumentException("PPTX slide index is out of range: " + pageIndex);
            }
            Dimension pageSize = slideShow.getPageSize();
            int width = Math.max(1, (int) Math.ceil(pageSize.getWidth() * pptScale));
            int height = Math.max(1, (int) Math.ceil(pageSize.getHeight() * pptScale));
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(
                        RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.scale(pptScale, pptScale);
                slideShow.getSlides().get(pageIndex).draw(graphics);
            } finally {
                graphics.dispose();
            }
            return image;
        }
    }
}
