package com.devpilot.review;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("devpilot.ai.review")
public record ReviewProperties(int maxFiles, int maxDiffChars, int maxRetrievedChunks, int maxTotalContextChars,
                               int maxBatchChars, int maxBatches, int maxFindings, int maxSeconds) {
    public ReviewProperties(int maxFiles,int maxDiffChars,int maxRetrievedChunks,int maxTotalContextChars,int maxBatchChars,int maxBatches,int maxFindings) {
        this(maxFiles,maxDiffChars,maxRetrievedChunks,maxTotalContextChars,maxBatchChars,maxBatches,maxFindings,300);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ReviewProperties {
        if(maxFiles<1 || maxFiles>30 || maxDiffChars<1000 || maxDiffChars>100000 || maxRetrievedChunks<1 || maxRetrievedChunks>100
                || maxTotalContextChars<4000 || maxTotalContextChars>300000 || maxBatchChars<2000 || maxBatchChars>50000
                || maxSeconds<30 || maxSeconds>300 || maxBatches<1 || maxBatches>20 || maxFindings<1 || maxFindings>100)
            throw new IllegalArgumentException("Invalid review budget configuration");
    }
}
