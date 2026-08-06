package com.harness.input.multimodal.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScannedPageDetectorTest {

    private final ScannedPageDetector detector = new ScannedPageDetector(0.6);

    @Test
    void flagsPageWithFullPageImage() throws Exception {
        ExtractedDocument document = wrap(pdfWithImage(900, 700));

        assertThat(detector.detectScannedPages(document)).containsExactly(0);
    }

    @Test
    void doesNotFlagPageWithSmallImage() throws Exception {
        ExtractedDocument document = wrap(pdfWithImage(150, 120));

        assertThat(detector.detectScannedPages(document)).isEmpty();
    }

    @Test
    void doesNotFlagTextOnlyPage() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            pdf = output.toByteArray();
        }

        assertThat(detector.detectScannedPages(wrap(pdf))).isEmpty();
    }

    @Test
    void recursesIntoFormXObject() throws Exception {
        ExtractedDocument document = wrap(pdfWithFormImage(900, 700));

        assertThat(detector.detectScannedPages(document)).containsExactly(0);
    }

    @Test
    void ignoresNonPdfMimeType() throws Exception {
        ExtractedDocument document = new ExtractedDocument(
                "sample.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1, 2, 3}, List.of());

        assertThat(detector.detectScannedPages(document)).isEmpty();
    }

    private static ExtractedDocument wrap(byte[] pdf) {
        return new ExtractedDocument("scan.pdf", "application/pdf", pdf, List.of());
    }

    private static byte[] pdfWithImage(int imageWidth, int imageHeight) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDImageXObject image = PDImageXObject.createFromByteArray(
                    document, pngBytes(imageWidth, imageHeight), "page-image");
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] pdfWithFormImage(int imageWidth, int imageHeight) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDImageXObject image = PDImageXObject.createFromByteArray(
                    document, pngBytes(imageWidth, imageHeight), "form-image");
            PDFormXObject form = new PDFormXObject(document);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("Im1"), image);
            form.setResources(resources);
            try (OutputStream content = form.getContentStream().createOutputStream()) {
                content.write("/Im1 Do".getBytes(StandardCharsets.US_ASCII));
            }
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawForm(form);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(220, 220, 220));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
