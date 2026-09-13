package com.devpilot.github;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class OAuthStateStore {
    public record Pending(Long userId, String verifierEncrypted, Instant expiresAt) {
        @Override public String toString() { return "PendingOAuth[REDACTED]"; }
    }
    private final JdbcTemplate jdbc;
    public OAuthStateStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void save(Long userId, String stateHash, String browserHash, String verifier, Instant expiresAt) {
        jdbc.update("DELETE FROM github_oauth_states WHERE user_id = ? OR expires_at <= CURRENT_TIMESTAMP", userId);
        jdbc.update("INSERT INTO github_oauth_states (state_hash, user_id, browser_hash, verifier_encrypted, expires_at) VALUES (?, ?, ?, ?, ?)",
                stateHash, userId, browserHash, verifier, Timestamp.from(expiresAt));
    }
    // Commit consumption BEFORE contacting GitHub. Failed exchange/expired state cannot be replayed.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Pending> consume(String stateHash, String browserHash) {
        return jdbc.query("DELETE FROM github_oauth_states WHERE state_hash = ? AND browser_hash = ? RETURNING user_id, verifier_encrypted, expires_at",
                (rs, row) -> new Pending(rs.getLong("user_id"), rs.getString("verifier_encrypted"), rs.getTimestamp("expires_at").toInstant()),
                stateHash, browserHash).stream().findFirst();
    }
    public void deleteForUser(Long userId) { jdbc.update("DELETE FROM github_oauth_states WHERE user_id = ?", userId); }
}
