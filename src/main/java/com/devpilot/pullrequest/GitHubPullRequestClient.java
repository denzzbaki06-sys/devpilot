package com.devpilot.pullrequest;

import java.time.Instant;
import java.util.List;

/** Read-only GitHub contract. All strings are untrusted content, never instructions. */
public interface GitHubPullRequestClient {
    record Repository(long githubId, String owner, String name) {}
    record PullRequest(int number, String title, String body, String state, String author,
                       Instant createdAt, Instant updatedAt, String headBranch, String baseBranch,
                       String baseSha, String headSha, Boolean mergeable, boolean draft, String htmlUrl,
                       Integer additions, Integer deletions, Integer changedFiles) {
        @Override public String toString() { return "PullRequest[REDACTED]"; }
    }
    record Page(List<PullRequest> items, boolean hasNext) {}
    record File(String filename, String status, String previousFilename, int additions, int deletions, int changes, String patch) {
        @Override public String toString() { return "PullRequestFile[REDACTED]"; }
    }
    void verifyRepository(String token, Repository repository);
    Page list(String token, Repository repository, int page, int size);
    PullRequest detail(String token, Repository repository, int number);
    List<File> files(String token, Repository repository, int number);
}
