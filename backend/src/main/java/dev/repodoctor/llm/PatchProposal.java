package dev.repodoctor.llm;

import java.util.List;

/**
 * Patch proposal from Gemini Pro with structured output.
 */
public record PatchProposal(
        String unifiedDiff,
        String explanation,
        String confidenceNotes,
        List<String> touchedFiles,
        String riskLevel) {
    public boolean isValid() {
        if (unifiedDiff == null || unifiedDiff.isBlank() || explanation == null || riskLevel == null) {
            return false;
        }

        // Check for git diff header (can be at start or after newline)
        boolean hasDiffHeader = unifiedDiff.startsWith("diff --git ") || unifiedDiff.contains("\ndiff --git ");

        // Check for file headers (more lenient - allow both with and without leading newline)
        boolean hasMinusHeader = unifiedDiff.startsWith("--- ") || unifiedDiff.contains("\n--- ");
        boolean hasPlusHeader = unifiedDiff.startsWith("+++ ") || unifiedDiff.contains("\n+++ ");

        // Check for hunk markers (allow at start or after newline)
        boolean hasHunks = unifiedDiff.startsWith("@@") || unifiedDiff.contains("\n@@");

        return hasDiffHeader && hasMinusHeader && hasPlusHeader && hasHunks;
    }
}
