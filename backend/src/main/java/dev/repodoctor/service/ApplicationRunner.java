package dev.repodoctor.service;

import dev.repodoctor.model.BuildTool;

import java.nio.file.Path;

/**
 * Abstraction for running application builds and tests in isolated or direct
 * environments.
 * Implementations: DockerRunner (sandboxed), DirectRunner (fast, for managed
 * platforms)
 */
public interface ApplicationRunner {

    /**
     * Detect the build tool based on project files in the workspace.
     */
    BuildTool detectBuildTool(Path workspacePath);

    /**
     * Run tests in the environment.
     * 
     * @param workspacePath Path to the project workspace
     * @param buildTool     The detected build tool (Maven, Gradle, Node)
     * @param allowNetwork  Whether to allow network access during test execution
     * @return Execution result with exit code, logs, and parsed test results
     */
    ExecutionResult runTests(Path workspacePath, BuildTool buildTool, boolean allowNetwork);

    /**
     * Result of a test execution.
     */
    record ExecutionResult(
            int exitCode,
            String logs,
            long durationMs,
            TestResults testResults) {
        public boolean isSuccess() {
            return exitCode == 0 && testResults.failed() == 0;
        }
    }

    /**
     * Parsed test results from build output.
     */
    record TestResults(int run, int failed, int passed) {
    }
}
