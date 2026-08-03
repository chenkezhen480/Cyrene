package com.harness.input.multimodal.document;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

public final class PptxStructuredDocumentExtractor implements StructuredDocumentExtractor {

    public static final String MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    @Override
    public boolean supports(String mimeType) {
        return MIME_TYPE.equalsIgnoreCase(mimeType);
    }

    @Override
    public ExtractedDocument extract(byte[] data, String fileName, String mimeType) throws Exception {
        List<DocumentBlock> blocks = new ArrayList<>();
        int order = 0;
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(data))) {
            for (int slideIndex = 0; slideIndex < slideShow.getSlides().size(); slideIndex++) {
                int slideBlockStart = blocks.size();
                int shapeIndex = 0;
                for (XSLFShape shape : slideShow.getSlides().get(slideIndex).getShapes()) {
                    String text = extractShapeText(shape);
                    if (!text.isBlank()) {
                        blocks.add(new DocumentBlock(
                                "pptx-slide-" + (slideIndex + 1) + "-shape-" + shapeIndex,
                                slideIndex,
                                order++,
                                text,
                                false));
                    }
                    shapeIndex++;
                }
                if (blocks.size() == slideBlockStart) {
                    blocks.add(new DocumentBlock(
                            "pptx-slide-" + (slideIndex + 1) + "-visual",
                            slideIndex,
                            order++,
                            "",
                            true));
                }
            }
        }
        return new ExtractedDocument(fileName, MIME_TYPE, data, blocks);
    }

    private static String extractShapeText(XSLFShape shape) {
        if (shape instanceof XSLFTextShape textShape) {
            return textShape.getText() != null ? textShape.getText().trim() : "";
        }
        if (shape instanceof XSLFTable table) {
            List<String> rows = new ArrayList<>();
            for (XSLFTableRow row : table.getRows()) {
                List<String> cells = new ArrayList<>();
                for (XSLFTableCell cell : row.getCells()) {
                    cells.add(cell.getText() != null ? cell.getText().trim() : "");
                }
                rows.add(String.join("\t", cells));
            }
            return String.join("\n", rows).trim();
        }
        return "";
    }
}
