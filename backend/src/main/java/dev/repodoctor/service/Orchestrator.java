package dev.repodoctor.service;

import dev.repodoctor.config.RepoDoctorConfig;
import dev.repodoctor.llm.*;
import dev.repodoctor.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Main orchestrator for the diagnose → patch → run → repeat loop.
 */
@Service
public class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final JobRepository jobRepository;
    private final AttemptRepository attemptRepository;
    private final ApplicationRunner applicationRunner;
    private final PatchEngine patchEngine;
    private final ArtifactService artifactService;
    private final EventService eventService;
    private final LLMClient llmClient;
    private final BaselineAnalyzer baselineAnalyzer;
    private final RepoDoctorConfig config;

    public Orchestrator(JobRepository jobRepository, AttemptRepository attemptRepository,
            ApplicationRunner applicationRunner, PatchEngine patchEngine,
            ArtifactService artifactService, EventService eventService,
            LLMClient llmClient, BaselineAnalyzer baselineAnalyzer, RepoDoctorConfig config) {
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.applicationRunner = applicationRunner;
        this.patchEngine = patchEngine;
        this.artifactService = artifactService;
        this.eventService = eventService;
        this.llmClient = llmClient;
        this.baselineAnalyzer = baselineAnalyzer;
        this.config = config;
    }

    /**
     * Run the full orchestration loop for a job.
     * This is called asynchronously after job creation.
     */
    @Async
    @org.springframework.transaction.annotation.Transactional
    public void runJob(String jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.error("Job not found: {}", jobId);
            return;
        }

        try {
            // Validate workspace path
            if (job.getWorkspacePath() == null || job.getWorkspacePath().isBlank()) {
                failJob(job, "Workspace path is not set");
                return;
            }

            Path workspacePath = Path.of(job.getWorkspacePath());

            // Verify workspace exists
            if (!Files.exists(workspacePath)) {
                failJob(job, "Workspace directory does not exist: " + workspacePath);
                return;
            }

            // Emit job started
            eventService.emitJobStarted(jobId, job.getRepoName());

            // Detect build tool
            BuildTool buildTool = applicationRunner.detectBuildTool(workspacePath);
            job.setBuildTool(buildTool);
            job.setStatus(JobStatus.RUNNING);
            jobRepository.save(job);

            // Emit build tool detection event
            if (buildTool != BuildTool.UNKNOWN) {
                eventService.emitBuildToolDetected(jobId, buildTool.name());
            }

            if (buildTool == BuildTool.UNKNOWN) {
                failJob(job, "Unsupported project type. RepoDoctor currently supports:\n" +
                        "• Java (Maven: pom.xml, or Gradle: build.gradle/build.gradle.kts)\n" +
                        "• Node.js (package.json)\n" +
                        "• Python (pyproject.toml, pytest.ini, setup.py, or test_*.py files)\n\n" +
                        "Please ensure your project has one of these configuration files in the root directory.");
                return;
            }

            // Initialize git repository
            patchEngine.initializeRepository(workspacePath);

            // Run baseline (Attempt 0)
            Attempt baseline = runAttempt(job, 0, true);

            // Check if entering improvement mode (no tests found)
            if (baseline.getTestsRun() == 0) {
                eventService.emitImprovementMode(jobId, "No tests detected - analyzing for code improvements");
            }

            // If baseline succeeded, we still continue to generate "Improvements"
            // instead of just stopping, unless maxAttempts is 0.
            if (baseline.getStatus() == AttemptStatus.SUCCESS && job.getMaxAttempts() == 0) {
                completeJob(job, true, "Tests already pass - no further improvements requested");
                return;
            }

            // If LLM not configured, stop here
            if (!llmClient.isConfigured()) {
                completeJob(job, false, "Baseline failed. Configure GEMINI_API_KEY for auto-fix attempts.");
                return;
            }

            // Run fix attempts
            for (int attemptNum = 1; attemptNum <= job.getMaxAttempts(); attemptNum++) {
                Attempt attempt = runAttempt(job, attemptNum, false);

                if (attempt.getStatus() == AttemptStatus.SUCCESS) {
                    String message = baseline.getStatus() == AttemptStatus.SUCCESS
                            ? String.format("Analysis complete! Applied improvements in %d attempt(s).", attemptNum)
                            : String.format("Fixed and improved after %d attempt(s)! All tests pass.", attemptNum);
                    completeJob(job, true, message);
                    return;
                }

                // Check for terminal failures
                if (attempt.getStatus() == AttemptStatus.LLM_ERROR ||
                        attempt.getStatus() == AttemptStatus.LLM_INVALID_OUTPUT ||
                        attempt.getStatus() == AttemptStatus.LLM_SERVICE_UNAVAILABLE) {
                    String failureMessage = attempt.getStatus() == AttemptStatus.LLM_SERVICE_UNAVAILABLE
                            ? "Gemini API is temporarily unavailable. Please try again later."
                            : "LLM failed to generate valid patch: " + attempt.getErrorMessage();
                    completeJob(job, false, failureMessage);
                    return;
                }
            }

            // Max attempts reached
            boolean isSuccessfulCompletion = baseline.getStatus() == AttemptStatus.SUCCESS;

            // Special handling for "Improvement Mode":
            // If baseline had no tests (testsRun == 0), and we generated at least one patch
            // with valid AI analysis, we consider this a success (COMPLETED) because we provided improvements.
            if (!isSuccessfulCompletion && baseline.getTestsRun() == 0) {
                // Check if any attempt generated a patch with AI analysis
                // We consider PATCH_FAILED acceptable because the analysis is still valuable
                // even if automatic application failed due to infrastructure issues
                boolean hasPatchWithAnalysis = job.getAttempts().stream()
                        .anyMatch(a -> a.getAttemptNumber() > 0
                                && a.getExplanation() != null
                                && !a.getExplanation().isEmpty()
                                && a.getStatus() != AttemptStatus.LLM_ERROR
                                && a.getStatus() != AttemptStatus.LLM_INVALID_OUTPUT
                                && a.getStatus() != AttemptStatus.LLM_SERVICE_UNAVAILABLE);

                if (hasPatchWithAnalysis) {
                    isSuccessfulCompletion = true;
                }
            }

            String message = isSuccessfulCompletion
                    ? "Analysis complete. Review the proposed suggestions even if some could not be applied automatically."
                    : String.format("Max attempts (%d) reached without fixing all tests", job.getMaxAttempts());
            completeJob(job, isSuccessfulCompletion, message);

        } catch (Exception e) {
            log.error("Job failed with exception", e);
            failJob(job, "Internal error: " + e.getMessage());
        } finally {
            llmClient.clearHistory(jobId);
        }
    }

    private Attempt runAttempt(Job job, int attemptNumber, boolean isBaseline) {
        String jobId = job.getId();
        Path workspacePath = Path.of(job.getWorkspacePath());

        Attempt attempt = new Attempt(attemptNumber);
        job.addAttempt(attempt);
        attemptRepository.save(attempt);

        eventService.emitAttemptStarted(jobId, attemptNumber, isBaseline);

        try {
            if (!isBaseline) {
                // Generate and apply patch
                attempt.setStatus(AttemptStatus.ANALYZING);
                attemptRepository.save(attempt);

                // Get prior attempts for context
                List<PatchContext.PriorAttempt> priorAttempts = getPriorAttempts(job);

                // Get failure summary from previous attempt logs
                String prevLogs = artifactService.getLogs(jobId, attemptNumber - 1);
                String logTail = getLogTail(prevLogs, 400);

                FailureSummary failureSummary = llmClient.summarizeFailure(
                        logTail,
                        job.getBuildTool().getName(),
                        job.getBuildTool().getTestCommand());

                // Build patch context
                PatchContext context = new PatchContext(
                        jobId,
                        job.getBuildTool(),
                        job.getBuildTool().getTestCommand(),
                        patchEngine.getRepoTree(workspacePath),
                        buildFileContext(workspacePath, job.getBuildTool()),
                        logTail,
                        failureSummary,
                        priorAttempts);

                // Smart timing: Pause slightly before the next LLM call to respect rate limits
                // (e.g. 2 RPM)
                try {
                    attempt.setStatus(AttemptStatus.RATE_LIMIT_PAUSE);
                    attemptRepository.save(attempt);
                    eventService.emitRateLimitWait(jobId, attemptNumber, 10);
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Get patch proposal
                attempt.setStatus(AttemptStatus.PATCHING);
                attemptRepository.save(attempt);

                // Emit analyzing with LLM event
                eventService.emitAnalyzingWithLLM(jobId, attemptNumber);

                PatchProposal proposal = llmClient.proposePatch(context);
                log.info("Job {} Attempt {}: LLM proposed patch (risk={})", jobId, attemptNumber, proposal.riskLevel());

                eventService.emitPatchProposed(jobId, attemptNumber,
                        proposal.riskLevel(), proposal.touchedFiles());

                // Validate proposal
                if (!proposal.isValid()) {
                    String diffPreview = proposal.unifiedDiff() != null
                            ? proposal.unifiedDiff().substring(0, Math.min(300, proposal.unifiedDiff().length()))
                            : "(null)";
                    log.warn(
                            "Job {} Attempt {}: LLM returned invalid patch proposal. Diff preview (first 300 chars): {}",
                            jobId, attemptNumber, diffPreview);
                    log.warn("Job {} Attempt {}: Full diff length: {}, has content: {}",
                            jobId, attemptNumber,
                            proposal.unifiedDiff() != null ? proposal.unifiedDiff().length() : 0,
                            proposal.unifiedDiff() != null && !proposal.unifiedDiff().isBlank());

                    attempt.setStatus(AttemptStatus.LLM_INVALID_OUTPUT);
                    attempt.setErrorMessage("LLM returned invalid patch proposal: missing required git diff format");
                    attempt.setCompletedAt(Instant.now());
                    attemptRepository.save(attempt);
                    eventService.emitAttemptCompleted(jobId, attemptNumber, "LLM_INVALID_OUTPUT",
                            "Invalid patch proposal");
                    return attempt;
                }

                // Save patch details
                attempt.setExplanation(proposal.explanation());
                attempt.setConfidenceNotes(proposal.confidenceNotes());
                attempt.setRiskLevel(proposal.riskLevel());

                // Apply patch
                log.info("Job {} Attempt {}: Applying patch...", jobId, attemptNumber);
                PatchEngine.PatchResult patchResult = patchEngine.applyPatch(
                        workspacePath, proposal.unifiedDiff(), attemptNumber);

                log.info("Job {} Attempt {}: Patch result: {} ({})", jobId, attemptNumber,
                        patchResult.success(), patchResult.message());

                eventService.emitPatchApplied(jobId, attemptNumber, patchResult.success());

                if (!patchResult.success()) {
                    attempt.setStatus(AttemptStatus.PATCH_FAILED);
                    attempt.setErrorMessage(patchResult.message());
                    attempt.setCompletedAt(Instant.now());
                    attemptRepository.save(attempt);

                    // Save the attempted diff even if it failed
                    artifactService.saveDiff(jobId, attemptNumber, proposal.unifiedDiff());

                    eventService.emitAttemptCompleted(jobId, attemptNumber, "PATCH_FAILED",
                            patchResult.message());
                    return attempt;
                }

                // Save the applied diff
                artifactService.saveDiff(jobId, attemptNumber, proposal.unifiedDiff());

                // Save explanation
                artifactService.saveExplanation(jobId, attemptNumber, Map.of(
                        "explanation", proposal.explanation(),
                        "confidenceNotes", proposal.confidenceNotes(),
                        "riskLevel", proposal.riskLevel(),
                        "touchedFiles", proposal.touchedFiles()));
            }

            // Run tests
            log.info("Job {} Attempt {}: Running tests (baseline={})...", jobId, attemptNumber, isBaseline);
            attempt.setStatus(AttemptStatus.RUNNING);
            attemptRepository.save(attempt);

            ApplicationRunner.ExecutionResult result = applicationRunner.runTests(
                    workspacePath, job.getBuildTool(), job.isAllowNetwork());

            log.info("Job {} Attempt {}: Test run finished. Exit code: {}, Success: {}",
                    jobId, attemptNumber, result.exitCode(), result.isSuccess());

            // Store results
            attempt.setExitCode(result.exitCode());
            attempt.setTestsRun(result.testResults().run());
            attempt.setTestsFailed(result.testResults().failed());
            attempt.setTestsPassed(result.testResults().passed());

            // Save logs
            artifactService.saveLogs(jobId, attemptNumber, result.logs());

            // Save summary
            artifactService.saveSummary(jobId, attemptNumber, Map.of(
                    "attemptNumber", attemptNumber,
                    "isBaseline", isBaseline,
                    "exitCode", result.exitCode(),
                    "durationMs", result.durationMs(),
                    "testsRun", result.testResults().run(),
                    "testsFailed", result.testResults().failed(),
                    "testsPassed", result.testResults().passed(),
                    "success", result.isSuccess()));

            eventService.emitRunCompleted(jobId, attemptNumber, result.exitCode(), result.isSuccess(),
                    result.testResults().run(), result.testResults().failed());

            // For baseline, generate analysis summary
            if (isBaseline) {
                try {
                    BaselineAnalyzer.BaselineAnalysis analysis = baselineAnalyzer.analyzeFailure(
                            result.logs(),
                            job.getBuildTool(),
                            result.testResults().failed(),
                            result.testResults().run());

                    // Set the explanation on the attempt so it's returned via API
                    attempt.setExplanation(analysis.summary());

                    // Save the analysis as baseline explanation
                    artifactService.saveExplanation(jobId, attemptNumber, Map.of(
                            "explanation", analysis.summary(),
                            "failureType", analysis.failureType(),
                            "failedTests", analysis.failedTests(),
                            "errorMessages", analysis.errorMessages(),
                            "testsFailed", analysis.failedCount(),
                            "testsTotal", analysis.totalCount()));

                    log.info("Job {} Baseline: Generated analysis - {} ({} failed tests)",
                            jobId, analysis.failureType(), analysis.failedCount());
                } catch (Exception e) {
                    log.warn("Failed to generate baseline analysis", e);
                }
            }

            if (result.isSuccess()) {
                attempt.setStatus(AttemptStatus.SUCCESS);
                eventService.emitAttemptCompleted(jobId, attemptNumber, "SUCCESS", "All tests pass!");
            } else {
                attempt.setStatus(AttemptStatus.FAILED);
                eventService.emitAttemptCompleted(jobId, attemptNumber, "FAILED",
                        String.format("Tests failed: %d/%d", result.testResults().failed(),
                                result.testResults().run()));
            }

        } catch (dev.repodoctor.llm.LLMServiceUnavailableException e) {
            log.error("Attempt {} failed - Gemini service unavailable", attemptNumber, e);
            attempt.setStatus(AttemptStatus.LLM_SERVICE_UNAVAILABLE);
            attempt.setErrorMessage("Gemini API is temporarily overloaded. Please try again in a few minutes.");
            eventService.emitAttemptCompleted(jobId, attemptNumber, "LLM_SERVICE_UNAVAILABLE",
                    "Gemini temporarily unavailable");
        } catch (Exception e) {
            log.error("Attempt {} failed", attemptNumber, e);
            attempt.setStatus(AttemptStatus.FAILED);
            attempt.setErrorMessage(e.getMessage());
            eventService.emitAttemptCompleted(jobId, attemptNumber, "ERROR", e.getMessage());
        }

        attempt.setCompletedAt(Instant.now());
        attemptRepository.save(attempt);

        return attempt;
    }

    private List<PatchContext.PriorAttempt> getPriorAttempts(Job job) {
        List<PatchContext.PriorAttempt> priors = new ArrayList<>();

        for (Attempt attempt : job.getAttempts()) {
            if (attempt.getAttemptNumber() > 0) {
                String diffSummary = "";
                try {
                    String diff = artifactService.getDiff(job.getId(), attempt.getAttemptNumber());
                    diffSummary = summarizeDiff(diff);
                } catch (IOException e) {
                    // Ignore
                }

                priors.add(new PatchContext.PriorAttempt(
                        attempt.getAttemptNumber(),
                        diffSummary,
                        attempt.getStatus().name(),
                        attempt.getErrorMessage()));
            }
        }

        return priors;
    }

    private String summarizeDiff(String diff) {
        if (diff == null || diff.isEmpty()) {
            return "No diff available";
        }

        // Count modified files
        long fileCount = diff.lines()
                .filter(l -> l.startsWith("---") || l.startsWith("+++"))
                .count() / 2;

        // Count added/removed lines
        long added = diff.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count();
        long removed = diff.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).count();

        return String.format("%d file(s), +%d/-%d lines", fileCount, added, removed);
    }

    private String getLogTail(String logs, int lines) {
        if (logs == null || logs.isEmpty()) {
            return "";
        }

        String[] allLines = logs.split("\n");
        int start = Math.max(0, allLines.length - lines);

        StringBuilder tail = new StringBuilder();
        for (int i = start; i < allLines.length; i++) {
            tail.append(allLines[i]).append("\n");
        }

        return tail.toString();
    }

    private String buildFileContext(Path workspacePath, BuildTool buildTool) {
        List<String> candidates = switch (buildTool) {
            case MAVEN -> List.of("pom.xml");
            case GRADLE -> List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
            case NODE -> List.of("package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml");
            case PYTHON ->
                List.of("pyproject.toml", "setup.py", "setup.cfg", "pytest.ini", "requirements.txt", "tox.ini");
            default -> List.of();
        };

        StringBuilder context = new StringBuilder();
        int maxCharsPerFile = 20000;

        // Add build files
        for (String candidate : candidates) {
            appendFileWithLineNumbers(workspacePath.resolve(candidate), candidate, context, maxCharsPerFile);
        }

        // Also add Java/source files from common locations
        try {
            // Look for Java files in src/main and src/test (Maven convention)
            Path srcMain = workspacePath.resolve("src/main/java");
            Path srcTest = workspacePath.resolve("src/test/java");

            if (Files.exists(srcMain)) {
                Files.walk(srcMain, 5)
                        .filter(p -> p.toString().endsWith(".java"))
                        .limit(10) // Limit to first 10 files
                        .forEach(p -> {
                            String relativePath = workspacePath.relativize(p).toString();
                            appendFileWithLineNumbers(p, relativePath, context, maxCharsPerFile);
                        });
            }

            if (Files.exists(srcTest)) {
                Files.walk(srcTest, 5)
                        .filter(p -> p.toString().endsWith(".java"))
                        .limit(5) // Limit test files
                        .forEach(p -> {
                            String relativePath = workspacePath.relativize(p).toString();
                            appendFileWithLineNumbers(p, relativePath, context, maxCharsPerFile);
                        });
            }

            // Look for Python files in common locations
            if (buildTool == BuildTool.PYTHON) {
                scanPythonFiles(workspacePath, context, maxCharsPerFile);
            }
        } catch (IOException e) {
            log.warn("Failed to scan source directories", e);
        }

        return context.toString();
    }

    private void scanPythonFiles(Path workspacePath, StringBuilder context, int maxCharsPerFile) throws IOException {
        // Scan for .py files in root, src, test, and tests directories
        List<Path> searchPaths = new ArrayList<>();
        searchPaths.add(workspacePath);

        Path src = workspacePath.resolve("src");
        if (Files.exists(src))
            searchPaths.add(src);

        Path test = workspacePath.resolve("test");
        if (Files.exists(test))
            searchPaths.add(test);

        Path tests = workspacePath.resolve("tests");
        if (Files.exists(tests))
            searchPaths.add(tests);

        for (Path searchPath : searchPaths) {
            try (var stream = Files.walk(searchPath, 3)) {
                stream.filter(p -> p.toString().endsWith(".py") && Files.isRegularFile(p))
                        .limit(10) // Limit total files to avoid huge context
                        .forEach(p -> {
                            // Avoid duplicates if we're scanning subdirectories of already scanned paths
                            if (context.toString().contains("File: " + workspacePath.relativize(p))) {
                                return;
                            }
                            String relativePath = workspacePath.relativize(p).toString();
                            appendFileWithLineNumbers(p, relativePath, context, maxCharsPerFile);
                        });
            } catch (IOException e) {
                log.warn("Error scanning python files in {}", searchPath, e);
            }
        }
    }

    private void appendFileWithLineNumbers(Path file, String displayName, StringBuilder context, int maxCharsPerFile) {
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return;
        }

        try {
            String originalContent = Files.readString(file);
            if (originalContent.length() > maxCharsPerFile) {
                originalContent = originalContent.substring(0, maxCharsPerFile) + "\n-----TRUNCATED-----\n";
            }

            // Add line numbers to content
            String[] lines = originalContent.split("\n", -1);
            StringBuilder numberedContent = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                numberedContent.append(String.format("%4d: %s\n", i + 1, lines[i]));
            }

            context.append("File: ").append(displayName).append("\n");
            context.append("-----BEGIN FILE (with line numbers)-----\n");
            context.append(numberedContent);
            context.append("-----END FILE-----\n\n");
        } catch (IOException e) {
            log.warn("Failed to read file context for {}", displayName, e);
        }
    }

    private void completeJob(Job job, boolean success, String summary) {
        job.setStatus(success ? JobStatus.COMPLETED : JobStatus.FAILED);
        job.setCompletedAt(Instant.now());
        job.setErrorMessage(success ? null : summary);
        jobRepository.save(job);

        eventService.emitJobCompleted(job.getId(), success, job.getAttempts().size(), summary);
    }

    private void failJob(Job job, String error) {
        job.setStatus(JobStatus.FAILED);
        job.setCompletedAt(Instant.now());
        job.setErrorMessage(error);
        jobRepository.save(job);

        eventService.emitError(job.getId(), error);
        eventService.emitJobCompleted(job.getId(), false, job.getAttempts().size(), error);
    }
}
