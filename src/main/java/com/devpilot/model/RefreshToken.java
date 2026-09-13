package com.devpilot.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false)
    private Instant expiresAt;
    @Column(nullable = false)
    private boolean revoked;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {}
    public RefreshToken(String tokenHash, User user, Instant expiresAt) {
        this.tokenHash = tokenHash; this.user = user; this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }
    public User getUser() { return user; }
    public boolean isRevoked() { return revoked; }
    public Instant getExpiresAt() { return expiresAt; }
    public void revoke() { revoked = true; }
}
