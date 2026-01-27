package dev.repodoctor.service;

import dev.repodoctor.config.RepoDoctorConfig;
import dev.repodoctor.model.BuildTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.stream.Stream;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Direct process execution without Docker isolation.
 * Best for: Render, Railway, and other managed platforms.
 * 
 * Trade-off: No sandboxing, but simpler deployment.
 */
@Service
public class DirectRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DirectRunner.class);

    private final RepoDoctorConfig config;

    public DirectRunner(RepoDoctorConfig config) {
        this.config = config;
    }

    @Override
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
        if (isPythonProject(workspacePath)) {
            return BuildTool.PYTHON;
        }
        return BuildTool.UNKNOWN;
    }

    /**
     * Check if the workspace is a Python project.
     * Detects: pyproject.toml, pytest.ini, setup.py, setup.cfg, tox.ini, or
     * test_*.py files.
     */
    private boolean isPythonProject(Path workspacePath) {
        // Check for common Python project markers
        if (Files.exists(workspacePath.resolve("pyproject.toml")) ||
                Files.exists(workspacePath.resolve("pytest.ini")) ||
                Files.exists(workspacePath.resolve("setup.py")) ||
                Files.exists(workspacePath.resolve("setup.cfg")) ||
                Files.exists(workspacePath.resolve("tox.ini")) ||
                Files.exists(workspacePath.resolve("requirements.txt"))) {
            return true;
        }

        // Check for test_*.py files in common locations
        try {
            Path testsDir = workspacePath.resolve("tests");
            Path testDir = workspacePath.resolve("test");

            if (Files.isDirectory(testsDir) && hasTestFiles(testsDir)) {
                return true;
            }
            if (Files.isDirectory(testDir) && hasTestFiles(testDir)) {
                return true;
            }
            // Check root directory for test files
            return hasTestFiles(workspacePath);
        } catch (Exception e) {
            log.warn("Error checking for Python test files: {}", e.getMessage());
            return false;
        }
    }

    private boolean hasTestFiles(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("test_") && name.endsWith(".py") && Files.isRegularFile(p);
            });
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public ExecutionResult runTests(Path workspacePath, BuildTool buildTool, boolean allowNetwork) {
        String command = buildTool.getTestCommand();

        log.info("Running tests directly: {} in {}", command, workspacePath);

        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(workspacePath.toFile());
        pb.command(parseCommand(command));
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
                logs.append("\n[TIMEOUT] Test execution exceeded ")
                        .append(config.getContainerTimeoutSeconds()).append(" seconds\n");
                exitCode = -1;
            }

        } catch (Exception e) {
            log.error("Direct execution failed", e);
            logs.append("\n[ERROR] Test execution failed: ").append(e.getMessage()).append("\n");
            exitCode = -1;
        } finally {
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

    /**
     * Parse command string into array for ProcessBuilder.
     */
    private String[] parseCommand(String command) {
        // Handle commands like "mvn test" or "./gradlew test"
        return command.split("\\s+");
    }

    private TestResults parseTestResults(String logs, BuildTool buildTool) {
        int testsRun = 0;
        int testsFailed = 0;
        int testsPassed = 0;

        switch (buildTool) {
            case MAVEN -> {
                var pattern = Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)");
                var matcher = pattern.matcher(logs);
                while (matcher.find()) {
                    testsRun += Integer.parseInt(matcher.group(1));
                    testsFailed += Integer.parseInt(matcher.group(2)) + Integer.parseInt(matcher.group(3));
                }
                testsPassed = testsRun - testsFailed;
            }
            case GRADLE -> {
                var pattern = Pattern.compile("(\\d+) tests? completed, (\\d+) failed");
                var matcher = pattern.matcher(logs);
                if (matcher.find()) {
                    testsRun = Integer.parseInt(matcher.group(1));
                    testsFailed = Integer.parseInt(matcher.group(2));
                    testsPassed = testsRun - testsFailed;
                }
            }
            case NODE -> {
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
            case PYTHON -> {
                // Parse pytest output: "5 passed, 2 failed in 0.12s" or "5 passed in 0.12s"
                var passPattern = Pattern.compile("(\\d+) passed");
                var failPattern = Pattern.compile("(\\d+) failed");
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
                // Unknown build tool
            }
        }

        return new TestResults(testsRun, testsFailed, testsPassed);
    }
}
