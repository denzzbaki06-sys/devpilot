package com.devpilot.rag;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("devpilot.ai.rag")
public record RagProperties(int topK, int maxContextChars, int maxQuestionChars) {
    public RagProperties {
        if (topK < 1 || topK > 20 || maxContextChars < 256 || maxContextChars > 100000 || maxQuestionChars < 1 || maxQuestionChars > 4000)
            throw new IllegalArgumentException("Invalid RAG limits");
    }
}
