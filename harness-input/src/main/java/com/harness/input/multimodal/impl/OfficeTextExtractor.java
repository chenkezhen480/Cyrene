package com.harness.input.multimodal.impl;

import com.harness.input.multimodal.TextExtractor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

public class OfficeTextExtractor implements TextExtractor {

    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOC_MIME = "application/msword";

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) return false;
        String m = mimeType.toLowerCase();
        return m.equals(DOCX_MIME) || m.equals(XLSX_MIME) || m.equals(DOC_MIME);
    }

    @Override
    public String extract(byte[] data, String mimeType) throws Exception {
        String m = mimeType != null ? mimeType.toLowerCase() : "";
        if (m.equals(XLSX_MIME)) {
            return extractXlsx(data);
        }
        if (m.equals(DOC_MIME)) {
            return extractDoc(data);
        }
        return extractDocx(data);
    }

    private String extractDocx(byte[] data) throws Exception {
        List<String> paragraphs = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    paragraphs.add(text);
                }
            }
        }
        return String.join("\n\n", paragraphs);
    }

    private String extractDoc(byte[] data) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(data));
             WordExtractor extractor = new WordExtractor(doc)) {
            String[] paragraphs = extractor.getParagraphText();
            StringBuilder sb = new StringBuilder();
            for (String p : paragraphs) {
                String text = cleanWordText(p);
                if (!text.isEmpty()) {
                    sb.append(text).append("\n\n");
                }
            }
            return sb.toString().trim();
        }
    }

    /**
     * Clean Word field codes, control characters, and formatting artifacts from .doc text.
     * HWPF getParagraphText() returns raw text including HYPERLINK, PAGEREF, TOC field codes.
     */
    private String cleanWordText(String raw) {
        if (raw == null) return "";
        String text = raw;
        // Remove Word field code blocks: \x13 ... \x14 (field start/end markers)
        text = text.replaceAll("[^]*", "");
        // Remove HYPERLINK field remnants
        text = text.replaceAll("HYPERLINK\\s+\\\\l\\s+\"[^\"]*\"", "");
        text = text.replaceAll("HYPERLINK\\s+\"[^\"]*\"", "");
        // Remove PAGEREF field remnants
        text = text.replaceAll("PAGEREF\\s+\\S+\\s+\\\\h\\s*", "");
        // Remove TOC field patterns like: TOC \o "1-3" \h \z
        text = text.replaceAll("TOC\\s+\\\\[o-z]\\s+\"[^\"]*\"(\\s+\\\\[a-z]\\s*)*", "");
        // Remove standalone backslash escape sequences (Word formatting codes)
        text = text.replaceAll("\\\\[a-z]\\b", "");
        // Remove control characters (0x00-0x1F) except normal whitespace (tab, newline)
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        // Collapse multiple whitespace/newlines
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    private String extractXlsx(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                XSSFSheet sheet = workbook.getSheetAt(s);
                sb.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    XSSFRow row = sheet.getRow(r);
                    if (row == null) continue;
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        XSSFCell cell = row.getCell(c);
                        cells.add(cell != null ? cell.toString() : "");
                    }
                    sb.append(String.join("\t", cells)).append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
