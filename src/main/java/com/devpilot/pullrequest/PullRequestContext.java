package com.devpilot.pullrequest;

import java.util.List;

/** Internal review input, independent of HTTP DTOs. Every source string is untrusted data. */
public record PullRequestContext(long repositoryId, GitHubPullRequestClient.PullRequest pullRequest,
                                 List<FileChange> files, Statistics statistics) {
    public record FileChange(String filename, String status, String previousFilename, int additions, int deletions,
                             int changes, String patch, boolean patchAvailable, UnifiedDiffParser.Status parseStatus,
                             List<UnifiedDiffParser.DiffHunk> hunks) {
        @Override public String toString() { return "FileChange[REDACTED]"; }
    }
    public record Statistics(int totalChangedFiles, long totalPatchChars, int patchAvailableCount,
                             int patchUnavailableCount, int malformedPatchCount) {}
    @Override public String toString() { return "PullRequestContext[REDACTED]"; }
}
