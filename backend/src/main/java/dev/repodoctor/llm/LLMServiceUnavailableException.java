package dev.repodoctor.llm;

/**
 * Exception thrown when the LLM service (e.g., Gemini API) is temporarily unavailable.
 * This typically indicates a 503 Service Unavailable error, meaning the service is
 * overloaded or under maintenance.
 *
 * Unlike other LLM errors, this is a temporary condition and users should retry later.
 */
public class LLMServiceUnavailableException extends RuntimeException {

    public LLMServiceUnavailableException(String message) {
        super(message);
    }

    public LLMServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
