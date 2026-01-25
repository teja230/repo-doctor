package dev.repodoctor.model;

public enum AttemptStatus {
    PENDING,
    RUNNING,
    ANALYZING,
    PATCHING,
    PATCH_APPLIED,
    PATCH_FAILED,
    SUCCESS,
    FAILED,
    LLM_ERROR,
    LLM_INVALID_OUTPUT,
    TIMEOUT,
    RATE_LIMIT_PAUSE
}
