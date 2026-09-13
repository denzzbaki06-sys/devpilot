package com.devpilot;

import com.devpilot.indexing.*;
import com.devpilot.exception.ApiException;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GitHubContentClientTests {
    MockRestServiceServer server;
    RestGitHubContentClient client;
    ObjectMapper json = new ObjectMapper();
    static final String ROOT = "https://api.github.com/repos/octocat/sample";
    static final String COMMIT = "a".repeat(40), TREE = "b".repeat(40), BLOB = "c".repeat(40);
    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestGitHubContentClient(builder.build(), json, CodeChunkingTests.properties(100, 15));
    }
    @AfterEach void verify() { server.verify(); }
    void metadata() {
        server.expect(requestTo(ROOT)).andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("{\"id\":123,\"default_branch\":\"develop\",\"size\":10}", MediaType.APPLICATION_JSON));
    }
    void commit() {
        server.expect(requestTo(ROOT + "/commits/develop")).andRespond(withSuccess(json.writeValueAsString(Map.of(
                "sha", COMMIT, "commit", Map.of("tree", Map.of("sha", TREE)))), MediaType.APPLICATION_JSON));
    }
    void error(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(status); assertThat(ex.getMessage()).doesNotContain("raw-secret");
        });
    }
    @Test void defaultBranchCommitPinnedAndTreeMetadataMapped() {
        metadata(); commit();
        server.expect(requestTo(ROOT + "/git/trees/" + TREE + "?recursive=1"))
                .andRespond(withSuccess(json.writeValueAsString(Map.of("truncated", false, "tree", List.of(Map.of(
                        "path", "src/App.java", "sha", BLOB, "size", 12, "mode", "100644", "type", "blob")))), MediaType.APPLICATION_JSON));
        var snapshot = client.snapshot("test-token", "octocat", "sample", 123);
        assertThat(snapshot.commitSha()).isEqualTo(COMMIT); assertThat(snapshot.files()).hasSize(1);
        var entry = snapshot.files().getFirst(); assertThat(entry.path()).isEqualTo("src/App.java");
        assertThat(entry.sha()).isEqualTo(BLOB); assertThat(entry.size()).isEqualTo(12);
    }
    @Test void truncatedTreeRejectedInsteadOfPublishingPartialIndex() {
        metadata(); commit(); server.expect(requestTo(ROOT + "/git/trees/" + TREE + "?recursive=1"))
                .andRespond(withSuccess("{\"truncated\":true,\"tree\":[]}", MediaType.APPLICATION_JSON));
        error(502, () -> client.snapshot("test-token", "octocat", "sample", 123));
    }
    @Test void rawBlobUsesPrivateTokenAndFixedGithubOrigin() {
        server.expect(requestTo(ROOT + "/git/blobs/" + BLOB)).andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(header("Accept", "application/vnd.github.raw+json"))
                .andRespond(withSuccess("hello", MediaType.TEXT_PLAIN));
        assertThat(client.blob("test-token", "octocat", "sample", BLOB, 5)).isEqualTo("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void oversizedStreamRejectedEvenWithoutAccurateLength() {
        server.expect(requestTo(ROOT + "/git/blobs/" + BLOB)).andRespond(withSuccess("too-large", MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> client.blob("test-token", "octocat", "sample", BLOB, 4))
                .isInstanceOf(RestGitHubContentClient.OversizedBlobException.class);
    }
    @Test void emptyRepositoryHandled() {
        metadata(); server.expect(requestTo(ROOT + "/commits/develop")).andRespond(withStatus(HttpStatus.CONFLICT));
        assertThat(client.snapshot("test-token", "octocat", "sample", 123).files()).isEmpty();
    }
    @Test void repositoryIdentityMismatchRejected() {
        metadata(); error(502, () -> client.snapshot("test-token", "octocat", "sample", 999));
    }
    @Test void rateLimitSanitized() {
        server.expect(requestTo(ROOT)).andRespond(withStatus(HttpStatus.FORBIDDEN).header("X-RateLimit-Remaining", "0").body("raw-secret"));
        error(429, () -> client.snapshot("test-token", "octocat", "sample", 123));
    }
    @Test void privateAccessLostSanitized() {
        server.expect(requestTo(ROOT)).andRespond(withStatus(HttpStatus.NOT_FOUND).body("raw-secret"));
        error(404, () -> client.snapshot("test-token", "octocat", "sample", 123));
    }
    @Test void revokedTokenSanitized() {
        server.expect(requestTo(ROOT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("raw-secret"));
        error(401, () -> client.snapshot("test-token", "octocat", "sample", 123));
    }
    @Test void timeoutSanitized() {
        server.expect(requestTo(ROOT)).andRespond(withException(new java.net.SocketTimeoutException("raw-secret")));
        error(502, () -> client.snapshot("test-token", "octocat", "sample", 123));
    }
    @Test void invalidBlobReferenceNeverRequested() {
        error(502, () -> client.blob("test-token", "octocat", "sample", "https://attacker.invalid", 100));
    }
}
