package com.harness.input.multimodal.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedTextCorruptionDetectorTest {

    private final RuleBasedTextCorruptionDetector detector =
            new RuleBasedTextCorruptionDetector(0.45);

    @Test
    void doesNotFlagNormalMultilingualTextOrCode() {
        List<DocumentBlock> blocks = List.of(new DocumentBlock(
                "normal", 0, 0,
                "正常中文 English 123 https://example.com UUID 123e4567-e89b-12d3-a456-426614174000",
                false));

        assertThat(detector.detect(blocks)).isEmpty();
    }

    @Test
    void flagsReplacementAndPrivateUseCharacters() {
        String text = "标题���" + new String(Character.toChars(0xE000));
        List<CorruptionFinding> findings = detector.detect(List.of(
                new DocumentBlock("bad", 0, 0, text, false)));

        assertThat(findings).singleElement()
                .satisfies(finding -> assertThat(finding.blockId()).isEqualTo("bad"));
    }

    @Test
    void flagsVisualOnlyBlock() {
        assertThat(detector.detect(List.of(
                new DocumentBlock("scan", 0, 0, "", true))))
                .singleElement();
    }
}
