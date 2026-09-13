package com.devpilot;

import com.devpilot.service.AuthService;
import com.devpilot.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Value("${jwt.secret}") String secret;
    final HttpClient client = HttpClient.newHttpClient();
    String email;
    static final String PASSWORD = "M2-test-password!";

    @BeforeEach void setup() { email = "m2-" + UUID.randomUUID() + "@example.test"; }
    @AfterEach void cleanup() { jdbc.update("DELETE FROM users WHERE email = ?", email); }

    HttpResponse<String> request(String method, String path, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path, Object body) throws Exception { return request("POST", "/api/auth/" + path, body, null); }
    JsonNode register() throws Exception {
        var response = post("register", Map.of("name", "Test User", "email", email, "password", PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body());
    }
    String token(JsonNode pair, String name) { return pair.get(name).asText(); }
    HttpResponse<String> refresh(String raw) throws Exception { return post("refresh", Map.of("refreshToken", raw)); }
    void assertError(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var error = json.readTree(response.body());
        assertThat(error.get("status").asInt()).isEqualTo(status);
        assertThat(error.has("message")).isTrue();
        assertThat(error.has("timestamp")).isTrue();
        assertThat(response.body()).doesNotContain("passwordHash", "stackTrace", PASSWORD);
    }

    @Test void registerSucceedsAndNormalizesEmail() throws Exception {
        var response = post("register", Map.of("name", " Test User ", "email", "  " + email.toUpperCase(Locale.ROOT) + "  ", "password", PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        var pair = json.readTree(response.body());
        assertThat(pair.get("user").get("email").asText()).isEqualTo(email);
        assertThat(pair.get("user").get("role").asText()).isEqualTo("USER");
        assertThat(pair.get("expiresIn").asLong()).isEqualTo(3600);
        assertThat(pair.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(response.body()).doesNotContain("passwordHash", PASSWORD);
    }
    @Test void duplicateEmailReturnsConflict() throws Exception {
        register();
        assertError(post("register", Map.of("name", "Other", "email", email.toUpperCase(Locale.ROOT), "password", PASSWORD)), 409);
    }
    @Test void passwordAndRefreshTokenAreHashed() throws Exception {
        var pair = register();
        var hash = users.findByEmail(email).orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2a$12$").isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        String raw = token(pair, "refreshToken");
        String stored = jdbc.queryForObject("SELECT token_hash FROM refresh_tokens WHERE user_id = ?", String.class, pair.get("user").get("id").asLong());
        assertThat(stored).isNotEqualTo(raw).isEqualTo(AuthService.hashToken(raw));
    }
    @Test void loginSucceeds() throws Exception {
        register();
        var response = post("login", Map.of("email", email.toUpperCase(Locale.ROOT), "password", PASSWORD));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("accessToken").asText()).isNotBlank();
    }
    @Test void wrongPasswordFails() throws Exception {
        register();
        assertError(post("login", Map.of("email", email, "password", "wrong-password")), 401);
    }
    @Test void unknownEmailFails() throws Exception {
        assertError(post("login", Map.of("email", email, "password", PASSWORD)), 401);
    }
    @Test void accessTokenAllowsCurrentUser() throws Exception {
        var pair = register();
        var response = request("GET", "/api/users/me", null, token(pair, "accessToken"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("email").asText()).isEqualTo(email);
        assertThat(response.body()).doesNotContain("password", "Hash");
        assertThat(response.headers().allValues("set-cookie")).isEmpty();
    }
    @Test void noTokenBlocksCurrentUser() throws Exception { assertError(request("GET", "/api/users/me", null, null), 401); }
    @Test void refreshRotatesBothTokens() throws Exception {
        var old = register();
        var response = refresh(token(old, "refreshToken"));
        assertThat(response.statusCode()).isEqualTo(200);
        var fresh = json.readTree(response.body());
        assertThat(token(fresh, "refreshToken")).isNotEqualTo(token(old, "refreshToken"));
        assertThat(token(fresh, "accessToken")).isNotEqualTo(token(old, "accessToken"));
        assertThat(request("GET", "/api/users/me", null, token(fresh, "accessToken")).statusCode()).isEqualTo(200);
    }
    @Test void oldRefreshCannotBeReused() throws Exception {
        String raw = token(register(), "refreshToken");
        assertThat(refresh(raw).statusCode()).isEqualTo(200);
        assertError(refresh(raw), 401);
    }
    @Test void logoutRevokesRefresh() throws Exception {
        String raw = token(register(), "refreshToken");
        assertThat(post("logout", Map.of("refreshToken", raw)).statusCode()).isEqualTo(204);
        assertError(refresh(raw), 401);
    }
    @Test void expiredRefreshRejected() throws Exception {
        String raw = token(register(), "refreshToken");
        jdbc.update("UPDATE refresh_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE token_hash = ?", AuthService.hashToken(raw));
        assertError(refresh(raw), 401);
    }
    @Test void revokedRefreshRejected() throws Exception {
        String raw = token(register(), "refreshToken");
        jdbc.update("UPDATE refresh_tokens SET revoked = TRUE WHERE token_hash = ?", AuthService.hashToken(raw));
        assertError(refresh(raw), 401);
    }
    @Test void invalidRefreshRejected() throws Exception { assertError(refresh("invalid-token"), 401); }
    @Test void opaqueRefreshCannotAuthenticate() throws Exception {
        assertError(request("GET", "/api/users/me", null, token(register(), "refreshToken")), 401);
    }
    @Test void invalidAccessRejected() throws Exception { assertError(request("GET", "/api/users/me", null, "invalid-token"), 401); }
    String signed(JsonNode pair, String type, Instant expiration) {
        return Jwts.builder().subject(email).claim("userId", pair.get("user").get("id").asLong())
                .claim("role", "USER").claim("type", type).expiration(Date.from(expiration))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }
    @Test void expiredAccessRejected() throws Exception {
        var pair = register();
        assertError(request("GET", "/api/users/me", null, signed(pair, "access", Instant.now().minusSeconds(60))), 401);
    }
    @Test void jwtWithRefreshTypeRejected() throws Exception {
        var pair = register();
        assertError(request("GET", "/api/users/me", null, signed(pair, "refresh", Instant.now().plusSeconds(60))), 401);
    }
    @Test void invalidRegistrationRejected() throws Exception {
        assertError(post("register", Map.of("name", " ", "email", "invalid", "password", "short")), 400);
    }
    @Test void bcryptByteLimitValidated() throws Exception {
        assertError(post("register", Map.of("name", "Test", "email", email, "password", "ş".repeat(40))), 400);
    }
    @Test void healthRemainsPublic() throws Exception { assertThat(request("GET", "/api/health", null, null).statusCode()).isEqualTo(200); }
    @Test void simultaneousRefreshOnlySucceedsOnce() throws Exception {
        String raw = token(register(), "refreshToken");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> call = () -> { start.await(); return refresh(raw).statusCode(); };
            var a = executor.submit(call); var b = executor.submit(call); start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 401);
        }
    }
    @Test void corsAllowsConfiguredFrontendWithoutCredentials() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Origin", "http://localhost:5174").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5174");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }
    @Test void corsRejectsUntrustedOrigin() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Origin", "https://untrusted.example").header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }
}
