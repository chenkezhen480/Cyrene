package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolSpec;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Built-in FFmpeg tool for audio/video processing.
 * Configured via HARNESS_TOOL_FFMPEG_* environment variables.
 */
public class FfmpegTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FfmpegTool.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final String ffmpegPath;

    public FfmpegTool() {
        this.ffmpegPath = EnvConfig.get().getString(EnvKey.TOOL_FFMPEG_PATH, "ffmpeg");
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "ffmpeg",
                "Execute FFmpeg commands for audio/video processing",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("args",
                                                mapper.createObjectNode().put("type", "string").put("description", "FFmpeg arguments")))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                                mapper.createArrayNode().add("args"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String args = arguments.has("args") ? arguments.get("args").asText() : null;
        if (args == null || args.isBlank()) {
            throw new ToolExecutionException("ffmpeg", "Missing required parameter: args");
        }

        log.info("FFmpeg: {} {}", ffmpegPath, args);

        try {
            // Build command: ffmpegPath + user-provided args
            List<String> command = new ArrayList<>();
            Collections.addAll(command, ffmpegPath.split(" "));
            Collections.addAll(command, args.split(" "));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ToolExecutionException("ffmpeg", "Exit code " + exitCode + ": " + output);
            }

            return output.toString();
        } catch (IOException | InterruptedException e) {
            throw new ToolExecutionException("ffmpeg", "Execution failed: " + e.getMessage(), e);
        }
    }
}
