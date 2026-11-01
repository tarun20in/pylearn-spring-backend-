package com.pylearn.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class VideoVisualizationService {

    private static final Logger log = LoggerFactory.getLogger(VideoVisualizationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Path videoStorageDir = Paths.get("data", "videos");
    private final Path scriptPath = Paths.get("scripts", "render_code_video.py");

    public VideoVisualizationService() {
        try {
            Files.createDirectories(videoStorageDir);
        } catch (Exception e) {
            log.error("Failed to create video storage directory: {}", e.getMessage());
        }
    }

    public Map<String, Object> generateVideoVisualization(String pythonCode, String title) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (pythonCode == null || pythonCode.trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "Python code cannot be empty.");
            return response;
        }

        String videoId = "vid_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path tempCodeFile = null;
        Path targetMp4Path = videoStorageDir.resolve(videoId + ".mp4");

        try {
            tempCodeFile = Files.createTempFile("pylearn_code_", ".py");
            Files.writeString(tempCodeFile, pythonCode, StandardCharsets.UTF_8);

            String displayTitle = (title != null && !title.isBlank()) ? title : "main.py";

            ProcessBuilder pb = new ProcessBuilder(
                    "python",
                    scriptPath.toAbsolutePath().toString(),
                    "--code-file", tempCodeFile.toAbsolutePath().toString(),
                    "--output", targetMp4Path.toAbsolutePath().toString(),
                    "--title", displayTitle
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder outputBuf = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuf.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                response.put("success", false);
                response.put("error", "Video rendering timed out after 45 seconds.");
                return response;
            }

            String fullOutput = outputBuf.toString();
            String prefix = "PYLEARN_VIDEO_RESULT:";
            int prefixIdx = fullOutput.indexOf(prefix);

            if (prefixIdx != -1) {
                String jsonStr = fullOutput.substring(prefixIdx + prefix.length()).trim();
                JsonNode root = objectMapper.readTree(jsonStr);

                response.put("success", true);
                response.put("videoId", videoId);
                response.put("videoUrl", "/api/ide/videos/" + videoId + ".mp4");
                response.put("stepsCount", root.path("stepsCount").asInt(0));
                response.put("durationSeconds", root.path("durationSeconds").asDouble(0.0));
                response.put("fileSizeBytes", root.path("fileSizeBytes").asLong(0L));
                return response;
            } else {
                log.warn("Video script finished without expected prefix. Output: {}", fullOutput);
                if (Files.exists(targetMp4Path)) {
                    response.put("success", true);
                    response.put("videoId", videoId);
                    response.put("videoUrl", "/api/ide/videos/" + videoId + ".mp4");
                    response.put("fileSizeBytes", Files.size(targetMp4Path));
                    return response;
                }
                response.put("success", false);
                response.put("error", "Failed to parse video result: " + fullOutput);
                return response;
            }

        } catch (Exception e) {
            log.error("Exception during video visualization generation", e);
            response.put("success", false);
            response.put("error", "Internal error generating video: " + e.getMessage());
            return response;
        } finally {
            if (tempCodeFile != null) {
                try {
                    Files.deleteIfExists(tempCodeFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public File getVideoFile(String videoId) {
        // Sanitize videoId to prevent path traversal
        String safeName = videoId.replaceAll("[^a-zA-Z0-9_]", "") + ".mp4";
        Path path = videoStorageDir.resolve(safeName);
        File file = path.toFile();
        if (file.exists() && file.isFile()) {
            return file;
        }
        return null;
    }
}
