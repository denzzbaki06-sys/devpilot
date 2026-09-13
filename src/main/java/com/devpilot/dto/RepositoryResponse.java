package com.devpilot.dto;
import com.devpilot.model.*;
import java.time.Instant;

public record RepositoryResponse(Long id, Long githubRepositoryId, String owner, String name,
        String fullName, String defaultBranch, boolean privateRepository, String htmlUrl,
        String primaryLanguage, Instant githubUpdatedAt, Instant connectedAt, RepositoryStatus status) {
    public static RepositoryResponse from(ConnectedRepository r) {
        return new RepositoryResponse(r.getId(), r.getGithubRepositoryId(), r.getOwner(), r.getName(),
                r.getFullName(), r.getDefaultBranch(), r.getPrivateRepository(), r.getHtmlUrl(),
                r.getPrimaryLanguage(), r.getGithubUpdatedAt(), r.getConnectedAt(), r.getStatus());
    }
}
