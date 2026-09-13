package com.devpilot.github;
import java.time.Instant;

public record GitHubRepositoryDto(long githubRepositoryId, String owner, String name, String fullName,
                                  boolean privateRepository, String htmlUrl, String defaultBranch,
                                  String language, Instant updatedAt) {}
