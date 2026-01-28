package dev.repodoctor.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repodoctor.config.RepoDoctorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;
import java.time.Duration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gemini 3 client implementation leveraging:
 * - Thinking control (high for patching, minimal for summarization)
 * - Structured JSON outputs with strict schema validation
 * - Multi-turn conversation history for thought signatures
 */
@Component
@Primary
@ConditionalOnProperty(name = "repodoctor.gemini.api-key")
public class GeminiClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final RepoDoctorConfig config;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // Multi-turn conversation history per job (thought signatures)
    private final Map<String, List<Map<String, Object>>> conversationHistory = new ConcurrentHashMap<>();

    private static final String GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    public GeminiClient(RepoDoctorConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(GEMINI_API_BASE)
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        log.info("GeminiClient initialized with flash-model={}, pro-model={}",
                config.getGemini().getFlashModel(),
                config.getGemini().getProModel());
    }

    @Override
    public FailureSummary summarizeFailure(String logTail, String tool, String command) {
        String prompt = buildSummarizePrompt(logTail, tool, command);

        Map<String, Object> request = buildRequest(
                config.getGemini().getFlashModel(),
                prompt,
                "minimal", // Fast thinking for log analysis
                FAILURE_SUMMARY_SCHEMA);

        try {
            String response = callGemini(config.getGemini().getFlashModel(), request);
            return parseFailureSummary(response);
        } catch (Exception e) {
            log.error("Failed to summarize failure", e);
            return new FailureSummary(
                    "UNKNOWN",
                    "Failed to analyze logs: " + e.getMessage(),
                    List.of(),
                    List.of(),
                    "Review logs manually",
                    5);
        }
    }

    @Override
    public PatchProposal proposePatch(PatchContext context) {
        String prompt = buildPatchPrompt(context);

        // Get or create conversation history for this job
        List<Map<String, Object>> history = conversationHistory.computeIfAbsent(
                context.jobId(), k -> new ArrayList<>());

        // Add user message to history
        history.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", prompt))));

        Map<String, Object> request = buildRequestWithHistory(
                config.getGemini().getProModel(),
                history,
                "low", // Fast thinking for patch generation
                PATCH_PROPOSAL_SCHEMA);

        try {
            String response = callGemini(config.getGemini().getProModel(), request);
            PatchProposal proposal = parsePatchProposal(response);

            // Add assistant response to history for multi-turn reasoning
            history.add(Map.of(
                    "role", "model",
                    "parts", List.of(Map.of("text", response))));

            return proposal;
        } catch (LLMServiceUnavailableException e) {
            // Re-throw service unavailable exceptions so they can be handled specially
            log.error("Gemini service unavailable", e);
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate patch", e);
            // Return empty proposal for other errors - will be caught by validation
            return new PatchProposal(
                    "",
                    "Failed to generate patch: " + e.getMessage(),
                    "LLM error occurred",
                    List.of(),
                    "HIGH");
        }
    }

    @Override
    public void clearHistory(String jobId) {
        conversationHistory.remove(jobId);
    }

    @Override
    public boolean isConfigured() {
        return config.getGemini().isConfigured();
    }

    private Map<String, Object> buildRequest(String model, String prompt, String thinkingLevel,
            Map<String, Object> schema) {
        Map<String, Object> request = new HashMap<>();

        // Contents
        request.put("contents", List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))));

        // Generation config with thinking control
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("temperature", 0.2);
        generationConfig.put("topP", 0.8);
        generationConfig.put("maxOutputTokens", 65536);

        // Generation-aware thinking config
        log.info("Building request for model: '{}', thinkingLevel: {}", model, thinkingLevel);
        if (model.startsWith("gemini-3-")) {
            // Gemini 3 uses thinkingLevel (HIGH, MEDIUM, LOW, MINIMAL)
            generationConfig.put("thinkingConfig", Map.of("thinkingLevel", thinkingLevel.toUpperCase()));
            log.info("Using Gemini 3 thinking config with level: {}", thinkingLevel.toUpperCase());
        } else if (model.toLowerCase().contains("thinking")) {
            // Gemini 2.0 Flash Thinking uses thinkingBudget
            int thinkingBudget = "minimal".equalsIgnoreCase(thinkingLevel) ? 0 : 8192;
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget));
            log.info("Using Gemini 2.0 thinking config with budget: {}", thinkingBudget);
        } else {
            log.info("No thinking config applied for model: {}", model);
        }

        // Response schema for structured output
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", schema);

        request.put("generationConfig", generationConfig);

        return request;
    }

    private Map<String, Object> buildRequestWithHistory(String model, List<Map<String, Object>> history,
            String thinkingLevel, Map<String, Object> schema) {
        Map<String, Object> request = new HashMap<>();

        // Include full conversation history for multi-turn reasoning
        request.put("contents", history);

        // Generation config with thinking control
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("temperature", 0.2);
        generationConfig.put("topP", 0.8);
        generationConfig.put("maxOutputTokens", 65536);

        // Generation-aware thinking config
        log.info("Building request with history for model: '{}', thinkingLevel: {}", model, thinkingLevel);
        if (model.startsWith("gemini-3-")) {
            // Gemini 3 uses thinkingLevel (HIGH, MEDIUM, LOW, MINIMAL)
            generationConfig.put("thinkingConfig", Map.of("thinkingLevel", thinkingLevel.toUpperCase()));
            log.info("Using Gemini 3 thinking config with level: {}", thinkingLevel.toUpperCase());
        } else if (model.toLowerCase().contains("thinking")) {
            // Gemini 2.0 Flash Thinking uses thinkingBudget
            int thinkingBudget = "minimal".equalsIgnoreCase(thinkingLevel) ? 0 : 8192;
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget));
            log.info("Using Gemini 2.0 thinking config with budget: {}", thinkingBudget);
        } else {
            log.info("No thinking config applied for model: {}", model);
        }

        // Response schema for structured output
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", schema);

        request.put("generationConfig", generationConfig);

        // System instruction
        request.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", SYSTEM_INSTRUCTION))));

        return request;
    }

    @SuppressWarnings("unchecked")
    private String callGemini(String model, Map<String, Object> request) {
        long startTime = System.currentTimeMillis();

        // Log request summary
        String thinkingLevel = "unknown";
        int promptLength = 0;
        try {
            Map<String, Object> genConfig = (Map<String, Object>) request.get("generationConfig");
            if (genConfig != null) {
                Map<String, Object> thinkingConfig = (Map<String, Object>) genConfig.get("thinkingConfig");
                if (thinkingConfig != null) {
                    thinkingLevel = String.valueOf(thinkingConfig.getOrDefault("thinkingLevel",
                            thinkingConfig.getOrDefault("thinkingBudget", "unknown")));
                }
            }
            List<Map<String, Object>> contents = (List<Map<String, Object>>) request.get("contents");
            if (contents != null && !contents.isEmpty()) {
                Object parts = contents.get(0).get("parts");
                if (parts != null) {
                    promptLength = parts.toString().length();
                }
            }
        } catch (Exception e) {
            // Ignore errors in logging metadata
        }

        log.info("→ Calling Gemini API: model={}, thinking={}, promptSize={}KB",
                model, thinkingLevel, promptLength / 1024);

        // Log the full request for debugging
        try {
            String requestJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
            log.debug("Full request body: {}", requestJson);
        } catch (Exception e) {
            log.warn("Failed to serialize request for logging", e);
        }

        // Build the URI to log it
        String uri = GEMINI_API_BASE + model + ":generateContent?key=" +
                (config.getGemini().getApiKey().substring(0, Math.min(10, config.getGemini().getApiKey().length()))
                        + "...");
        log.info("Request URI: {}", uri.replace(
                config.getGemini().getApiKey().substring(0, Math.min(10, config.getGemini().getApiKey().length())),
                "***"));

        String responseBody;
        try {
            responseBody = webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(model + ":generateContent")
                            .queryParam("key", config.getGemini().getApiKey())
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(120)) // 120 second timeout for complex patch generation
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(30))
                            .filter(throwable -> throwable instanceof WebClientResponseException.TooManyRequests)
                            .doBeforeRetry(
                                    retrySignal -> log.warn("Gemini API rate limited (429). Retrying... (Attempt {})",
                                            retrySignal.totalRetriesInARow() + 1))
                            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure()))
                    .doOnError(throwable -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        log.error("✗ Gemini API call failed after {}ms: {}", elapsed, throwable.getMessage(),
                                throwable);
                        if (throwable instanceof WebClientResponseException) {
                            WebClientResponseException ex = (WebClientResponseException) throwable;
                            log.error("Response status: {}, body: {}", ex.getStatusCode(),
                                    ex.getResponseBodyAsString());
                        }
                    })
                    .block();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Exception during Gemini API call after {}ms", elapsed, e);

            // Check if this is a 503 Service Unavailable error
            if (e instanceof WebClientResponseException) {
                WebClientResponseException webEx = (WebClientResponseException) e;
                if (webEx.getStatusCode().value() == 503) {
                    throw new LLMServiceUnavailableException(
                            "Gemini API is temporarily unavailable (overloaded). Please try again in a few minutes.",
                            e);
                }
            }

            throw new RuntimeException("Failed to call Gemini API: " + e.getMessage(), e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("✓ Gemini API responded in {}ms, size={}KB", elapsed,
                responseBody != null ? responseBody.length() / 1024 : 0);

        // Validate response is not null or empty
        if (responseBody == null || responseBody.trim().isEmpty()) {
            log.error("Gemini API returned null or empty response");
            throw new RuntimeException("Empty response from Gemini API");
        }

        // Extract text from response
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Check for error response
            if (root.has("error")) {
                String errorMessage = root.path("error").path("message").asText("Unknown error");
                String errorCode = root.path("error").path("code").asText("UNKNOWN");
                log.error("Gemini API returned error: code={}, message={}", errorCode, errorMessage);
                throw new RuntimeException("Gemini API error: " + errorMessage);
            }

            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                // Check for truncation due to token limit
                String finishReason = candidates.get(0).path("finishReason").asText("STOP");
                log.info("Gemini finishReason: {}", finishReason);

                if ("MAX_TOKENS".equals(finishReason)) {
                    log.error("Gemini response truncated due to token limit (finishReason=MAX_TOKENS)");
                    log.error("Full response: {}", responseBody);
                    throw new RuntimeException("Response truncated: output exceeded maxOutputTokens limit");
                }

                JsonNode content = candidates.get(0).path("content");
                JsonNode parts = content.path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    // Thinking models (Gemini 2.0/3.0) return 'thought' parts followed by 'text'
                    // parts.
                    // We must find the first part that contains 'text'.
                    for (JsonNode part : parts) {
                        if (part.has("text")) {
                            String textResponse = part.get("text").asText();
                            log.info("Extracted text response length: {} chars", textResponse.length());

                            if (textResponse.trim().isEmpty()) {
                                log.error("Gemini returned empty text response. Full response: {}", responseBody);
                                throw new RuntimeException("Empty text in Gemini response");
                            }

                            return textResponse;
                        }
                    }
                    log.error("Gemini response has parts but no 'text' part found. Full response: {}", responseBody);
                    throw new RuntimeException("No text part found in Gemini response");
                } else {
                    log.error("Gemini response has no parts. Full response: {}", responseBody);
                    throw new RuntimeException("No parts in Gemini response");
                }
            } else {
                log.error("Gemini response has no candidates. Full response: {}", responseBody);
                throw new RuntimeException("No candidates in Gemini response");
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Gemini response JSON: {}", responseBody, e);
            throw new RuntimeException("Failed to parse Gemini response: " + e.getMessage(), e);
        }
    }

    private String buildSummarizePrompt(String logTail, String tool, String command) {
        return """
                Analyze this build/test failure log and extract structured information.

                Build tool: %s
                Command: %s

                Log output (last ~400 lines):
                ```
                %s
                ```

                Return a JSON object with the failure analysis.
                """.formatted(tool, command, logTail);
    }

    private String buildPatchPrompt(PatchContext context) {
        StringBuilder priorDiffs = new StringBuilder();
        if (context.priorAttempts() != null && !context.priorAttempts().isEmpty()) {
            for (var prior : context.priorAttempts()) {
                priorDiffs.append("Attempt ").append(prior.attemptNumber())
                        .append(" (").append(prior.outcome()).append("): ")
                        .append(prior.diffSummary());
                if (prior.errorMessage() != null && !prior.errorMessage().isBlank()) {
                    priorDiffs.append(" Error: ").append(prior.errorMessage());
                }
                priorDiffs.append("\n");
            }
        }

        String failureSummaryJson = "";
        try {
            failureSummaryJson = objectMapper.writeValueAsString(context.failureSummary());
        } catch (JsonProcessingException e) {
            failureSummaryJson = "{}";
        }

        return PATCH_PROMPT_TEMPLATE
                .replace("{{BUILD_TOOL}}", context.buildTool().getName())
                .replace("{{COMMAND}}", context.command())
                .replace("{{TREE}}", context.repoTree())
                .replace("{{FILE_CONTEXT}}", context.fileContext())
                .replace("{{PRIOR_DIFFS_SUMMARY}}", priorDiffs.toString())
                .replace("{{FAILURE_SUMMARY_JSON}}", failureSummaryJson)
                .replace("{{LOG_TAIL}}", context.logTail());
    }

    private FailureSummary parseFailureSummary(String json) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(json);
        return new FailureSummary(
                node.path("failureType").asText("UNKNOWN"),
                node.path("primaryError").asText("Unknown error"),
                parseStringList(node.path("failingTests")),
                parseStringList(node.path("stackTraceSnippets")),
                node.path("suggestedFocus").asText(""),
                node.path("severity").asInt(5));
    }

    private PatchProposal parsePatchProposal(String json) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(json);

        // Validate JSON structure
        if (!node.has("unified_diff")) {
            log.error("Gemini response missing 'unified_diff' field. Full JSON: {}", json);
            throw new RuntimeException("Invalid patch proposal: missing unified_diff field");
        }

        String diff = node.path("unified_diff").asText("");

        // Log diff details for debugging (INFO level for visibility)
        if (diff.length() > 0) {
            log.info("Parsing patch proposal. unified_diff length: {}, first 300 chars: {}",
                    diff.length(), diff.substring(0, Math.min(300, diff.length())));
            // Log the raw JSON field to see if escaping is correct
            log.debug("Raw JSON unified_diff value: {}",
                    node.path("unified_diff").toString().substring(0,
                            Math.min(500, node.path("unified_diff").toString().length())));
        } else {
            log.error("Parsing patch proposal: unified_diff is EMPTY. Full JSON: {}", json);
            throw new RuntimeException("Invalid patch proposal: unified_diff is empty");
        }

        // Validate that diff looks like a proper unified diff
        if (!diff.trim().startsWith("diff --git")) {
            log.warn("unified_diff doesn't start with 'diff --git'. First 100 chars: {}",
                    diff.substring(0, Math.min(100, diff.length())));
        }

        return new PatchProposal(
                diff,
                node.path("explanation").asText(""),
                node.path("confidence_notes").asText(""),
                parseStringList(node.path("touched_files")),
                node.path("risk_level").asText("MEDIUM"));
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                result.add(item.asText());
            }
        }
        return result;
    }

    // System instruction for patch generation
    private static final String SYSTEM_INSTRUCTION = """
            You are RepoDoctor, an automated agent for improving code and fixing builds. Your goal is to:
            1. Fix any failing builds or tests (top priority).
            2. If tests pass or are missing, suggest high-quality code improvements, best practices, refactoring, or help write initial tests.
            3. Always ensure your changes are safe and the codebase remains stable.
            4. If applying improvements, focus on readability, performance, or modern patterns.
            5. Provide clear, concise explanations for your changes.

            CRITICAL RULES FOR unified_diff:
            1. The unified_diff MUST be a COMPLETE, valid git-style unified diff
            2. NEVER truncate or abbreviate the diff - include ALL changed and context lines
            3. Line numbers in @@ headers MUST match the ACTUAL line numbers in the source file
            4. Count from line 1, including package statements, imports, comments, and blank lines
            5. Context lines (starting with single space) must EXACTLY match the original file
            6. Removed lines start with '-', added lines start with '+'
            7. Include 3 lines of context before and after each change
            8. The replacement line (starting with '+') must contain the COMPLETE new code
            9. Do NOT leave the '+' line empty or incomplete
            10. The hunk line count in @@ headers MUST match the actual number of lines in the hunk
            11. Each hunk must end with a complete line (including trailing newline)
            12. Preserve all whitespace and indentation EXACTLY as in the original file

            COMMON MISTAKES TO AVOID:
            - Wrong line numbers (forgetting to count package/import/comment lines)
            - Truncated replacement lines ('+' without the actual code)
            - Missing closing braces, semicolons, or parentheses
            - Incorrect indentation on context or changed lines

            OUTPUT FORMAT:
            - Output MUST be valid JSON matching the schema exactly
            - The unified_diff string must properly escape newlines as \\n
            - Do not use markdown code blocks, just raw JSON

            EXAMPLE - If file is:
            Line 1: package com.example;
            Line 2: (blank)
            Line 3: public class Calc {
            Line 4:     public int add(int a, int b) {
            Line 5:         return a - b; // BUG
            Line 6:     }
            Line 7: }

            Then the diff to fix line 5 would be:
            diff --git a/src/Calc.java b/src/Calc.java
            --- a/src/Calc.java
            +++ b/src/Calc.java
            @@ -2,7 +2,7 @@

             public class Calc {
                 public int add(int a, int b) {
            -        return a - b; // BUG
            +        return a + b;
                 }
             }
            """;

    // Patch prompt template
    private static final String PATCH_PROMPT_TEMPLATE = """
            Context:
            - Build tool: {{BUILD_TOOL}}
            - Command run: {{COMMAND}}
            - Repo tree (top-level): {{TREE}}
            - Relevant files (full content with LINE NUMBERS): {{FILE_CONTEXT}}
            - Previous attempt diffs (if any): {{PRIOR_DIFFS_SUMMARY}}
            - Current State/Failure summary: {{FAILURE_SUMMARY_JSON}}
            - Log tail (if failing): {{LOG_TAIL}}

            OBJECTIVE:
            - If "failureType" is "SUCCESS" or "NO_TESTS_FOUND", propose a set of code improvements, refactors, or new tests. Focus on making the code state-of-the-art.
            - If "failureType" is a specific error (e.g., COMPILATION_ERROR, TEST_FAILURE), prioritize fixing that error first.

            IMPORTANT INSTRUCTIONS:
            1. Look at the file content carefully - note the EXACT line numbers
            2. When creating the diff, use the CORRECT line numbers from the file content
            3. The @@ header must reference the actual line numbers where the change occurs
            4. COMPLETE the replacement line - do not leave it empty or partial
            5. Include 3 lines of unchanged context before and after your changes
            6. Make sure the '+' lines contain the FULL corrected code
            7. Ensure your diff is a valid git diff format starting with 'diff --git'.

            Return JSON only. No markdown. No code fences. The unified_diff field must be a complete, valid git diff.
            """;

    // JSON Schema for FailureSummary structured output
    private static final Map<String, Object> FAILURE_SUMMARY_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "failureType",
                    Map.of("type", "string", "enum",
                            List.of("COMPILATION_ERROR", "TEST_FAILURE", "RUNTIME_ERROR", "DEPENDENCY_ERROR",
                                    "CONFIGURATION_ERROR", "SUCCESS", "NO_TESTS_FOUND", "UNKNOWN")),
                    "primaryError", Map.of("type", "string"),
                    "failingTests", Map.of("type", "array", "items", Map.of("type", "string")),
                    "stackTraceSnippets", Map.of("type", "array", "items", Map.of("type", "string")),
                    "suggestedFocus", Map.of("type", "string"),
                    "severity", Map.of("type", "integer", "minimum", 1, "maximum", 10)),
            "required", List.of("failureType", "primaryError", "failingTests", "severity"));

    // JSON Schema for PatchProposal structured output
    private static final Map<String, Object> PATCH_PROPOSAL_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "unified_diff", Map.of(
                            "type", "string",
                            "description",
                            "A valid unified diff in git format. MUST start with 'diff --git a/path b/path', followed by '--- a/path' and '+++ b/path' headers, then '@@ line,count line,count @@' hunks. Use proper context lines (starting with space) that match the original file exactly."),
                    "explanation", Map.of("type", "string"),
                    "confidence_notes", Map.of("type", "string"),
                    "touched_files", Map.of("type", "array", "items", Map.of("type", "string")),
                    "risk_level", Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH"))),
            "required", List.of("unified_diff", "explanation", "confidence_notes", "touched_files", "risk_level"));
}
