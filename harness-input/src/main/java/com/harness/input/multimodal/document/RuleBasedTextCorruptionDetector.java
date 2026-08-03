package com.harness.input.multimodal.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic corruption detector; no OCR or model call is used here.
 */
public final class RuleBasedTextCorruptionDetector implements TextCorruptionDetector {

    private static final List<String> MOJIBAKE_MARKERS = List.of(
            "ï¿½", "â€™", "â€œ", "â€", "Ã", "Â");
    private final double corruptionThreshold;

    public RuleBasedTextCorruptionDetector(double corruptionThreshold) {
        if (corruptionThreshold <= 0 || corruptionThreshold > 1) {
            throw new IllegalArgumentException("corruptionThreshold must be in range (0, 1]");
        }
        this.corruptionThreshold = corruptionThreshold;
    }

    @Override
    public List<CorruptionFinding> detect(List<DocumentBlock> blocks) {
        List<CorruptionFinding> findings = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            Score score = score(block.text(), block.visualOnly());
            if (score.value() >= corruptionThreshold) {
                findings.add(new CorruptionFinding(block.blockId(), score.value(), score.reasons()));
            }
        }
        return findings;
    }

    @Override
    public boolean isCorrupted(String text) {
        return score(text, false).value() >= corruptionThreshold;
    }

    private static Score score(String text, boolean visualOnly) {
        if (visualOnly || text == null || text.isBlank()) {
            return visualOnly
                    ? new Score(1.0, List.of("visual-only block has no native text"))
                    : new Score(0.0, List.of());
        }

        int codePoints = Math.max(text.codePointCount(0, text.length()), 1);
        int replacement = 0;
        int controls = 0;
        int privateOrUnassigned = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == 0xFFFD) {
                replacement++;
            }
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL && codePoint != '\n' && codePoint != '\r' && codePoint != '\t') {
                controls++;
            }
            if (type == Character.PRIVATE_USE || type == Character.UNASSIGNED) {
                privateOrUnassigned++;
            }
        }

        List<String> reasons = new ArrayList<>();
        double score = 0;
        if (replacement > 0) {
            score += Math.min(0.8, 0.35 + replacement * 1.0 / codePoints * 4);
            reasons.add("replacement characters");
        }
        if (controls > 0) {
            score += Math.min(0.5, controls * 1.0 / codePoints * 5);
            reasons.add("unexpected control characters");
        }
        if (privateOrUnassigned > 0) {
            score += Math.min(0.7, privateOrUnassigned * 1.0 / codePoints * 4);
            reasons.add("private or unassigned characters");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        long markerCount = MOJIBAKE_MARKERS.stream()
                .filter(marker -> lower.contains(marker.toLowerCase(Locale.ROOT)))
                .count();
        if (markerCount > 0) {
            score += Math.min(0.75, markerCount * 0.35);
            reasons.add("common mojibake markers");
        }
        return new Score(Math.min(score, 1.0), List.copyOf(reasons));
    }

    private record Score(double value, List<String> reasons) {
    }
}
