package dev.repodoctor.service;

import dev.repodoctor.model.BuildTool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes baseline test failures without using AI/LLM.
 * Provides quick insights based on log patterns.
 */
@Service
public class BaselineAnalyzer {

    public BaselineAnalysis analyzeFailure(String logs, BuildTool buildTool, int testsFailed, int testsRun) {
        List<String> failedTests = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();
        String failureType = "TEST_FAILURE";
        String summary = "";

        // Extract failed test names and error messages based on build tool
        switch (buildTool) {
            case MAVEN -> {
                extractMavenFailures(logs, failedTests, errorMessages);
            }
            case GRADLE -> {
                extractGradleFailures(logs, failedTests, errorMessages);
            }
            case NODE -> {
                extractNodeFailures(logs, failedTests, errorMessages);
            }
            case PYTHON -> {
                extractPythonFailures(logs, failedTests, errorMessages);
            }
            default -> {
                failureType = "UNKNOWN";
            }
        }

        // Determine failure type based on patterns
        if (logs.contains("CompilationError") || logs.contains("cannot find symbol") ||
                logs.contains("package does not exist")) {
            failureType = "COMPILATION_ERROR";
            summary = "Build failed due to compilation errors. Check for missing imports, typos, or incorrect method signatures.";
        } else if (logs.contains("NullPointerException")) {
            failureType = "NULL_POINTER_ERROR";
            summary = "Tests are failing with NullPointerException. Check for uninitialized variables or missing null checks.";
        } else if (logs.contains("AssertionError") || logs.contains("expected") || logs.contains("but was")) {
            failureType = "ASSERTION_FAILURE";
            summary = generateAssertionSummary(errorMessages, testsFailed, testsRun);
        } else if (logs.contains("ClassNotFoundException") || logs.contains("NoClassDefFoundError")) {
            failureType = "DEPENDENCY_ERROR";
            summary = "Missing dependencies or classpath issues. Check your project dependencies.";
        } else if (logs.contains("timeout") || logs.contains("Timeout")) {
            failureType = "TIMEOUT";
            summary = "Tests are timing out. Consider increasing timeout values or optimizing slow operations.";
        } else if (testsFailed > 0) {
            summary = String.format("%d out of %d tests failed. Review the test logs for specific error messages.",
                    testsFailed, testsRun);
        }

        return new BaselineAnalysis(
                failureType,
                summary,
                failedTests,
                errorMessages,
                testsFailed,
                testsRun);
    }

    private void extractMavenFailures(String logs, List<String> failedTests, List<String> errorMessages) {
        // Pattern: " testMethodName(com.example.ClassName) Time elapsed: 0.001 s <<<
        // FAILURE!"
        Pattern testPattern = Pattern.compile("\\s+(\\w+)\\(([\\w.]+)\\).*?<<<\\s*FAILURE!");
        Matcher matcher = testPattern.matcher(logs);
        while (matcher.find()) {
            String testMethod = matcher.group(1);
            String className = matcher.group(2);
            failedTests.add(className + "." + testMethod);
        }

        // Extract assertion errors
        Pattern assertPattern = Pattern.compile("(?:Expected|expected)\\s*:?\\s*([^\\n]+)");
        Matcher assertMatcher = assertPattern.matcher(logs);
        while (assertMatcher.find() && errorMessages.size() < 5) {
            errorMessages.add(assertMatcher.group(0).trim());
        }
    }

    private void extractGradleFailures(String logs, List<String> failedTests, List<String> errorMessages) {
        // Pattern: "com.example.TestClass > testMethod FAILED"
        Pattern testPattern = Pattern.compile("([\\w.]+)\\s*>\\s*(\\w+)\\s+FAILED");
        Matcher matcher = testPattern.matcher(logs);
        while (matcher.find()) {
            String className = matcher.group(1);
            String testMethod = matcher.group(2);
            failedTests.add(className + "." + testMethod);
        }

        extractCommonErrors(logs, errorMessages);
    }

    private void extractNodeFailures(String logs, List<String> failedTests, List<String> errorMessages) {
        // Pattern: "✕ test description"
        Pattern testPattern = Pattern.compile("✕\\s+(.+)");
        Matcher matcher = testPattern.matcher(logs);
        while (matcher.find()) {
            failedTests.add(matcher.group(1).trim());
        }

        extractCommonErrors(logs, errorMessages);
    }

    private void extractPythonFailures(String logs, List<String> failedTests, List<String> errorMessages) {
        // Pattern: "FAILED tests/test_example.py::test_name - AssertionError"
        Pattern testPattern = Pattern.compile("FAILED\\s+([\\w/._]+::\\w+)");
        Matcher matcher = testPattern.matcher(logs);
        while (matcher.find()) {
            failedTests.add(matcher.group(1).trim());
        }

        // Also try pattern: "test_name FAILED"
        Pattern altPattern = Pattern.compile("(test_\\w+)\\s+FAILED");
        Matcher altMatcher = altPattern.matcher(logs);
        while (altMatcher.find()) {
            String test = altMatcher.group(1).trim();
            if (!failedTests.contains(test)) {
                failedTests.add(test);
            }
        }

        // Extract assertion errors from pytest output
        Pattern assertPattern = Pattern.compile("(?:AssertionError|assert)\\s*:?\\s*([^\\n]{10,100})");
        Matcher assertMatcher = assertPattern.matcher(logs);
        while (assertMatcher.find() && errorMessages.size() < 5) {
            errorMessages.add(assertMatcher.group(0).trim());
        }

        extractCommonErrors(logs, errorMessages);
    }

    private void extractCommonErrors(String logs, List<String> errorMessages) {
        // Extract common error patterns
        Pattern errorPattern = Pattern.compile("(?:Error|Exception|expected|assertion failed):?\\s*([^\\n]{20,100})");
        Matcher matcher = errorPattern.matcher(logs);
        while (matcher.find() && errorMessages.size() < 5) {
            errorMessages.add(matcher.group(0).trim());
        }
    }

    private String generateAssertionSummary(List<String> errorMessages, int failed, int total) {
        if (errorMessages.isEmpty()) {
            return String.format(
                    "%d test assertion(s) failed out of %d tests. Check the logs for expected vs actual values.",
                    failed, total);
        }

        StringBuilder summary = new StringBuilder(
                String.format("%d test(s) failed with assertion errors:\n", failed));

        for (int i = 0; i < Math.min(3, errorMessages.size()); i++) {
            summary.append("• ").append(errorMessages.get(i)).append("\n");
        }

        if (errorMessages.size() > 3) {
            summary.append("... and ").append(errorMessages.size() - 3).append(" more error(s)");
        }

        return summary.toString();
    }

    public record BaselineAnalysis(
            String failureType,
            String summary,
            List<String> failedTests,
            List<String> errorMessages,
            int failedCount,
            int totalCount) {
    }
}
