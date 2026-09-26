package com.devpilot.pullrequest;

import java.time.Instant;
import java.util.List;

/** Explicit allowlisted API shapes; no connection entities or credential fields. */
public final class PullRequestResponses {
    private PullRequestResponses() {}
    public record Summary(int number, String title, String state, String author, Instant createdAt, Instant updatedAt,
                          String headBranch, String baseBranch, boolean draft, String htmlUrl, Integer additions, Integer deletions, Integer changedFiles) {
        static Summary from(GitHubPullRequestClient.PullRequest p) { return new Summary(p.number(), p.title(), p.state(), p.author(), p.createdAt(), p.updatedAt(),
                p.headBranch(), p.baseBranch(), p.draft(), p.htmlUrl(), p.additions(), p.deletions(), p.changedFiles()); }
        @Override public String toString() { return "PullRequestSummary[REDACTED]"; }
    }
    public record Page(long repositoryId, int page, int size, boolean hasNext, List<Summary> items) {
        public static Page from(long id, int page, int size, GitHubPullRequestClient.Page result) {
            return new Page(id, page, size, result.hasNext(), result.items().stream().map(Summary::from).toList());
        }
    }
    public record Line(UnifiedDiffParser.LineType type, String content, Integer oldLineNumber, Integer newLineNumber) {
        @Override public String toString() { return "DiffLineResponse[REDACTED]"; }
    }
    public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<Line> lines) {}
    public record File(String filename, String status, String previousFilename, int additions, int deletions, int changes,
                       String patch, boolean patchAvailable, UnifiedDiffParser.Status parseStatus, List<Hunk> hunks) {
        static File from(PullRequestContext.FileChange f) {
            return new File(f.filename(), f.status(), f.previousFilename(), f.additions(), f.deletions(), f.changes(), f.patch(), f.patchAvailable(), f.parseStatus(),
                    f.hunks().stream().map(h -> new Hunk(h.oldStart(), h.oldCount(), h.newStart(), h.newCount(), h.lines().stream()
                            .map(l -> new Line(l.type(), l.content(), l.oldLineNumber(), l.newLineNumber())).toList())).toList());
        }
        @Override public String toString() { return "PullRequestFileResponse[REDACTED]"; }
    }
    public record Statistics(int totalChangedFiles, long totalPatchChars, int patchAvailableCount, int patchUnavailableCount, int malformedPatchCount) {}
    public record Detail(long repositoryId, int number, String title, String body, String state, String author, Instant createdAt, Instant updatedAt,
                         String headBranch, String baseBranch, String baseSha, String headSha, Boolean mergeable, boolean draft,
                         String htmlUrl, Integer additions, Integer deletions, Integer changedFiles, List<File> files, Statistics statistics) {
        public static Detail from(PullRequestContext c) {
            var p = c.pullRequest(); var s = c.statistics();
            return new Detail(c.repositoryId(), p.number(), p.title(), p.body(), p.state(), p.author(), p.createdAt(), p.updatedAt(), p.headBranch(), p.baseBranch(),
                    p.baseSha(), p.headSha(), p.mergeable(), p.draft(), p.htmlUrl(), p.additions(), p.deletions(), p.changedFiles(),
                    c.files().stream().map(File::from).toList(), new Statistics(s.totalChangedFiles(), s.totalPatchChars(), s.patchAvailableCount(), s.patchUnavailableCount(), s.malformedPatchCount()));
        }
        @Override public String toString() { return "PullRequestDetail[REDACTED]"; }
    }
}
