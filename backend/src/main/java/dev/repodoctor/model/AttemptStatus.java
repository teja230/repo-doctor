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
    LLM_SERVICE_UNAVAILABLE,  // Gemini API temporarily down (503)
    TIMEOUT,
    RATE_LIMIT_PAUSE
}
