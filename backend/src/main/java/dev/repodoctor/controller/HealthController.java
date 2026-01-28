package dev.repodoctor.controller;

import dev.repodoctor.llm.LLMClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check endpoint for monitoring and wake-up detection.
 * Used by frontend to detect Render free tier cold starts and verify backend availability.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final LLMClient llmClient;

    public HealthController(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Health check endpoint.
     * Returns 200 OK when the backend is fully operational.
     *
     * This endpoint is used by the frontend to:
     * 1. Detect when Render free tier is sleeping (connection timeout/503)
     * 2. Wait for backend to wake up (retry with exponential backoff)
     * 3. Verify backend is ready before submitting jobs
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "healthy");
        response.put("timestamp", Instant.now().toString());
        response.put("llmConfigured", llmClient.isConfigured());

        return ResponseEntity.ok(response);
    }
}
