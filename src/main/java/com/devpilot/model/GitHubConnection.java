package com.devpilot.model;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "github_connections")
public class GitHubConnection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private Long userId;
    @Column(nullable = false) private Long githubUserId;
    @Column(nullable = false) private String githubLogin;
    @Column(nullable = false, columnDefinition = "text") private String accessTokenEncrypted;
    @Column(nullable = false, columnDefinition = "text") private String scopes;
    @Column(nullable = false) private Instant connectedAt;
    @Column(nullable = false) private Instant updatedAt;
    protected GitHubConnection() {}
    public GitHubConnection(Long userId) { this.userId = userId; }
    public void connect(long githubUserId, String login, String encryptedToken, String scopes) {
        this.githubUserId = githubUserId; this.githubLogin = login;
        this.accessTokenEncrypted = encryptedToken; this.scopes = scopes;
        this.connectedAt = this.updatedAt = Instant.now();
    }
    public String getGithubLogin() { return githubLogin; }
    public String getAccessTokenEncrypted() { return accessTokenEncrypted; }
}
