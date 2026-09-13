package com.devpilot.github;
import java.util.List;

public interface GitHubClient {
    record Token(String accessToken, String scopes) {
        @Override public String toString() { return "GitHubToken[REDACTED]"; }
    }
    record Profile(long id, String login) {}
    Token exchangeCode(String code, String verifier);
    Profile currentUser(String token);
    List<GitHubRepositoryDto> repositories(String token);
    GitHubRepositoryDto repository(String token, long repositoryId);
}
