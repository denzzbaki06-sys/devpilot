package com.devpilot.rag;
import java.util.List;
public interface LlmProvider {
    record Statement(String text, List<Integer> sourceIds) {
        @Override public String toString() { return "Statement[REDACTED]"; }
    }
    record Completion(List<Statement> statements, boolean insufficientContext, String model) {
        @Override public String toString() { return "Completion[REDACTED]"; }
    }
    void requireConfigured();
    String model();
    Completion complete(String systemInstruction, String question, String context);
}
