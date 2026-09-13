package com.devpilot.github;

import com.devpilot.exception.ApiException;
import com.devpilot.model.*;
import com.devpilot.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GitHubService {
    public record Status(boolean connected, String login) {}
    public record Authorization(String authorizeUrl, String browserSecret) {
        @Override public String toString() { return "GitHubAuthorization[REDACTED]"; }
    }
    private final GitHubProperties properties;
    private final GitHubTokenCipher cipher;
    private final GitHubClient client;
    private final OAuthStateStore states;
    private final GitHubConnectionRepository connections;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();
    public GitHubService(GitHubProperties properties, GitHubTokenCipher cipher, GitHubClient client,
                         OAuthStateStore states, GitHubConnectionRepository connections, UserRepository users) {
        this.properties = properties; this.cipher = cipher; this.client = client;
        this.states = states; this.connections = connections; this.users = users;
    }
    @Transactional
    public Authorization connect(Long userId) {
        properties.requireConfigured(); cipher.requireConfigured(); lockUser(userId);
        String state = randomSecret(), browser = randomSecret(), verifier = randomSecret();
        states.save(userId, hash(state), hash(browser), cipher.encrypt(verifier, "github-pkce:" + userId), Instant.now().plusSeconds(600));
        String url = UriComponentsBuilder.fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", properties.clientId()).queryParam("redirect_uri", properties.redirectUri())
                .queryParam("scope", "repo").queryParam("state", state)
                .queryParam("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier)))
                .queryParam("code_challenge_method", "S256").build().encode().toUriString();
        return new Authorization(url, browser);
    }
    public OAuthStateStore.Pending consumeState(String state, String browser) {
        if (state == null || browser == null || !state.matches("[A-Za-z0-9_-]{43}") || !browser.matches("[A-Za-z0-9_-]{43}")) throw invalidState();
        var pending = states.consume(hash(state), hash(browser)).orElseThrow(this::invalidState);
        if (!pending.expiresAt().isAfter(Instant.now())) throw invalidState();
        return pending;
    }
    @Transactional
    public Status complete(OAuthStateStore.Pending pending, String code, String error) {
        if (error != null || code == null || code.isBlank() || code.length() > 2048) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GitHub authorization denied or code missing; start connection again");
        }
        properties.requireConfigured(); cipher.requireConfigured(); lockUser(pending.userId());
        var token = client.exchangeCode(code, cipher.decrypt(pending.verifierEncrypted(), "github-pkce:" + pending.userId()));
        var profile = client.currentUser(token.accessToken());
        var connection = connections.findByUserId(pending.userId()).orElseGet(() -> new GitHubConnection(pending.userId()));
        connection.connect(profile.id(), profile.login(), cipher.encrypt(token.accessToken(), "github-token:" + pending.userId()), token.scopes());
        connections.save(connection);
        return new Status(true, profile.login());
    }
    @Transactional(readOnly = true)
    public Status status(Long userId) {
        return connections.findByUserId(userId).map(c -> new Status(true, c.getGithubLogin())).orElse(new Status(false, null));
    }
    @Transactional
    public List<GitHubRepositoryDto> repositories(Long userId) { lockUser(userId); return client.repositories(token(userId)); }
    @Transactional
    public void disconnect(Long userId) {
        lockUser(userId); states.deleteForUser(userId); connections.deleteByUserId(userId);
    }
    // Caller holds the user lock when using a stored token, serializing disconnect and API operations.
    public String token(Long userId) {
        var connection = connections.findByUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Connect GitHub first"));
        return cipher.decrypt(connection.getAccessTokenEncrypted(), "github-token:" + userId);
    }
    public void lockUser(Long userId) {
        users.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    }
    private String randomSecret() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private String hash(String value) { return HexFormat.of().formatHex(digest(value)); }
    private ApiException invalidState() { return new ApiException(HttpStatus.BAD_REQUEST, "Invalid or expired GitHub OAuth state"); }
}
