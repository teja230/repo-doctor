package dev.repodoctor.llm;

/**
 * Interface for LLM operations.
 * Implementations: GeminiClient (production), MockLLMClient (testing)
 */
public interface LLMClient {

    /**
     * Summarize test/build failure from logs.
     * Uses Gemini Flash with thinking_level="minimal" for fast extraction.
     * 
     * @param logTail last ~400 lines of build output
     * @param tool    build tool name (maven, gradle, npm)
     * @param command the command that was run
     * @return structured failure summary
     */
    FailureSummary summarizeFailure(String logTail, String tool, String command);

    /**
     * Propose a patch to fix the failing tests.
     * Uses Gemini Pro with thinking_level="high" for deep reasoning.
     * Maintains conversation history for multi-turn reasoning.
     * 
     * @param context full context including repo tree, logs, prior attempts
     * @return structured patch proposal with unified diff
     */
    PatchProposal proposePatch(PatchContext context);

    /**
     * Clear conversation history for a job.
     * Called when job completes or is cancelled.
     */
    void clearHistory(String jobId);

    /**
     * Check if the LLM client is properly configured.
     */
    boolean isConfigured();
}
