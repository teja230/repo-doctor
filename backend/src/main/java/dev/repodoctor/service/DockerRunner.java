package dev.repodoctor.service;

import dev.repodoctor.config.RepoDoctorConfig;
import dev.repodoctor.model.BuildTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Service for running builds in Docker containers with sandbox restrictions.
 * Supports Maven, Gradle, and Node.js projects.
 */
@Service
public class DockerRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerRunner.class);

    private final RepoDoctorConfig config;

    public DockerRunner(RepoDoctorConfig config) {
        this.config = config;
    }

    /**
     * Detect the build tool based on project files.
     */
    public BuildTool detectBuildTool(Path workspacePath) {
        if (Files.exists(workspacePath.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        if (Files.exists(workspacePath.resolve("build.gradle")) ||
                Files.exists(workspacePath.resolve("build.gradle.kts"))) {
            return BuildTool.GRADLE;
        }
        if (Files.exists(workspacePath.resolve("package.json"))) {
            return BuildTool.NODE;
        }
        return BuildTool.UNKNOWN;
    }

    /**
     * Run the test command in a Docker container.
     * Returns the execution result with exit code and logs.
     */
    public ExecutionResult runTests(Path workspacePath, BuildTool buildTool, boolean allowNetwork) {
        String command = buildTool.getTestCommand();

        // Build Docker command with sandbox restrictions
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(buildDockerCommand(workspacePath, command, allowNetwork));
        pb.redirectErrorStream(true);

        StringBuilder logs = new StringBuilder();
        int exitCode = -1;
        long startTime = System.currentTimeMillis();

        ExecutorService executor = null;
        try {
            Process process = pb.start();

            // Read output with timeout
            executor = Executors.newSingleThreadExecutor();
            Future<String> outputFuture = executor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    StringBuilder output = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                    return output.toString();
                }
            });

            try {
                String output = outputFuture.get(config.getContainerTimeoutSeconds(), TimeUnit.SECONDS);
                logs.append(output);
                exitCode = process.waitFor();
            } catch (TimeoutException e) {
                process.destroyForcibly();
                outputFuture.cancel(true);
                logs.append("\n[TIMEOUT] Container execution exceeded ")
                        .append(config.getContainerTimeoutSeconds()).append(" seconds\n");
                exitCode = -1;
            }

        } catch (Exception e) {
            log.error("Docker execution failed", e);
            logs.append("\n[ERROR] Docker execution failed: ").append(e.getMessage()).append("\n");
            exitCode = -1;
        } finally {
            // Properly shutdown executor and wait for termination
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        TestResults testResults = parseTestResults(logs.toString(), buildTool);

        return new ExecutionResult(exitCode, logs.toString(), duration, testResults);
    }

    private String[] buildDockerCommand(Path workspacePath, String command, boolean allowNetwork) {
        // Use absolute path for the workspace
        String absPath = workspacePath.toAbsolutePath().toString();

        return new String[] {
                "docker", "run",
                "--rm",
                "--user", "1000:1000", // Non-root
                "-e", "MAVEN_CONFIG=/workspace/.m2", // Add MAVEN_CONFIG environment variable
                "--cpus", String.valueOf(config.getContainerCpus()),
                "--memory", config.getContainerMemoryMb() + "m",
                "--memory-swap", config.getContainerMemoryMb() + "m",
                allowNetwork ? "--network=bridge" : "--network=none",
                "-v", absPath + ":/workspace:rw",
                "-w", "/workspace",
                config.getRunnerImage(),
                command
        };
    }

    private TestResults parseTestResults(String logs, BuildTool buildTool) {
        int testsRun = 0;
        int testsFailed = 0;
        int testsPassed = 0;

        switch (buildTool) {
            case MAVEN -> {
                // Parse Maven Surefire output: Tests run: X, Failures: Y, Errors: Z
                var pattern = Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)");
                var matcher = pattern.matcher(logs);
                while (matcher.find()) {
                    testsRun += Integer.parseInt(matcher.group(1));
                    testsFailed += Integer.parseInt(matcher.group(2)) + Integer.parseInt(matcher.group(3));
                }
                testsPassed = testsRun - testsFailed;
            }
            case GRADLE -> {
                // Parse Gradle output
                var pattern = Pattern.compile("(\\d+) tests? completed, (\\d+) failed");
                var matcher = pattern.matcher(logs);
                if (matcher.find()) {
                    testsRun = Integer.parseInt(matcher.group(1));
                    testsFailed = Integer.parseInt(matcher.group(2));
                    testsPassed = testsRun - testsFailed;
                }
            }
            case NODE -> {
                // Parse npm test output (Jest-style)
                var passPattern = Pattern.compile("Tests:\\s+(\\d+) passed");
                var failPattern = Pattern.compile("Tests:\\s+(\\d+) failed");
                var passMatcher = passPattern.matcher(logs);
                var failMatcher = failPattern.matcher(logs);
                if (passMatcher.find()) {
                    testsPassed = Integer.parseInt(passMatcher.group(1));
                }
                if (failMatcher.find()) {
                    testsFailed = Integer.parseInt(failMatcher.group(1));
                }
                testsRun = testsPassed + testsFailed;
            }
            default -> {
                // Unknown build tool - just check exit code
            }
        }

        return new TestResults(testsRun, testsFailed, testsPassed);
    }

    public record ExecutionResult(
            int exitCode,
            String logs,
            long durationMs,
            TestResults testResults) {
        public boolean isSuccess() {
            return exitCode == 0 && testResults.failed() == 0;
        }
    }

    public record TestResults(int run, int failed, int passed) {
    }
}
