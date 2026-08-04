package com.harness.tool.knowledge;

public record ResolvedDocumentText(
        String text,
        int repairedBlockCount,
        String repairModel
) {
    public ResolvedDocumentText {
        text = text != null ? text : "";
        repairModel = repairModel != null ? repairModel : "";
    }
}
