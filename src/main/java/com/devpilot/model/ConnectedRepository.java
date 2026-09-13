package com.devpilot.model;
import com.devpilot.github.GitHubRepositoryDto;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "repositories", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "github_repository_id"}))
public class ConnectedRepository {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long userId;
    private Long githubRepositoryId;
    private String owner;
    private String name;
    @Column(length = 511, nullable = false) private String fullName;
    private String defaultBranch;
    private boolean privateRepository;
    @Column(columnDefinition = "text", nullable = false) private String htmlUrl;
    private String primaryLanguage;
    private Instant githubUpdatedAt;
    private Instant connectedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private RepositoryStatus status;
    protected ConnectedRepository() {}
    public ConnectedRepository(Long userId, GitHubRepositoryDto repo) {
        this.userId = userId; this.githubRepositoryId = repo.githubRepositoryId();
        this.owner = repo.owner(); this.name = repo.name(); this.fullName = repo.fullName();
        this.defaultBranch = repo.defaultBranch(); this.privateRepository = repo.privateRepository();
        this.htmlUrl = repo.htmlUrl(); this.primaryLanguage = repo.language();
        this.githubUpdatedAt = repo.updatedAt(); this.connectedAt = Instant.now(); this.status = RepositoryStatus.CONNECTED;
    }
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getGithubRepositoryId() { return githubRepositoryId; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getFullName() { return fullName; }
    public String getDefaultBranch() { return defaultBranch; }
    public boolean getPrivateRepository() { return privateRepository; }
    public String getHtmlUrl() { return htmlUrl; }
    public String getPrimaryLanguage() { return primaryLanguage; }
    public Instant getGithubUpdatedAt() { return githubUpdatedAt; }
    public Instant getConnectedAt() { return connectedAt; }
    public RepositoryStatus getStatus() { return status; }
}
