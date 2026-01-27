package dev.repodoctor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for applying patches via git operations.
 * Ensures repository is initialized and commits each patch attempt.
 */
@Service
public class PatchEngine {

    private static final Logger log = LoggerFactory.getLogger(PatchEngine.class);

    /**
     * Initialize git repository with baseline commit.
     * Call this after extracting/cloning the repo.
     */
    public void initializeRepository(Path workspacePath) throws IOException {
        // Check if already a git repo
        if (!Files.exists(workspacePath.resolve(".git"))) {
            runGitCommand(workspacePath, "init");
        }

        // Configure git for commits
        runGitCommand(workspacePath, "config", "user.email", "repodoctor@example.com");
        runGitCommand(workspacePath, "config", "user.name", "RepoDoctor");

        // Configure line ending handling for consistent behavior across platforms
        runGitCommand(workspacePath, "config", "core.autocrlf", "false");
        runGitCommand(workspacePath, "config", "core.eol", "lf");

        // Add all files and create baseline commit
        runGitCommand(workspacePath, "add", "-A");
        runGitCommand(workspacePath, "commit", "-m", "baseline", "--allow-empty");

        log.info("Initialized git repository at {}", workspacePath);
    }

    /**
     * Apply a unified diff patch to the repository.
     * Returns true if patch applied successfully.
     */
    public PatchResult applyPatch(Path workspacePath, String unifiedDiff, int attemptNumber) {
        // Validate patch doesn't modify paths outside workspace
        if (!validatePatchPaths(unifiedDiff)) {
            return new PatchResult(false, "Patch contains invalid paths (outside workspace)");
        }

        // Normalize line endings to LF (Unix style) - critical for git apply on Linux
        String normalizedDiff = unifiedDiff.replace("\r\n", "\n").replace("\r", "\n");

        // Fix common patch format issues
        normalizedDiff = fixPatchFormat(normalizedDiff);

        // Write diff to temp file
        Path patchFile;
        try {
            patchFile = Files.createTempFile("patch-", ".diff");
            Files.writeString(patchFile, normalizedDiff);
        } catch (IOException e) {
            return new PatchResult(false, "Failed to write patch file: " + e.getMessage());
        }

        try {
            // Try to apply the patch with different strategies
            String result;
            int strategy = 0; // Track which strategy worked

            // Strategy 1: ignore space and whitespace changes
            result = runGitCommandWithOutput(workspacePath, "apply", "--ignore-space-change",
                    "--ignore-whitespace", "--check", patchFile.toString());
            boolean applySuccess = !result.contains("error:") && !result.contains("fatal:");

            if (!applySuccess) {
                // Strategy 2: just ignore whitespace
                log.warn("First git apply check failed, trying with --ignore-whitespace only");
                result = runGitCommandWithOutput(workspacePath, "apply", "--ignore-whitespace", "--check",
                        patchFile.toString());
                applySuccess = !result.contains("error:") && !result.contains("fatal:");
                strategy = 1;
            }

            if (!applySuccess) {
                // Strategy 3: use 3-way merge
                log.warn("Second git apply check failed, trying with --3way");
                result = runGitCommandWithOutput(workspacePath, "apply", "--3way", "--check",
                        patchFile.toString());
                applySuccess = !result.contains("error:") && !result.contains("fatal:");
                strategy = 2;
            }

            if (!applySuccess) {
                log.error("All git apply strategies failed. Final error: {}", result);
                log.error("Full patch content ({} chars):\n{}", normalizedDiff.length(), normalizedDiff);

                // Log each line with its length and hex representation for debugging
                String[] lines = normalizedDiff.split("\n", -1);
                for (int i = 0; i < Math.min(20, lines.length); i++) {
                    log.error("Patch line {}: [{}] len={}", i + 1,
                            lines[i].replace("\t", "\\t").replace("\r", "\\r"),
                            lines[i].length());
                }

                return new PatchResult(false, "Patch would not apply cleanly: " + result);
            }

            // Actually apply the patch using the strategy that succeeded
            switch (strategy) {
                case 0 -> runGitCommand(workspacePath, "apply", "--ignore-space-change", "--ignore-whitespace",
                        patchFile.toString());
                case 1 -> runGitCommand(workspacePath, "apply", "--ignore-whitespace", patchFile.toString());
                case 2 -> runGitCommand(workspacePath, "apply", "--3way", patchFile.toString());
            }

            // Commit the changes
            runGitCommand(workspacePath, "add", "-A");
            runGitCommand(workspacePath, "commit", "-m", "attempt-" + attemptNumber);

            log.info("Applied patch for attempt {} in {}", attemptNumber, workspacePath);
            return new PatchResult(true, "Patch applied successfully");

        } catch (Exception e) {
            log.error("Failed to apply patch", e);

            // Try to reset to clean state
            try {
                runGitCommand(workspacePath, "checkout", "--", ".");
                runGitCommand(workspacePath, "clean", "-fd");
            } catch (Exception resetError) {
                log.error("Failed to reset after patch failure", resetError);
            }

            return new PatchResult(false, "Failed to apply patch: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(patchFile);
            } catch (IOException e) {
                // Ignore cleanup failure
            }
        }
    }

