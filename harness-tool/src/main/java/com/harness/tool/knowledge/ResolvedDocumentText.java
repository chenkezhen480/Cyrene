package com.harness.tool.knowledge;

public record ResolvedDocumentText(
        String text,
        int repairedBlockCount,
        String repairModel,
        int ocrBlockCount,
        String ocrModel
) {
    public ResolvedDocumentText {
        text = text != null ? text : "";
        repairModel = repairModel != null ? repairModel : "";
        ocrModel = ocrModel != null ? ocrModel : "";
    }

    public ResolvedDocumentText(String text, int repairedBlockCount, String repairModel) {
        this(text, repairedBlockCount, repairModel, 0, "");
    }
}
