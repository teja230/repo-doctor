package dev.repodoctor.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service for Server-Sent Events (SSE) broadcasting.
 * Allows real-time updates to connected clients.
 */
@Service
public class EventService {

    private final Map<String, List<SseEmitter>> jobEmitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(String jobId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        jobEmitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // Send immediate ping
        try {
            emitter.send(SseEmitter.event().name("ping").data("connected"));
        } catch (IOException e) {
            // ignore
        }

        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(e -> removeEmitter(jobId, emitter));

        return emitter;
    }

    private void removeEmitter(String jobId, SseEmitter emitter) {
        List<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                jobEmitters.remove(jobId);
            }
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 5000)
    public void sendHeartbeat() {
        jobEmitters.forEach((jobId, emitters) -> {
            List<SseEmitter> deadEmitters = new ArrayList<>();
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("ping").data("pong"));
                } catch (IOException e) {
                    deadEmitters.add(emitter);
                }
            }
            emitters.removeAll(deadEmitters);
        });
    }

    public void emit(String jobId, String eventType, Object data) {
        List<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", eventType);
        payload.put("timestamp", Instant.now().toString());
        payload.put("data", data);

        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventType)
                        .data(payload));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);
    }

    public void emitJobStarted(String jobId, String repoName) {
        emit(jobId, "job_started", Map.of(
                "jobId", jobId,
                "repoName", repoName));
    }

    public void emitAttemptStarted(String jobId, int attemptNumber, boolean isBaseline) {
        emit(jobId, "attempt_started", Map.of(
                "attemptNumber", attemptNumber,
                "isBaseline", isBaseline));
    }

    public void emitRunCompleted(String jobId, int attemptNumber, int exitCode, boolean testsPass) {
        emit(jobId, "run_completed", Map.of(
                "attemptNumber", attemptNumber,
                "exitCode", exitCode,
                "testsPass", testsPass));
    }

    public void emitPatchProposed(String jobId, int attemptNumber, String riskLevel, List<String> touchedFiles) {
        emit(jobId, "patch_proposed", Map.of(
                "attemptNumber", attemptNumber,
                "riskLevel", riskLevel,
                "touchedFiles", touchedFiles));
    }

    public void emitPatchApplied(String jobId, int attemptNumber, boolean success) {
        emit(jobId, "patch_applied", Map.of(
                "attemptNumber", attemptNumber,
                "success", success));
    }

    public void emitRateLimitWait(String jobId, int attemptNumber, int seconds) {
        emit(jobId, "rate_limit_wait", Map.of(
                "attemptNumber", attemptNumber,
                "seconds", seconds));
    }

    public void emitAttemptCompleted(String jobId, int attemptNumber, String status, String message) {
        emit(jobId, "attempt_completed", Map.of(
                "attemptNumber", attemptNumber,
                "status", status,
                "message", message));
    }

    public void emitJobCompleted(String jobId, boolean success, int totalAttempts, String summary) {
        emit(jobId, "job_completed", Map.of(
                "success", success,
                "totalAttempts", totalAttempts,
                "summary", summary));

        // Close all emitters for this job
        List<SseEmitter> emitters = jobEmitters.remove(jobId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                emitter.complete();
            }
        }
    }

    public void emitError(String jobId, String error) {
        emit(jobId, "error", Map.of(
                "error", error));
    }
}
