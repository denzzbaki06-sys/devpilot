package com.devpilot;

import com.devpilot.github.*;
import com.devpilot.model.User;
import com.devpilot.repository.UserRepository;
import com.devpilot.service.JwtService;
import com.devpilot.exception.ApiException;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

// Test-only OAuth settings; all upstream requests are mocked. No real credentials or GitHub calls.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "github.client-id=test-only-client", "github.client-secret=test-only-secret"})
class GitHubIntegrationTests {
    @DynamicPropertySource static void key(DynamicPropertyRegistry registry) {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String key = Base64.getEncoder().encodeToString(bytes);
        registry.add("github.token-encryption-key", () -> key);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired GitHubTokenCipher cipher;
    @MockitoBean GitHubClient client;
    final HttpClient http = HttpClient.newHttpClient();
    User user, other;
    String access, otherAccess;
    static final String UPSTREAM_TOKEN = "test-only-github-access-token";
    record Browser(String state, String cookie, String url) {}

    @BeforeEach void setup() {
        user = users.saveAndFlush(new User("M3 Test", "m3-" + UUID.randomUUID() + "@example.test", "unused-test-hash"));
        other = users.saveAndFlush(new User("M3 Other", "m3-" + UUID.randomUUID() + "@example.test", "unused-test-hash"));
        access = jwt.createAccessToken(user); otherAccess = jwt.createAccessToken(other);
        when(client.exchangeCode(anyString(), anyString())).thenReturn(new GitHubClient.Token(UPSTREAM_TOKEN, "repo"));
        when(client.currentUser(UPSTREAM_TOKEN)).thenReturn(new GitHubClient.Profile(99, "octocat"));
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", user.getId(), other.getId());
    }
    HttpResponse<String> request(String method, String path, Object body, String accessToken, String cookie) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (accessToken != null) builder.header("Authorization", "Bearer " + accessToken);
        if (cookie != null) builder.header("Cookie", cookie);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String path, String token) throws Exception { return request("GET", path, null, token, null); }
    Browser start() throws Exception {
        var response = get("/api/github/connect", access);
        assertThat(response.statusCode()).isEqualTo(200);
        String url = json.readTree(response.body()).get("authorizeUrl").asText();
        String state = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
        String cookie = response.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly", "SameSite=Lax", "Max-Age=600");
        assertThat(response.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        return new Browser(state, cookie.split(";", 2)[0], url);
    }
    HttpResponse<String> callback(Browser browser) throws Exception {
        return request("GET", "/api/github/callback?code=test-code&state=" + browser.state(), null, null, browser.cookie());
    }
    void connect() throws Exception { assertThat(callback(start()).statusCode()).isEqualTo(200); }
    void error(HttpResponse<String> response, int expected) throws Exception {
        assertThat(response.statusCode()).isEqualTo(expected);
        var data = json.readTree(response.body());
        assertThat(data.get("status").asInt()).isEqualTo(expected);
        assertThat(data.has("message")).isTrue();
        assertThat(response.body()).doesNotContain(UPSTREAM_TOKEN, "test-only-secret", "stackTrace", "accessTokenEncrypted");
    }
    GitHubRepositoryDto repo() { return new GitHubRepositoryDto(123, "octocat", "private-repo", "octocat/private-repo", true,
            "https://github.com/octocat/private-repo", "main", "Java", Instant.parse("2026-01-01T00:00:00Z")); }
    HttpResponse<String> add() throws Exception {
        when(client.repository(UPSTREAM_TOKEN, 123)).thenReturn(repo());
        return request("POST", "/api/repositories", Map.of("githubRepositoryId", 123), access, null);
    }

    @Test void connectUrlContainsRandomStateAndPkce() throws Exception {
        var a = start(); var b = start();
        assertThat(a.state()).hasSize(43).isNotEqualTo(b.state());
        assertThat(b.url()).contains("scope=repo", "code_challenge=", "code_challenge_method=S256");
        assertThat(b.url()).doesNotContain("test-only-secret");
        assertThat(jdbc.queryForObject("SELECT user_id FROM github_oauth_states WHERE user_id = ?", Long.class, user.getId())).isEqualTo(user.getId());
    }
    @Test void stateIsSingleUseAndBoundToInitiatingUser() throws Exception {
        var browser = start();
        assertThat(callback(browser).statusCode()).isEqualTo(200);
        error(callback(browser), 400);
        assertThat(jdbc.queryForObject("SELECT user_id FROM github_connections WHERE github_user_id = 99 AND user_id = ?", Long.class, user.getId())).isEqualTo(user.getId());
        verify(client, times(1)).exchangeCode(anyString(), anyString());
    }
    @Test void concurrentCallbackOnlySucceedsOnce() throws Exception {
        var browser = start();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> call = () -> { gate.await(); return callback(browser).statusCode(); };
            var a = executor.submit(call); var b = executor.submit(call); gate.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 400);
        }
    }
    @Test void invalidStateRejected() throws Exception { error(request("GET", "/api/github/callback?code=code&state=invalid", null, null, null), 400); }
    @Test void missingBrowserCookieRejected() throws Exception {
        var browser = start();
        error(request("GET", "/api/github/callback?code=code&state=" + browser.state(), null, null, null), 400);
        verifyNoInteractions(client);
    }
    @Test void differentBrowserRejectedWithoutConsumingValidState() throws Exception {
        var browser = start();
        error(request("GET", "/api/github/callback?code=code&state=" + browser.state(), null, null,
                "devpilot_github_oauth=" + "a".repeat(43)), 400);
        assertThat(callback(browser).statusCode()).isEqualTo(200);
    }
    @Test void expiredStateRejectedAndConsumed() throws Exception {
        var browser = start();
        jdbc.update("UPDATE github_oauth_states SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE user_id = ?", user.getId());
        error(callback(browser), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_oauth_states WHERE user_id = ?", Integer.class, user.getId())).isZero();
        verifyNoInteractions(client);
    }
    @Test void callbackStoresEncryptedTokenAndNoPlaintext() throws Exception {
        var response = callback(start());
        assertThat(response.statusCode()).isEqualTo(200);
        String stored = jdbc.queryForObject("SELECT access_token_encrypted FROM github_connections WHERE user_id = ?", String.class, user.getId());
        assertThat(stored).startsWith("v1:").doesNotContain(UPSTREAM_TOKEN);
        assertThat(cipher.decrypt(stored, "github-token:" + user.getId())).isEqualTo(UPSTREAM_TOKEN);
        assertThat(response.body()).doesNotContain(UPSTREAM_TOKEN, "accessToken", "scopes");
    }
    @Test void encryptedTokenCannotBeMovedToAnotherUserOrTampered() {
        String encrypted = cipher.encrypt(UPSTREAM_TOKEN, "github-token:" + user.getId());
        assertThat(cipher.encrypt(UPSTREAM_TOKEN, "github-token:" + user.getId())).isNotEqualTo(encrypted);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "github-token:" + other.getId())).isInstanceOf(ApiException.class);
        byte[] data = Base64.getDecoder().decode(encrypted.substring(3)); data[15] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(data), "github-token:" + user.getId())).isInstanceOf(ApiException.class);
    }
    @Test void statusConnectedAndOtherUserIsIsolated() throws Exception {
        connect();
        assertThat(json.readTree(get("/api/github/status", access).body()).get("login").asText()).isEqualTo("octocat");
        assertThat(json.readTree(get("/api/github/status", otherAccess).body()).get("connected").asBoolean()).isFalse();
        error(get("/api/github/repositories", otherAccess), 409);
    }
    @Test void repositoryListMapsAndDoesNotPersistAutomatically() throws Exception {
        connect(); when(client.repositories(UPSTREAM_TOKEN)).thenReturn(List.of(repo()));
        var response = get("/api/github/repositories", access);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = json.readTree(response.body()).get(0);
        assertThat(data.get("privateRepository").asBoolean()).isTrue();
        assertThat(data.get("fullName").asText()).isEqualTo("octocat/private-repo");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repositories WHERE user_id = ?", Integer.class, user.getId())).isZero();
    }
    @Test void connectRepositorySucceedsWithServerMetadata() throws Exception {
        connect(); var response = add();
        assertThat(response.statusCode()).isEqualTo(201);
        var data = json.readTree(response.body());
        assertThat(data.get("status").asText()).isEqualTo("CONNECTED");
        assertThat(data.get("privateRepository").asBoolean()).isTrue();
        verify(client).repository(UPSTREAM_TOKEN, 123);
        assertThat(get("/api/repositories/" + data.get("id").asLong(), access).statusCode()).isEqualTo(200);
    }
    @Test void duplicateRepositoryReturnsConflict() throws Exception { connect(); assertThat(add().statusCode()).isEqualTo(201); error(add(), 409); }
    @Test void otherUserCannotReadListOrDeleteOwnedRepository() throws Exception {
        connect(); long id = json.readTree(add().body()).get("id").asLong();
        error(get("/api/repositories/" + id, otherAccess), 404);
        error(request("DELETE", "/api/repositories/" + id, null, otherAccess, null), 404);
        assertThat(json.readTree(get("/api/repositories", otherAccess).body()).size()).isZero();
        assertThat(get("/api/repositories/" + id, access).statusCode()).isEqualTo(200);
    }
    @Test void deletingRepositoryOnlyDeletesLocalMetadata() throws Exception {
        connect(); long id = json.readTree(add().body()).get("id").asLong(); clearInvocations(client);
        assertThat(request("DELETE", "/api/repositories/" + id, null, access, null).statusCode()).isEqualTo(204);
        error(get("/api/repositories/" + id, access), 404); verifyNoInteractions(client);
    }
    @Test void disconnectRemovesConnectionAndPendingAuthorization() throws Exception {
        connect(); var pending = start();
        assertThat(request("POST", "/api/github/disconnect", null, access, null).statusCode()).isEqualTo(204);
        assertThat(json.readTree(get("/api/github/status", access).body()).get("connected").asBoolean()).isFalse();
        error(get("/api/github/repositories", access), 409); error(callback(pending), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_connections WHERE user_id = ?", Integer.class, user.getId())).isZero();
    }
    @Test void apiUnauthorizedHandled() throws Exception {
        connect(); when(client.repositories(UPSTREAM_TOKEN)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "Reconnect GitHub"));
        error(get("/api/github/repositories", access), 401);
    }
    @Test void rateLimitHandled() throws Exception {
        connect(); when(client.repositories(UPSTREAM_TOKEN)).thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "GitHub rate limit reached"));
        error(get("/api/github/repositories", access), 429);
    }
    @Test void inaccessiblePrivateRepositoryRejected() throws Exception {
        connect(); when(client.repository(UPSTREAM_TOKEN, 456)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "GitHub repository not found or inaccessible"));
        error(request("POST", "/api/repositories", Map.of("githubRepositoryId", 456), access, null), 404);
    }
    @Test void failedExchangeStillConsumesState() throws Exception {
        var browser = start(); when(client.exchangeCode(anyString(), anyString())).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "GitHub code exchange failed"));
        error(callback(browser), 502); error(callback(browser), 400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_connections WHERE user_id = ?", Integer.class, user.getId())).isZero();
    }
    @Test void oauthDenialConsumesStateWithoutExchange() throws Exception {
        var browser = start();
        error(request("GET", "/api/github/callback?error=access_denied&state=" + browser.state(), null, null, browser.cookie()), 400);
        error(callback(browser), 400); verifyNoInteractions(client);
    }
    @Test void githubEndpointsRequireAuthentication() throws Exception {
        error(get("/api/github/connect", null), 401); error(get("/api/github/status", null), 401);
        error(get("/api/github/repositories", null), 401); error(get("/api/repositories", null), 401);
        error(request("POST", "/api/github/disconnect", null, null, null), 401);
    }
    @Test void browserCallbackRedirectsToConfiguredFrontendWithoutLeakingCredentials() throws Exception {
        var browser = start();
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/github/callback?code=test-code&state=" + browser.state()))
                .header("Accept", "text/html").header("Cookie", browser.cookie()).GET().build();
        var response = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("location")).contains("http://localhost:5174/github?connected=true");
        assertThat(response.body()).doesNotContain(UPSTREAM_TOKEN, browser.state(), "test-code");
        error(callback(browser), 400);
    }
    @Test void browserCallbackFailureRedirectsWithFixedErrorAndKeepsStateValidation() throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/github/callback?state=bad&error=raw-private-error"))
                .header("Accept", "text/html").GET().build();
        var response = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("location")).contains("http://localhost:5174/github?error=oauth_failed");
        verifyNoInteractions(client);
    }
    @Test void githubCorsAllowsStateCookieForOnlyAllowedOrigins() throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/github/connect"))
                .header("Origin", "http://localhost:5174").header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5174");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
    }
}
