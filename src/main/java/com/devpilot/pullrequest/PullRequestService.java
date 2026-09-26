package com.devpilot.pullrequest;

import com.devpilot.exception.ApiException;
import com.devpilot.github.GitHubService;
import com.devpilot.repository.ConnectedRepositoryRepository;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PullRequestService {
    private final ConnectedRepositoryRepository repositories;
    private final GitHubService github;
    private final GitHubPullRequestClient client;
    private final PullRequestContextBuilder builder;
    public PullRequestService(ConnectedRepositoryRepository repositories, GitHubService github, GitHubPullRequestClient client, PullRequestContextBuilder builder) {
        this.repositories = repositories; this.github = github; this.client = client; this.builder = builder;
    }
    private GitHubPullRequestClient.Repository owned(long userId, long id) {
        var repo = repositories.findByIdAndUserId(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Connected repository not found"));
        return new GitHubPullRequestClient.Repository(repo.getGithubRepositoryId(), repo.getOwner(), repo.getName());
    }
    @Transactional
    public GitHubPullRequestClient.Page list(long userId, long id, int page, int size) {
        var repo = owned(userId, id);
        if (page < 1 || page > 1000 || size < 1 || size > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid pull request pagination");
        github.lockUser(userId); String token = github.token(userId);
        client.verifyRepository(token, repo);
        return client.list(token, repo, page, size);
    }
    @Transactional
    public void verifySnapshot(long userId,long id,int number,String baseSha,String headSha) {
        var repo=owned(userId,id);github.lockUser(userId);
        var current=client.detail(github.token(userId),repo,number);
        if(!Objects.equals(baseSha,current.baseSha()) || !Objects.equals(headSha,current.headSha()))
            throw new ApiException(HttpStatus.CONFLICT,"Pull request changed during retrieval; retry");
    }
    @Transactional
    public PullRequestContext context(long userId, long id, int number) {
        var repo = owned(userId, id);
        if (number < 1) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid pull request number");
        // Reuse the encrypted token under the existing user lock, serializing disconnect.
        github.lockUser(userId); String token = github.token(userId);
        client.verifyRepository(token, repo);
        var detail = client.detail(token, repo, number);
        if (detail.changedFiles() > 3000) throw RestGitHubPullRequestClient.tooLarge();
        var files = client.files(token, repo, number);
        var after = client.detail(token, repo, number);
        // Never build citations from pages fetched across a force-push/base update.
        if (!Objects.equals(detail.headSha(), after.headSha()) || !Objects.equals(detail.baseSha(), after.baseSha())
                || !Objects.equals(detail.updatedAt(), after.updatedAt()) || !Objects.equals(detail.changedFiles(), after.changedFiles())
                || files.size() != detail.changedFiles())
            throw new ApiException(HttpStatus.CONFLICT, "Pull request changed during retrieval; retry");
        return builder.build(id, detail, files);
    }
}
