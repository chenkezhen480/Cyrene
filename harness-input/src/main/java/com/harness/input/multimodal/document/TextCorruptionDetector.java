package com.harness.input.multimodal.document;

import java.util.List;

public interface TextCorruptionDetector {

    List<CorruptionFinding> detect(List<DocumentBlock> blocks);

    boolean isCorrupted(String text);
}