    /**
     * Get the diff for a specific commit (by attempt number).
     */
    public String getAttemptDiff(Path workspacePath, int attemptNumber) {
        try {
            return runGitCommandWithOutput(workspacePath, "show", "--format=", "attempt-" + attemptNumber);
        } catch (Exception e) {
            // Try getting diff from commit message
            try {
                return runGitCommandWithOutput(workspacePath, "log", "-1", "--format=%B", "-p",
                        "--grep=attempt-" + attemptNumber);
            } catch (Exception e2) {
                log.error("Failed to get diff for attempt {}", attemptNumber, e2);
                return "";
            }
        }
    }

    /**
     * Get a tree listing of the repository (top-level files).
     */
    public String getRepoTree(Path workspacePath) {
        try (Stream<Path> walk = Files.walk(workspacePath, 2)) {
            return walk
                    .filter(p -> !p.toString().contains(".git"))
                    .map(p -> workspacePath.relativize(p).toString())
                    .filter(s -> !s.isEmpty())
                    .sorted()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            log.error("Failed to get repo tree", e);
            return "";
        }
    }

    /**
     * Fix common patch format issues that cause git apply to fail.
     */
    private String fixPatchFormat(String patch) {
        StringBuilder fixed = new StringBuilder();
        String[] lines = patch.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // 1. Fix context lines that are missing the leading space
            // If a line is in a hunk (after @@) and doesn't start with +, -, or space,
            // it's likely a context line where the LLM skipped the space.
            if (isInHunk(lines, i)) {
                if (!line.isEmpty() && !line.startsWith("+") && !line.startsWith("-") && !line.startsWith(" ")
                        && !line.startsWith("@@")) {
                    line = " " + line;
                }
            }

            // Ensure patch ends with a newline
            if (i == lines.length - 1 && !line.isEmpty()) {
                fixed.append(line).append("\n");
            } else {
                fixed.append(line);
                if (i < lines.length - 1) {
                    fixed.append("\n");
                }
            }
        }

        String result = fixed.toString();

        // 2. Cleanup double newlines and ensure final newline
        while (result.endsWith("\n\n")) {
            result = result.substring(0, result.length() - 1);
        }
        if (!result.endsWith("\n")) {
            result += "\n";
        }

        return result;
    }

    private boolean isInHunk(String[] lines, int currentIndex) {
        // Look back for the nearest hunk header
        for (int i = currentIndex; i >= 0; i--) {
            if (lines[i].startsWith("@@"))
                return true;
            if (lines[i].startsWith("diff --git"))
                return false;
        }
        return false;
    }

    /**
     * Validate that the patch doesn't modify files outside the workspace.
     */
    private boolean validatePatchPaths(String unifiedDiff) {
        for (String line : unifiedDiff.split("\n")) {
            if (line.startsWith("---") || line.startsWith("+++")) {
                String path = line.substring(4).trim();
                // Remove a/ or b/ prefix
                if (path.startsWith("a/") || path.startsWith("b/")) {
                    path = path.substring(2);
                }
                // Check for path traversal
                if (path.contains("..") || path.startsWith("/")) {
                    log.warn("Invalid path in patch: {}", path);
                    return false;
                }
            }
        }
        return true;
    }

    private void runGitCommand(Path workDir, String... args) throws IOException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try {
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Git command timed out: " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command interrupted", e);
        }
    }

    private String runGitCommandWithOutput(Path workDir, String... args) throws IOException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        try {
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return output.toString();
    }

    public record PatchResult(boolean success, String message) {
    }
}
