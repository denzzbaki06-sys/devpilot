package com.devpilot.architecture.ask;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("devpilot.ai.architecture")
public record ArchitectureAskProperties(int maxQuestionChars, int maxComponents, int maxRelationships,
        int depth, int maxDepth, int topK, int maxChunks, int maxContextChars) {
    public ArchitectureAskProperties {
        if(maxQuestionChars<1||maxQuestionChars>4000||maxComponents<1||maxComponents>60||maxRelationships<1||maxRelationships>100
                ||depth<1||maxDepth>2||depth>maxDepth||topK<1||topK>20||maxChunks<2||maxChunks>30||maxContextChars<4000||maxContextChars>80000)
            throw new IllegalArgumentException("Invalid architecture question budgets");
    }
}
