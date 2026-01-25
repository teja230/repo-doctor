package dev.repodoctor.llm;

import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Mock LLM client for testing without Gemini API key.
 * Returns predictable responses for development and testing.
 */
@Component
public class MockLLMClient implements LLMClient {

    @Override
    public FailureSummary summarizeFailure(String logTail, String tool, String command) {
        // Parse simple patterns from logs
        String failureType = "TEST_FAILURE";
        String primaryError = "Mock analysis: Test assertion failed";

        if (logTail.contains("CompilationError") || logTail.contains("cannot find symbol")) {
            failureType = "COMPILATION_ERROR";
            primaryError = "Mock analysis: Compilation failed";
        } else if (logTail.contains("NullPointerException")) {
            failureType = "RUNTIME_ERROR";
            primaryError = "Mock analysis: NullPointerException occurred";
        }

        return new FailureSummary(
                failureType,
                primaryError,
                List.of("MockTest.testExample"),
                List.of("at com.example.MockTest.testExample(MockTest.java:10)"),
                "Check test assertions and expected values",
                5);
    }

    @Override
    public PatchProposal proposePatch(PatchContext context) {
        // Return a mock patch that won't actually fix anything
        // but demonstrates the structure
        String mockDiff = """
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1,2 @@
                 # Project
                +# Mock patch applied - this is a placeholder
                """;

        return new PatchProposal(
                mockDiff,
                "Mock LLM: This is a placeholder patch. Configure GEMINI_API_KEY for real fixes.",
                "This mock patch will not fix actual issues. It demonstrates the patch format.",
                List.of("README.md"),
                "LOW");
    }

    @Override
    public void clearHistory(String jobId) {
        // No-op for mock
    }

    @Override
    public boolean isConfigured() {
        return false; // Mock is always "not configured" for real use
    }
}
