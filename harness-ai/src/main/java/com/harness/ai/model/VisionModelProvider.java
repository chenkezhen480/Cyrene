package com.harness.ai.model;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;

/**
 * 2. Vision Model Provider
 * Handles: image recognition, video frame analysis, OCR.
 * Some providers support vision natively (GPT-4o, Claude); others need a separate model.
 */
public interface VisionModelProvider {

    /**
     * Analyze an image with a text prompt.
     *
     * @param prompt text instruction
     * @param image  the image to analyze
     * @return model response
     */
    String analyze(String prompt, Image image);

    /**
     * Analyze multiple images with a text prompt.
     *
     * @param prompt text instruction
     * @param images list of images
     * @return model response
     */
    String analyze(String prompt, List<Image> images);

    /**
     * Check if this provider supports vision.
     * If false, the harness should fall back to text-only description.
     */
    boolean isAvailable();

    String providerName();
    String modelName();
}
