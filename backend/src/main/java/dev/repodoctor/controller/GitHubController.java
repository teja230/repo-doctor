package dev.repodoctor.controller;

import dev.repodoctor.config.GitHubConfig;
import dev.repodoctor.service.GitHubService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * REST controller for GitHub OAuth and PR creation.
 *
 * Endpoints:
 * - GET /api/github/status - Check if GitHub integration is enabled
 * - GET /api/github/authorize - Start OAuth flow (redirect to GitHub)
 * - GET /api/github/callback - OAuth callback handler
 * - POST /api/github/pr - Create PR with access token
 */
@RestController
@RequestMapping("/api/github")
public class GitHubController {

        private static final Logger log = LoggerFactory.getLogger(GitHubController.class);

        private final GitHubService gitHubService;
        private final GitHubConfig gitHubConfig;

        public GitHubController(GitHubService gitHubService, GitHubConfig gitHubConfig) {
                this.gitHubService = gitHubService;
                this.gitHubConfig = gitHubConfig;
        }

        /**
         * Check if GitHub integration is enabled and configured
         */
        @GetMapping("/status")
        public ResponseEntity<Map<String, Object>> getStatus() {
                boolean enabled = gitHubService.isEnabled();
                return ResponseEntity.ok(Map.of(
                                "enabled", enabled,
                                "configured", gitHubConfig.isConfigured(),
                                "clientIdSet",
                                gitHubConfig.getClientId() != null && !gitHubConfig.getClientId().isBlank()));
        }

        /**
         * Start OAuth authorization flow.
         *
         * Returns the GitHub authorization URL that the frontend should redirect to.
         */
        @GetMapping("/authorize")
        public ResponseEntity<Map<String, String>> authorize(
                        @RequestParam String jobId,
                        @RequestParam int attemptNumber) {

                if (!gitHubService.isEnabled()) {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                        .body(Map.of("error", "GitHub integration is not configured"));
                }

                try {
                        String authUrl = gitHubService.getAuthorizationUrl(jobId, attemptNumber);
                        return ResponseEntity.ok(Map.of("authUrl", authUrl));
                } catch (Exception e) {
                        log.error("Failed to generate authorization URL", e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(Map.of("error",
                                                        "Failed to generate authorization URL: " + e.getMessage()));
                }
        }

        /**
         * OAuth callback handler.
         *
         * This is called by GitHub after user authorizes the app.
         * It exchanges the code for an access token, prepares the PR (creates branch,
         * pushes changes),
         * and redirects to GitHub's native PR creation form with pre-filled data.
         */
        @GetMapping("/callback")
        public ResponseEntity<Void> callback(
                        @RequestParam String code,
                        @RequestParam String state) {

                String jobId = null;
                try {
                        // Exchange code for token
                        GitHubService.OAuthResult result = gitHubService.exchangeCodeForToken(code, state);
                        jobId = result.jobId(); // Store for error handling

                        // Prepare the PR (create branch, push changes) but don't create it via API yet
                        GitHubService.PullRequestResult prep = gitHubService.preparePullRequest(
                                        result.accessToken(), result.jobId(), result.attemptNumber());

                        // Redirect to GitHub's native PR creation form with pre-filled data
                        String githubCompareUrl = String.format(
                                        "https://github.com/%s/%s/compare/%s...%s?expand=1&title=%s&body=%s",
                                        prep.owner(),
                                        prep.repo(),
                                        prep.baseBranch(),
                                        prep.branch(),
                                        URLEncoder.encode(prep.title(), StandardCharsets.UTF_8),
                                        URLEncoder.encode(prep.body(), StandardCharsets.UTF_8));

                        return ResponseEntity.status(HttpStatus.FOUND)
                                        .location(URI.create(githubCompareUrl))
                                        .build();

                } catch (IllegalStateException e) {
                        // User doesn't have write access to the repository
                        log.warn("PR creation failed - insufficient permissions: {}", e.getMessage());

                        String jobIdParam = jobId != null ? "&jobId=" + URLEncoder.encode(jobId, StandardCharsets.UTF_8)
                                        : "";
                        String errorUrl = String.format("%s/error?type=NO_WRITE_ACCESS&message=%s%s",
                                        gitHubConfig.getFrontendUrl(),
                                        URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8),
                                        jobIdParam);
                        return ResponseEntity.status(HttpStatus.FOUND)
                                        .location(URI.create(errorUrl))
                                        .build();

                } catch (IllegalArgumentException e) {
                        log.error("OAuth callback validation failed", e);

                        String jobIdParam = jobId != null ? "&jobId=" + URLEncoder.encode(jobId, StandardCharsets.UTF_8)
                                        : "";
                        String errorUrl = String.format("%s/error?type=VALIDATION_ERROR&message=%s%s",
                                        gitHubConfig.getFrontendUrl(),
                                        URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8),
                                        jobIdParam);
                        return ResponseEntity.status(HttpStatus.FOUND)
                                        .location(URI.create(errorUrl))
                                        .build();

                } catch (IOException | InterruptedException e) {
                        log.error("OAuth callback failed", e);

                        String jobIdParam = jobId != null ? "&jobId=" + URLEncoder.encode(jobId, StandardCharsets.UTF_8)
                                        : "";
                        String errorUrl = String.format("%s/error?type=API_ERROR&message=%s%s",
                                        gitHubConfig.getFrontendUrl(),
                                        URLEncoder.encode("Failed to prepare PR: " + e.getMessage(),
                                                        StandardCharsets.UTF_8),
                                        jobIdParam);
                        return ResponseEntity.status(HttpStatus.FOUND)
                                        .location(URI.create(errorUrl))
                                        .build();
                }
        }

        /**
         * Create PR using an existing access token.
         *
         * This endpoint is used when the frontend already has a valid token
         * (e.g., from a previous OAuth flow stored in localStorage).
         */
        @PostMapping("/pr")
        public ResponseEntity<Map<String, Object>> createPullRequest(
                        @RequestBody CreatePRRequest request) {

                if (!gitHubService.isEnabled()) {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                        .body(Map.of("error", "GitHub integration is not configured"));
                }

                if (request.accessToken == null || request.accessToken.isBlank()) {
                        return ResponseEntity.badRequest()
                                        .body(Map.of("error", "Access token is required"));
                }

                try {
                        GitHubService.PullRequestResult result = gitHubService.createPullRequest(
                                        request.accessToken, request.jobId, request.attemptNumber);

                        return ResponseEntity.ok(Map.of(
                                        "success", true,
                                        "url", result.url(),
                                        "title", result.title(),
                                        "branch", result.branch()));

                } catch (IllegalArgumentException e) {
                        log.error("PR creation failed - invalid request", e);
                        return ResponseEntity.badRequest()
                                        .body(Map.of("error", e.getMessage()));

                } catch (IOException | InterruptedException e) {
                        log.error("PR creation failed", e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(Map.of("error", "Failed to create PR: " + e.getMessage()));
                }
        }

        // Request DTOs
        public record CreatePRRequest(
                        String accessToken,
                        String jobId,
                        int attemptNumber) {
        }
}
