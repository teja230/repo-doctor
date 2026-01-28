package dev.repodoctor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repodoctor.config.GitHubConfig;
import dev.repodoctor.model.Attempt;
import dev.repodoctor.model.Job;
import dev.repodoctor.model.AttemptRepository;
import dev.repodoctor.model.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for GitHub API integration.
 *
 * Handles:
 * - OAuth flow (authorization URL generation, token exchange)
 * - Repository operations (create branch, create PR)
 * - Content operations (update files via GitHub API)
 */
@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String GITHUB_AUTH_URL = "https://github.com/login/oauth/authorize";
    private static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";

    private final GitHubConfig gitHubConfig;
    private final JobRepository jobRepository;
    private final AttemptRepository attemptRepository;
    private final ArtifactService artifactService;
    private final PullRequestGenerator prGenerator;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // In-memory state store for OAuth (in production, use Redis or similar)
    private final Map<String, OAuthState> oauthStates = new java.util.concurrent.ConcurrentHashMap<>();

    public GitHubService(GitHubConfig gitHubConfig, JobRepository jobRepository,
                         AttemptRepository attemptRepository, ArtifactService artifactService,
                         PullRequestGenerator prGenerator, ObjectMapper objectMapper) {
        this.gitHubConfig = gitHubConfig;
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.artifactService = artifactService;
        this.prGenerator = prGenerator;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Check if GitHub PR creation feature is enabled and configured
     */
    public boolean isEnabled() {
        return gitHubConfig.isConfigured();
    }

    /**
     * Generate OAuth authorization URL
     *
     * @param jobId The job ID to associate with this OAuth flow
     * @return Authorization URL to redirect user to
     */
    public String getAuthorizationUrl(String jobId, int attemptNumber) {
        if (!isEnabled()) {
            throw new IllegalStateException("GitHub integration is not configured");
        }

        // Generate state token for CSRF protection
        String state = UUID.randomUUID().toString();
        oauthStates.put(state, new OAuthState(jobId, attemptNumber, System.currentTimeMillis()));

        // Clean up old states (older than 10 minutes)
        long cutoff = System.currentTimeMillis() - (10 * 60 * 1000);
        oauthStates.entrySet().removeIf(entry -> entry.getValue().timestamp < cutoff);

        return String.format("%s?client_id=%s&redirect_uri=%s&scope=%s&state=%s",
                GITHUB_AUTH_URL,
                URLEncoder.encode(gitHubConfig.getClientId(), StandardCharsets.UTF_8),
                URLEncoder.encode(gitHubConfig.getCallbackUrl(), StandardCharsets.UTF_8),
                URLEncoder.encode(gitHubConfig.getScopes(), StandardCharsets.UTF_8),
                URLEncoder.encode(state, StandardCharsets.UTF_8));
    }

    /**
     * Exchange authorization code for access token
     *
     * @param code The authorization code from GitHub callback
     * @param state The state parameter for CSRF validation
     * @return OAuthResult containing token and job info
     */
    public OAuthResult exchangeCodeForToken(String code, String state) throws IOException, InterruptedException {
        // Validate state
        OAuthState oauthState = oauthStates.remove(state);
        if (oauthState == null) {
            throw new IllegalArgumentException("Invalid or expired state parameter");
        }

        // Exchange code for token
        String requestBody = String.format("client_id=%s&client_secret=%s&code=%s&redirect_uri=%s",
                URLEncoder.encode(gitHubConfig.getClientId(), StandardCharsets.UTF_8),
                URLEncoder.encode(gitHubConfig.getClientSecret(), StandardCharsets.UTF_8),
                URLEncoder.encode(code, StandardCharsets.UTF_8),
                URLEncoder.encode(gitHubConfig.getCallbackUrl(), StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("GitHub token exchange failed: {} - {}", response.statusCode(), response.body());
            throw new IOException("Failed to exchange code for token: " + response.statusCode());
        }

        JsonNode jsonResponse = objectMapper.readTree(response.body());

        if (jsonResponse.has("error")) {
            throw new IOException("GitHub OAuth error: " + jsonResponse.get("error_description").asText());
        }

        String accessToken = jsonResponse.get("access_token").asText();

        return new OAuthResult(accessToken, oauthState.jobId, oauthState.attemptNumber);
    }

    /**
     * Prepare a pull request (create branch, push changes) without creating the PR via API.
     * This allows redirecting users to GitHub's native PR creation form.
     *
     * @param accessToken GitHub access token
     * @param jobId The job ID
     * @param attemptNumber The attempt number to create PR from
     * @return PullRequestResult with url=null (PR not created yet)
     */
    public PullRequestResult preparePullRequest(String accessToken, String jobId, int attemptNumber)
            throws IOException, InterruptedException {

        // Fetch job and attempts
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        Attempt successfulAttempt = attemptRepository.findByJob_IdAndAttemptNumber(jobId, attemptNumber)
                .orElseThrow(() -> new IllegalArgumentException("Attempt not found: " + attemptNumber));

        Attempt baselineAttempt = attemptRepository.findByJob_IdAndAttemptNumber(jobId, 0)
                .orElseThrow(() -> new IllegalArgumentException("Baseline attempt not found"));

        List<Attempt> allAttempts = attemptRepository.findByJob_IdOrderByAttemptNumberAsc(jobId);

        // Get the patch content
        String patchContent;
        try {
            patchContent = artifactService.getDiff(jobId, attemptNumber);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read patch for attempt " + attemptNumber, e);
        }
        if (patchContent == null || patchContent.isBlank()) {
            throw new IllegalStateException("No patch found for attempt " + attemptNumber);
        }

        // Parse repo owner/name from URL
        RepoInfo repoInfo = parseRepoUrl(job.getRepoUrl());

        // Generate PR content
        String prTitle = prGenerator.generateTitle(job, successfulAttempt, baselineAttempt);
        String prBody = prGenerator.generateDescription(job, successfulAttempt, baselineAttempt, allAttempts, patchContent);

        // Get default branch
        String defaultBranch = getDefaultBranch(accessToken, repoInfo.owner, repoInfo.name);

        // Get the latest commit SHA from default branch
        String baseSha = getLatestCommitSha(accessToken, repoInfo.owner, repoInfo.name, defaultBranch);

        // Create a new branch
        String branchName = String.format("repodoctor/fix-%s-%d", jobId.substring(0, 8), attemptNumber);
        createBranch(accessToken, repoInfo.owner, repoInfo.name, branchName, baseSha);

        // Apply the patch by creating/updating files
        applyPatchToGitHub(accessToken, repoInfo.owner, repoInfo.name, branchName, patchContent, prTitle);

        // Return result without PR URL (not created via API yet)
        return new PullRequestResult(null, prTitle, branchName, repoInfo.owner, repoInfo.name, prBody, defaultBranch);
    }

    /**
     * Create a pull request for the specified job and attempt.
     * This method prepares the PR (branch + changes) and then creates it via GitHub API.
     *
     * @param accessToken GitHub access token
     * @param jobId The job ID
     * @param attemptNumber The attempt number to create PR from
     * @return PullRequestResult containing PR URL and details
     */
    public PullRequestResult createPullRequest(String accessToken, String jobId, int attemptNumber)
            throws IOException, InterruptedException {

        // Prepare the PR (create branch, push changes)
        PullRequestResult prep = preparePullRequest(accessToken, jobId, attemptNumber);

        // Create the PR via GitHub API
        String prUrl = createPR(accessToken, prep.owner(), prep.repo(), prep.title(),
                               prep.body(), prep.branch(), prep.baseBranch());

        // Return result with PR URL
        return new PullRequestResult(prUrl, prep.title(), prep.branch(),
                                    prep.owner(), prep.repo(), prep.body(), prep.baseBranch());
    }

    // === Private helper methods ===

    private RepoInfo parseRepoUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("Repository URL is required");
        }

        // Handle various GitHub URL formats:
        // https://github.com/owner/repo
        // https://github.com/owner/repo.git
        // git@github.com:owner/repo.git

        Pattern httpsPattern = Pattern.compile("github\\.com[/:]([^/]+)/([^/.]+)(?:\\.git)?");
        Matcher matcher = httpsPattern.matcher(repoUrl);

        if (matcher.find()) {
            return new RepoInfo(matcher.group(1), matcher.group(2));
        }

        throw new IllegalArgumentException("Invalid GitHub repository URL: " + repoUrl);
    }

    private String getDefaultBranch(String accessToken, String owner, String repo)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/%s", GITHUB_API_BASE, owner, repo)))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Failed to get repo info: {} - {}", response.statusCode(), response.body());
            throw new IOException("Failed to get repository info: " + response.statusCode());
        }

        JsonNode json = objectMapper.readTree(response.body());
        return json.get("default_branch").asText();
    }

    private String getLatestCommitSha(String accessToken, String owner, String repo, String branch)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/%s/git/ref/heads/%s",
                        GITHUB_API_BASE, owner, repo, branch)))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Failed to get branch ref: {} - {}", response.statusCode(), response.body());
            throw new IOException("Failed to get branch reference: " + response.statusCode());
        }

        JsonNode json = objectMapper.readTree(response.body());
        return json.get("object").get("sha").asText();
    }

    private void createBranch(String accessToken, String owner, String repo, String branchName, String sha)
            throws IOException, InterruptedException {
        Map<String, String> requestBody = Map.of(
                "ref", "refs/heads/" + branchName,
                "sha", sha
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/%s/git/refs", GITHUB_API_BASE, owner, repo)))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201) {
            // Branch might already exist, try to get it
            if (response.statusCode() == 422 && response.body().contains("Reference already exists")) {
                log.info("Branch {} already exists, will use it", branchName);
                return;
            }
            log.error("Failed to create branch: {} - {}", response.statusCode(), response.body());
            throw new IOException("Failed to create branch: " + response.statusCode());
        }

        log.info("Created branch: {}", branchName);
    }

    private void applyPatchToGitHub(String accessToken, String owner, String repo,
                                     String branch, String patchContent, String commitMessage)
            throws IOException, InterruptedException {

        // Parse the patch to extract file changes
        List<FileChange> changes = parsePatch(patchContent);

        if (changes.isEmpty()) {
            throw new IllegalStateException("No file changes found in patch");
        }

        // For each file, get current content, apply changes, and commit
        for (FileChange change : changes) {
            log.info("Applying change to file: {}", change.path);

            // Get current file content and SHA
            FileContent currentContent = getFileContent(accessToken, owner, repo, branch, change.path);

            // Apply the patch changes to get new content
            String newContent = applyHunksToContent(currentContent.content, change.hunks);

            // Update file on GitHub
            updateFile(accessToken, owner, repo, branch, change.path, newContent,
                    currentContent.sha, commitMessage);
        }
    }

    private FileContent getFileContent(String accessToken, String owner, String repo,
                                        String branch, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/%s/contents/%s?ref=%s",
                        GITHUB_API_BASE, owner, repo, path, branch)))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) {
            // File doesn't exist yet (new file)
            return new FileContent("", null);
        }

        if (response.statusCode() != 200) {
            log.error("Failed to get file content: {} - {}", response.statusCode(), response.body());
            throw new IOException("Failed to get file content: " + response.statusCode());
        }

        JsonNode json = objectMapper.readTree(response.body());
        String content = new String(Base64.getMimeDecoder().decode(
                json.get("content").asText().replaceAll("\\s", "")), StandardCharsets.UTF_8);
        String sha = json.get("sha").asText();

        return new FileContent(content, sha);
    }

    private void updateFile(String accessToken, String owner, String repo, String branch,
                            String path, String content, String sha, String message)
            throws IOException, InterruptedException {

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("message", message);
        requestBody.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        requestBody.put("branch", branch);
        if (sha != null) {
            requestBody.put("sha", sha);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/%s/contents/%s",
                        GITHUB_API_BASE, owner, repo, path)))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            log.error("Failed to update file {}: {} - {}", path, response.statusCode(), response.body());
            throw new IOException("Failed to update file " + path + ": " + response.statusCode());
        }

        log.info("Updated file: {}", path);
    }

    private String createPR(String accessToken, String owner, String repo, String title,
                            String body, String head, String base) throws IOException, InterruptedException {

        Map<String, String> requestBody = Map.of(
                "title", title,
                "body", body,
                "head", head,
                "base", base
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/repos/%s/%s/pulls", GITHUB_API_BASE, owner, repo)))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201) {
            log.error("Failed to create PR: {} - {}", response.statusCode(), response.body());
            throw new IOException("Failed to create pull request: " + response.statusCode());
        }

        JsonNode json = objectMapper.readTree(response.body());
        String prUrl = json.get("html_url").asText();

        log.info("Created PR: {}", prUrl);
        return prUrl;
    }

    // === Patch parsing helpers ===

    private List<FileChange> parsePatch(String patchContent) {
        List<FileChange> changes = new ArrayList<>();

        // Split by file diffs
        String[] fileDiffs = patchContent.split("(?=diff --git)");

        for (String fileDiff : fileDiffs) {
            if (fileDiff.isBlank()) continue;

            // Extract file path from "+++ b/path"
            Pattern pathPattern = Pattern.compile("^\\+\\+\\+ b/(.+)$", Pattern.MULTILINE);
            Matcher pathMatcher = pathPattern.matcher(fileDiff);

            if (!pathMatcher.find()) continue;

            String path = pathMatcher.group(1);
            List<Hunk> hunks = parseHunks(fileDiff);

            changes.add(new FileChange(path, hunks));
        }

        return changes;
    }

    private List<Hunk> parseHunks(String fileDiff) {
        List<Hunk> hunks = new ArrayList<>();

        // Pattern for hunk headers: @@ -start,count +start,count @@
        Pattern hunkPattern = Pattern.compile("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
        Matcher matcher = hunkPattern.matcher(fileDiff);

        int lastEnd = 0;
        int hunkStart = -1;
        int oldStart = 0;
        int newStart = 0;

        while (matcher.find()) {
            // If we have a previous hunk, extract its content
            if (hunkStart >= 0) {
                String hunkContent = fileDiff.substring(hunkStart, matcher.start());
                hunks.add(new Hunk(oldStart, newStart, hunkContent));
            }

            oldStart = Integer.parseInt(matcher.group(1));
            newStart = Integer.parseInt(matcher.group(2));
            hunkStart = matcher.end();
            lastEnd = matcher.end();
        }

        // Don't forget the last hunk
        if (hunkStart >= 0 && lastEnd < fileDiff.length()) {
            String hunkContent = fileDiff.substring(hunkStart);
            hunks.add(new Hunk(oldStart, newStart, hunkContent));
        }

        return hunks;
    }

    private String applyHunksToContent(String originalContent, List<Hunk> hunks) {
        if (hunks.isEmpty()) {
            return originalContent;
        }

        // Split content into lines
        List<String> lines = new ArrayList<>(Arrays.asList(originalContent.split("\n", -1)));

        // Apply hunks in reverse order to maintain line numbers
        hunks.sort((a, b) -> Integer.compare(b.oldStart, a.oldStart));

        for (Hunk hunk : hunks) {
            lines = applyHunk(lines, hunk);
        }

        return String.join("\n", lines);
    }

    private List<String> applyHunk(List<String> lines, Hunk hunk) {
        List<String> result = new ArrayList<>();
        String[] hunkLines = hunk.content.split("\n");

        int lineIndex = 0;
        int targetLine = hunk.oldStart - 1; // Convert to 0-based

        // Copy lines before the hunk
        while (lineIndex < targetLine && lineIndex < lines.size()) {
            result.add(lines.get(lineIndex));
            lineIndex++;
        }

        // Process hunk lines
        for (String hunkLine : hunkLines) {
            if (hunkLine.isEmpty()) continue;

            char prefix = hunkLine.charAt(0);
            String content = hunkLine.length() > 1 ? hunkLine.substring(1) : "";

            switch (prefix) {
                case '+':
                    // Add new line
                    result.add(content);
                    break;
                case '-':
                    // Skip (remove) old line
                    lineIndex++;
                    break;
                case ' ':
                    // Context line - copy and advance
                    if (lineIndex < lines.size()) {
                        result.add(lines.get(lineIndex));
                        lineIndex++;
                    } else {
                        result.add(content);
                    }
                    break;
                default:
                    // Ignore other lines (like "\ No newline at end of file")
                    break;
            }
        }

        // Copy remaining lines after hunk
        while (lineIndex < lines.size()) {
            result.add(lines.get(lineIndex));
            lineIndex++;
        }

        return result;
    }

    // === Inner classes ===

    private record OAuthState(String jobId, int attemptNumber, long timestamp) {}

    public record OAuthResult(String accessToken, String jobId, int attemptNumber) {}

    public record PullRequestResult(
            String url,         // PR URL (null if not created yet via API)
            String title,
            String branch,
            String owner,       // Repository owner
            String repo,        // Repository name
            String body,        // PR description
            String baseBranch   // Base branch (e.g., "main")
    ) {}

    private record RepoInfo(String owner, String name) {}

    private record FileContent(String content, String sha) {}

    private record FileChange(String path, List<Hunk> hunks) {}

    private record Hunk(int oldStart, int newStart, String content) {}
}
