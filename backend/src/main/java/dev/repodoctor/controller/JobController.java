package dev.repodoctor.controller;

import dev.repodoctor.llm.LLMClient;
import dev.repodoctor.model.*;
import dev.repodoctor.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

/**
 * REST API controller for RepoDoctor jobs.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobService jobService;
    private final AttemptRepository attemptRepository;
    private final ArtifactService artifactService;
    private final EventService eventService;
    private final LLMClient llmClient;

    public JobController(JobService jobService, AttemptRepository attemptRepository,
            ArtifactService artifactService, EventService eventService,
            LLMClient llmClient) {
        this.jobService = jobService;
        this.attemptRepository = attemptRepository;
        this.artifactService = artifactService;
        this.eventService = eventService;
        this.llmClient = llmClient;
    }

    /**
     * List recent jobs.
     */
    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<?> listJobs(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(defaultValue = "false") boolean all) {
        List<Job> jobs = jobService.getRecentJobs(sessionId, all);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Job job : jobs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", job.getId());
            item.put("repoName", job.getRepoName());
            item.put("status", job.getStatus());
            item.put("createdAt", job.getCreatedAt());
            item.put("completedAt", job.getCompletedAt());
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Delete a job.
     */
    @DeleteMapping("/{jobId}")
    public ResponseEntity<?> deleteJob(@PathVariable String jobId) {
        boolean deleted = jobService.deleteJob(jobId);
        if (deleted) {
            return ResponseEntity.ok(Map.of("success", true));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Create a new job from URL or ZIP upload.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createJob(
            @RequestParam(required = false) String repoUrl,
            @RequestParam(required = false) MultipartFile repoZip,
            @RequestParam(defaultValue = "1") int maxAttempts,
            @RequestParam(defaultValue = "false") boolean allowNetwork,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        try {
            Job job;

            if (repoZip != null && !repoZip.isEmpty()) {
                job = jobService.createJobFromZip(repoZip, maxAttempts, allowNetwork, sessionId);
            } else if (repoUrl != null && !repoUrl.isBlank()) {
                job = jobService.createJobFromUrl(repoUrl, maxAttempts, allowNetwork, sessionId);
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Either repoUrl or repoZip must be provided"));
            }

            return ResponseEntity.ok(Map.of(
                    "jobId", job.getId(),
                    "status", job.getStatus(),
                    "llmConfigured", llmClient.isConfigured()));

        } catch (IllegalArgumentException e) {
            log.warn("Invalid job request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Collections.singletonMap("error",
                            e.getMessage() != null ? e.getMessage() : "Invalid request"));
        } catch (IOException e) {
            log.error("IO error processing job", e);
            return ResponseEntity.internalServerError()
                    .body(Collections.singletonMap("error",
                            "Failed to process repository: " + (e.getMessage() != null ? e.getMessage() : "IO error")));
        } catch (Exception e) {
            log.error("Unexpected error in createJob", e);
            return ResponseEntity.internalServerError()
                    .body(Collections.singletonMap("error", "An unexpected error occurred: "
                            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
    }

    /**
     * Get job metadata and summary.
     */
    @GetMapping("/{jobId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<?> getJob(@PathVariable String jobId) {
        Job job = jobService.getJob(jobId);

        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", job.getId());
        response.put("repoUrl", job.getRepoUrl());
        response.put("repoName", job.getRepoName());
        response.put("status", job.getStatus());
        response.put("buildTool", job.getBuildTool());
        response.put("maxAttempts", job.getMaxAttempts());
        response.put("allowNetwork", job.isAllowNetwork());
        response.put("createdAt", job.getCreatedAt());
        response.put("completedAt", job.getCompletedAt());
        response.put("errorMessage", job.getErrorMessage());
        response.put("attemptCount", job.getAttempts().size());
        response.put("llmConfigured", llmClient.isConfigured());
        response.put("sessionId", job.getSessionId());

        // Add summary of final state
        if (!job.getAttempts().isEmpty()) {
            Attempt lastAttempt = job.getAttempts().get(job.getAttempts().size() - 1);
            Map<String, Object> finalAttempt = new LinkedHashMap<>();
            finalAttempt.put("attemptNumber", lastAttempt.getAttemptNumber());
            finalAttempt.put("status", lastAttempt.getStatus());
            finalAttempt.put("testsRun", lastAttempt.getTestsRun());
            finalAttempt.put("testsFailed", lastAttempt.getTestsFailed());
            finalAttempt.put("testsPassed", lastAttempt.getTestsPassed());
            response.put("finalAttempt", finalAttempt);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * SSE endpoint for real-time job events.
     */
    @GetMapping(value = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getJobEvents(@PathVariable String jobId) {
        return eventService.createEmitter(jobId);
    }

    /**
     * Get list of attempts for a job.
     */
    @GetMapping("/{jobId}/attempts")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<?> getAttempts(@PathVariable String jobId) {
        Job job = jobService.getJob(jobId);

        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> attempts = new ArrayList<>();
        for (Attempt attempt : job.getAttempts()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("attemptNumber", attempt.getAttemptNumber());
            a.put("status", attempt.getStatus());
            a.put("exitCode", attempt.getExitCode());
            a.put("testsRun", attempt.getTestsRun());
            a.put("testsFailed", attempt.getTestsFailed());
            a.put("testsPassed", attempt.getTestsPassed());
            a.put("startedAt", attempt.getStartedAt());
            a.put("completedAt", attempt.getCompletedAt());
            a.put("explanation", attempt.getExplanation());
            a.put("confidenceNotes", attempt.getConfidenceNotes());
            a.put("riskLevel", attempt.getRiskLevel());
            a.put("errorMessage", attempt.getErrorMessage());
            attempts.add(a);
        }

        return ResponseEntity.ok(attempts);
    }

    /**
     * Get diff for a specific attempt.
     */
    @GetMapping("/{jobId}/attempts/{attemptNumber}/diff")
    public ResponseEntity<String> getAttemptDiff(
            @PathVariable String jobId,
            @PathVariable int attemptNumber) {

        try {
            String diff = artifactService.getDiff(jobId, attemptNumber);
            if (diff.isEmpty() && attemptNumber == 0) {
                return ResponseEntity.ok("# Baseline - no patch applied");
            }
            return ResponseEntity.ok(diff);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get logs for a specific attempt.
     */
    @GetMapping("/{jobId}/attempts/{attemptNumber}/logs")
    public ResponseEntity<String> getAttemptLogs(
            @PathVariable String jobId,
            @PathVariable int attemptNumber) {

        try {
            String logs = artifactService.getLogs(jobId, attemptNumber);
            return ResponseEntity.ok(logs);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get summary for a specific attempt.
     */
    @GetMapping("/{jobId}/attempts/{attemptNumber}/summary")
    public ResponseEntity<?> getAttemptSummary(
            @PathVariable String jobId,
            @PathVariable int attemptNumber) {

        try {
            Map<String, Object> summary = artifactService.getSummary(jobId, attemptNumber);
            return ResponseEntity.ok(summary);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Health check endpoint (legacy path for backward compatibility).
     * @deprecated Use /api/health instead
     */
    @GetMapping("/health")
    @Deprecated
    public ResponseEntity<?> jobsHealth() {
        // Delegate to keep backward compatibility
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "llmConfigured", llmClient.isConfigured()));
    }
}
