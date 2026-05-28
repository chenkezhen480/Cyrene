package com.harness.ai.model.impl;

import com.harness.ai.model.VisionModelProvider;
import dev.langchain4j.data.image.Image;
import java.util.List;

public class NoOpVisionModelProvider implements VisionModelProvider {
    @Override public String analyze(String prompt, Image image) {
        throw new UnsupportedOperationException("Vision model not configured. Set HARNESS_MODEL_VISION_PROVIDER.");
    }
    @Override public String analyze(String prompt, List<Image> images) {
        throw new UnsupportedOperationException("Vision model not configured. Set HARNESS_MODEL_VISION_PROVIDER.");
    }
    @Override public boolean isAvailable() { return false; }
    @Override public String providerName() { return "none"; }
    @Override public String modelName() { return "none"; }
}
