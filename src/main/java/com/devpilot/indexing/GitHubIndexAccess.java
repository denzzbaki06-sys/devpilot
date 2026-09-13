package com.devpilot.indexing;
import com.devpilot.github.GitHubService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitHubIndexAccess {
    private final GitHubService github;
    private final GitHubContentClient client;
    public GitHubIndexAccess(GitHubService github, GitHubContentClient client) { this.github = github; this.client = client; }
    @Transactional
    public GitHubContentClient.Snapshot snapshot(IndexJobStore.Ticket ticket) {
        github.lockUser(ticket.userId());
        return client.snapshot(github.token(ticket.userId()), ticket.owner(), ticket.name(), ticket.githubRepositoryId());
    }
    @Transactional
    public byte[] blob(IndexJobStore.Ticket ticket, GitHubContentClient.Snapshot snapshot, String sha, int limit) {
        // Re-read authorization for each network operation; disconnect takes effect on the next file.
        github.lockUser(ticket.userId());
        return client.blob(github.token(ticket.userId()), snapshot.owner(), snapshot.name(), sha, limit);
    }
}
