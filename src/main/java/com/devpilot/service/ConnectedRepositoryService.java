package com.devpilot.service;

import com.devpilot.dto.RepositoryResponse;
import com.devpilot.exception.ApiException;
import com.devpilot.github.*;
import com.devpilot.model.ConnectedRepository;
import com.devpilot.repository.ConnectedRepositoryRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectedRepositoryService {
    private final ConnectedRepositoryRepository repositories;
    private final GitHubService github;
    private final GitHubClient client;
    public ConnectedRepositoryService(ConnectedRepositoryRepository repositories, GitHubService github, GitHubClient client) {
        this.repositories = repositories; this.github = github; this.client = client;
    }
    @Transactional
    public RepositoryResponse connect(Long userId, Long githubRepositoryId) {
        github.lockUser(userId);
        String token = github.token(userId);
        if (repositories.findByUserIdAndGithubRepositoryId(userId, githubRepositoryId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Repository already connected");
        }
        var metadata = client.repository(token, githubRepositoryId);
        return RepositoryResponse.from(repositories.saveAndFlush(new ConnectedRepository(userId, metadata)));
    }
    @Transactional(readOnly = true)
    public List<RepositoryResponse> list(Long userId) {
        return repositories.findAllByUserIdOrderByIdAsc(userId).stream().map(RepositoryResponse::from).toList();
    }
    @Transactional(readOnly = true)
    public RepositoryResponse get(Long userId, Long id) { return RepositoryResponse.from(owned(userId, id)); }
    @Transactional
    public void delete(Long userId, Long id) {
        var repository = repositories.findOwnedForUpdate(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Connected repository not found"));
        if (repository.getStatus() == com.devpilot.model.RepositoryStatus.INDEXING) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot delete repository while indexing");
        }
        repositories.delete(repository);
    }
    private ConnectedRepository owned(Long userId, Long id) {
        return repositories.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Connected repository not found"));
    }
}
