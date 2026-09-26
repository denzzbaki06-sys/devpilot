package com.devpilot.pullrequest;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PullRequestContextBuilder {
    private final UnifiedDiffParser parser;
    public PullRequestContextBuilder(UnifiedDiffParser parser) { this.parser = parser; }
    public PullRequestContext build(long repositoryId, GitHubPullRequestClient.PullRequest detail, List<GitHubPullRequestClient.File> files) {
        // Bound allocation before parsing. Reject excess rather than dropping files/lines.
        long totalChars = 0, totalLines = 0;
        for (var file : files) if (file.patch() != null) {
            totalChars += file.patch().length();
            totalLines += file.patch().chars().filter(c -> c == '\n').count() + 1;
        }
        if (files.size() > 3000 || totalChars > 10_000_000 || totalLines > 200_000) throw RestGitHubPullRequestClient.tooLarge();
        var changes = new ArrayList<PullRequestContext.FileChange>(); long chars = 0; int available = 0, malformed = 0;
        for (var file : files) {
            var parsed = parser.parse(file.patch()); boolean hasPatch = file.patch() != null && !file.patch().isBlank();
            if (parsed.status() == UnifiedDiffParser.Status.PARSED) {
                long additions = parsed.hunks().stream().flatMap(h -> h.lines().stream()).filter(l -> l.type() == UnifiedDiffParser.LineType.ADDITION).count();
                long deletions = parsed.hunks().stream().flatMap(h -> h.lines().stream()).filter(l -> l.type() == UnifiedDiffParser.LineType.DELETION).count();
                // A truncated patch can end at a valid hunk boundary; metadata still reveals it.
                if (additions != file.additions() || deletions != file.deletions())
                    parsed = new UnifiedDiffParser.Result(UnifiedDiffParser.Status.MALFORMED, List.of());
            }
            chars += file.patch() == null ? 0 : file.patch().length();
            if (hasPatch) available++; if (parsed.status() == UnifiedDiffParser.Status.MALFORMED) malformed++;
            changes.add(new PullRequestContext.FileChange(file.filename(), file.status(), file.previousFilename(), file.additions(),
                    file.deletions(), file.changes(), file.patch(), hasPatch, parsed.status(), parsed.hunks()));
        }
        return new PullRequestContext(repositoryId, detail, List.copyOf(changes),
                new PullRequestContext.Statistics(files.size(), chars, available, files.size() - available, malformed));
    }
}
