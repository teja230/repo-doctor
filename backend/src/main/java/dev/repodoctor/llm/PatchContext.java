package dev.repodoctor.llm;

import dev.repodoctor.model.BuildTool;
import java.util.List;

/**
 * Context for generating a patch proposal.
 */
public record PatchContext(
        String jobId,
        BuildTool buildTool,
        String command,
        String repoTree,
        String fileContext,
        String logTail,
        FailureSummary failureSummary,
        List<PriorAttempt> priorAttempts) {
    public record PriorAttempt(
            int attemptNumber,
            String diffSummary,
            String outcome,
            String errorMessage) {
    }
}
