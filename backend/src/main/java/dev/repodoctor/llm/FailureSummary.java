package dev.repodoctor.llm;

import java.util.List;

/**
 * Structured failure summary from log analysis.
 */
public record FailureSummary(
        String failureType,
        String primaryError,
        List<String> failingTests,
        List<String> stackTraceSnippets,
        String suggestedFocus,
        int severity) {
}
