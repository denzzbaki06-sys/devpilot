package com.devpilot.rag;
import java.util.List;
public interface LlmProvider {
    record Statement(String text, List<Integer> sourceIds) {
        @Override public String toString() { return "Statement[REDACTED]"; }
    }
    record Completion(List<Statement> statements, boolean insufficientContext, String model) {
        @Override public String toString() { return "Completion[REDACTED]"; }
    }
    /** Structured workflows share this provider and HTTP transport; no separate AI client. */
    default tools.jackson.databind.JsonNode completeStructured(String systemInstruction, java.util.Map<String, Object> input,
            String schemaName, java.util.Map<String, Object> schema) {
        throw new com.devpilot.exception.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Structured chat workflow is not supported by this provider");
    }
    void requireConfigured();
    String model();
    Completion complete(String systemInstruction, String question, String context);
}
