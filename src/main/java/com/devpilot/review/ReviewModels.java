package com.devpilot.review;
import java.util.*;
public final class ReviewModels {
    private ReviewModels() {}
    public enum Category { BUG, SECURITY, MAINTAINABILITY, TESTING }
    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW }
    public enum Side { LEFT, RIGHT }
    public record Location(String path,String previousPath,Side side,int startLine,int endLine,int hunk) {}
    public record Evidence(long chunkId,String path,int startLine,int endLine,String symbolName,String language,double similarity) {}
    public record Finding(String id,Category category,Severity severity,String title,String description,String recommendation,Location location,List<Evidence> evidence) {
        @Override public String toString(){return "ReviewFinding[REDACTED]";}
    }
    public record SkippedFile(String path,String reason) {}
    public record Stats(int filesAnalyzed,int filesSkipped,int findings,int critical,int high,int medium,int low,int batches,int diffChars,int retrievedChunks,int contextChars) {}
    public record Response(long repositoryId,int pullRequestNumber,String baseSha,String headSha,String indexCommitSha,String indexRelation,
                           String summary,String riskLevel,List<Finding> findings,Stats stats,List<SkippedFile> skippedFiles,List<String> warnings,String model,boolean grounded) {
        @Override public String toString(){return "ReviewResponse[REDACTED]";}
    }
}
