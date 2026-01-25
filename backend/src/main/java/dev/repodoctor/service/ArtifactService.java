package dev.repodoctor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repodoctor.config.RepoDoctorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/**
 * Service for storing and retrieving job artifacts.
 * Artifacts include logs, diffs, and JSON summaries per attempt.
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final Path artifactsRoot;
    private final ObjectMapper objectMapper;

    public ArtifactService(RepoDoctorConfig config, ObjectMapper objectMapper) {
        this.artifactsRoot = Path.of(config.getArtifactsPath());
        this.objectMapper = objectMapper;

        try {
            Files.createDirectories(artifactsRoot);
        } catch (IOException e) {
            log.error("Failed to create artifacts directory", e);
        }
    }

    public Path getAttemptDir(String jobId, int attemptNumber) {
        return artifactsRoot.resolve(jobId).resolve("attempt-" + attemptNumber);
    }

    public void saveLogs(String jobId, int attemptNumber, String logs) throws IOException {
        Path dir = getAttemptDir(jobId, attemptNumber);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("logs.txt"), logs);
    }

    public String getLogs(String jobId, int attemptNumber) throws IOException {
        Path file = getAttemptDir(jobId, attemptNumber).resolve("logs.txt");
        if (Files.exists(file)) {
            return Files.readString(file);
        }
        return "";
    }

    public void saveDiff(String jobId, int attemptNumber, String diff) throws IOException {
        Path dir = getAttemptDir(jobId, attemptNumber);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("patch.diff"), diff);
    }

    public String getDiff(String jobId, int attemptNumber) throws IOException {
        Path file = getAttemptDir(jobId, attemptNumber).resolve("patch.diff");
        if (Files.exists(file)) {
            return Files.readString(file);
        }
        return "";
    }

    public void saveSummary(String jobId, int attemptNumber, Map<String, Object> summary) throws IOException {
        Path dir = getAttemptDir(jobId, attemptNumber);
        Files.createDirectories(dir);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("summary.json").toFile(), summary);
    }

    public Map<String, Object> getSummary(String jobId, int attemptNumber) throws IOException {
        Path file = getAttemptDir(jobId, attemptNumber).resolve("summary.json");
        if (Files.exists(file)) {
            return objectMapper.readValue(file.toFile(), Map.class);
        }
        return Map.of();
    }

    public void saveExplanation(String jobId, int attemptNumber, Map<String, Object> explanation) throws IOException {
        Path dir = getAttemptDir(jobId, attemptNumber);
        Files.createDirectories(dir);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("explanation.json").toFile(), explanation);
    }

    public Map<String, Object> getExplanation(String jobId, int attemptNumber) throws IOException {
        Path file = getAttemptDir(jobId, attemptNumber).resolve("explanation.json");
        if (Files.exists(file)) {
            return objectMapper.readValue(file.toFile(), Map.class);
        }
        return Map.of();
    }

    public void cleanup(String jobId) {
        try {
            Path jobDir = artifactsRoot.resolve(jobId);
            if (Files.exists(jobDir)) {
                Files.walk(jobDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                log.warn("Failed to delete: {}", path);
                            }
                        });
            }
        } catch (IOException e) {
            log.error("Failed to cleanup artifacts for job: {}", jobId, e);
        }
    }
}
