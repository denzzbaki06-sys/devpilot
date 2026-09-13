package com.devpilot;

import com.devpilot.github.*;
import com.devpilot.exception.ApiException;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RestGitHubClientTests {
    MockRestServiceServer api, oauth;
    RestGitHubClient client;
    ObjectMapper json = new ObjectMapper();
    static final String TOKEN = "test-only-token";
    static final String LIST = "https://api.github.com/user/repos?per_page=100&page=1&sort=full_name&direction=asc";
    @BeforeEach void setup() {
        var apiBuilder = RestClient.builder().baseUrl("https://api.github.com");
        var oauthBuilder = RestClient.builder().baseUrl("https://github.com");
        api = MockRestServiceServer.bindTo(apiBuilder).build();
        oauth = MockRestServiceServer.bindTo(oauthBuilder).build();
        client = new RestGitHubClient(apiBuilder.build(), oauthBuilder.build(),
                new GitHubProperties("test-client", "test-secret", "http://localhost:8082/api/github/callback"), json);
    }
    @AfterEach void verify() { api.verify(); oauth.verify(); }
    Map<String, Object> repo(long id) {
        return Map.of("id", id, "owner", Map.of("login", "octocat"), "name", "repo" + id,
                "full_name", "octocat/repo" + id, "private", true, "html_url", "https://github.com/octocat/repo" + id,
                "default_branch", "main", "language", "Java", "updated_at", "2026-01-01T00:00:00Z");
    }
    void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(status);
            assertThat(ex.getMessage()).doesNotContain("raw-secret", TOKEN, "test-secret");
        });
    }
    @Test void profileMapping() {
        api.expect(requestTo("https://api.github.com/user")).andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess("{\"id\":99,\"login\":\"octocat\"}", MediaType.APPLICATION_JSON));
        assertThat(client.currentUser(TOKEN)).isEqualTo(new GitHubClient.Profile(99, "octocat"));
    }
    @Test void repositoryMapping() {
        api.expect(requestTo(LIST)).andRespond(withSuccess(json.writeValueAsString(List.of(repo(123))), MediaType.APPLICATION_JSON));
        var r = client.repositories(TOKEN).getFirst();
        assertThat(r.githubRepositoryId()).isEqualTo(123); assertThat(r.privateRepository()).isTrue();
        assertThat(r.owner()).isEqualTo("octocat"); assertThat(r.name()).isEqualTo("repo123");
        assertThat(r.fullName()).isEqualTo("octocat/repo123"); assertThat(r.defaultBranch()).isEqualTo("main");
        assertThat(r.language()).isEqualTo("Java"); assertThat(r.updatedAt().toString()).isEqualTo("2026-01-01T00:00:00Z");
    }
    @Test void paginationFetchesBeyondFirstHundred() {
        var first = IntStream.rangeClosed(1, 100).mapToObj(i -> repo(i)).toList();
        api.expect(requestTo(LIST)).andRespond(withSuccess(json.writeValueAsString(first), MediaType.APPLICATION_JSON)
                .header("Link", "<https://api.github.com/user/repos?page=2>; rel=\"next\""));
        api.expect(requestTo(LIST.replace("page=1&", "page=2&"))).andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess(json.writeValueAsString(List.of(repo(101))), MediaType.APPLICATION_JSON));
        assertThat(client.repositories(TOKEN)).hasSize(101);
    }
    @Test void paginationNeverForwardsTokenToLinkHost() {
        api.expect(requestTo(LIST)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON)
                .header("Link", "<https://attacker.invalid/steal>; rel=\"next\""));
        api.expect(requestTo(LIST.replace("page=1&", "page=2&"))).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(client.repositories(TOKEN)).isEmpty();
    }
    @Test void selectedRepositoryIsVerifiedUsingCallerToken() {
        api.expect(requestTo(LIST)).andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess(json.writeValueAsString(List.of(repo(123))), MediaType.APPLICATION_JSON));
        api.expect(requestTo("https://api.github.com/repos/octocat/repo123")).andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess(json.writeValueAsString(repo(123)), MediaType.APPLICATION_JSON));
        assertThat(client.repository(TOKEN, 123).githubRepositoryId()).isEqualTo(123);
    }
    @Test void inaccessibleRepositoryCannotBeConnected() {
        api.expect(requestTo(LIST)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertStatus(() -> client.repository(TOKEN, 123), 404);
    }
    @Test void repositoryDetailNotFoundHandled() {
        api.expect(requestTo(LIST)).andRespond(withSuccess(json.writeValueAsString(List.of(repo(123))), MediaType.APPLICATION_JSON));
        api.expect(requestTo("https://api.github.com/repos/octocat/repo123"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("raw-secret"));
        assertStatus(() -> client.repository(TOKEN, 123), 404);
    }
    @Test void unauthorizedSanitized() {
        api.expect(requestTo(LIST)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("raw-secret"));
        assertStatus(() -> client.repositories(TOKEN), 401);
    }
    @Test void primaryRateLimitSanitized() {
        api.expect(requestTo(LIST)).andRespond(withStatus(HttpStatus.FORBIDDEN).header("X-RateLimit-Remaining", "0").body("raw-secret"));
        assertStatus(() -> client.repositories(TOKEN), 429);
    }
    @Test void secondaryRateLimitSanitized() {
        api.expect(requestTo(LIST)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").body("raw-secret"));
        assertStatus(() -> client.repositories(TOKEN), 429);
    }
    @Test void forbiddenSanitized() {
        api.expect(requestTo(LIST)).andRespond(withStatus(HttpStatus.FORBIDDEN).body("raw-secret"));
        assertStatus(() -> client.repositories(TOKEN), 403);
    }
    @Test void exchangeUsesFormAndPkce() {
        oauth.expect(requestTo("https://github.com/login/oauth/access_token")).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code_verifier=test-verifier")))
                .andRespond(withSuccess("{\"access_token\":\"test-only-token\",\"scope\":\"repo\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        var token = client.exchangeCode("test-code", "test-verifier");
        assertThat(token.accessToken()).isEqualTo(TOKEN); assertThat(token.scopes()).isEqualTo("repo");
        assertThat(token.toString()).doesNotContain(TOKEN);
    }
    @Test void oauthErrorBodySanitized() {
        oauth.expect(requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("{\"error\":\"bad_verification_code\",\"error_description\":\"raw-secret\"}", MediaType.APPLICATION_JSON));
        assertStatus(() -> client.exchangeCode("test-code", "test-verifier"), 400);
    }
    @Test void exchangeHttpFailureSanitized() {
        oauth.expect(requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("raw-secret"));
        assertStatus(() -> client.exchangeCode("test-code", "test-verifier"), 502);
    }
    @Test void malformedUpstreamResponseSanitized() {
        api.expect(requestTo(LIST)).andRespond(withSuccess("raw-secret", MediaType.APPLICATION_JSON));
        assertStatus(() -> client.repositories(TOKEN), 502);
    }
    @Test void missingConfigurationIsLazyAndClear() {
        var properties = new GitHubProperties("", "", "http://localhost:8082/api/github/callback");
        assertStatus(properties::requireConfigured, 503);
        var cipher = new GitHubTokenCipher("");
        assertStatus(cipher::requireConfigured, 503);
    }
}
