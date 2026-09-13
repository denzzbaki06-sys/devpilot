package com.devpilot;

import com.devpilot.indexing.*;
import com.devpilot.github.*;
import com.devpilot.model.*;
import com.devpilot.repository.*;
import com.devpilot.service.JwtService;
import com.devpilot.exception.ApiException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.context.bean.override.mockito.*;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@org.springframework.context.annotation.Import(com.devpilot.support.TestEmbeddings.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IndexingIntegrationTests {
    @DynamicPropertySource static void key(DynamicPropertyRegistry registry) {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String key = Base64.getEncoder().encodeToString(bytes); registry.add("github.token-encryption-key", () -> key);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired ConnectedRepositoryRepository repositories;
    @Autowired GitHubConnectionRepository connections;
    @Autowired GitHubTokenCipher cipher;
    @Autowired JwtService jwt;
    @MockitoBean GitHubContentClient upstream;
    @MockitoSpyBean IndexJobStore store;
    @Autowired IndexWorker worker;
    @Autowired GitHubIndexAccess indexingAccess;
    @Autowired com.devpilot.embedding.ChunkEmbeddingStage embeddingStage;
    final HttpClient http = HttpClient.newHttpClient();
    User user, other;
    ConnectedRepository repository;
    String access, otherAccess;
    CountDownLatch gate = new CountDownLatch(0);
    static final String TOKEN = "m4-test-only-private-token";
    static final String JAVA_SHA = "a".repeat(40), MD_SHA = "b".repeat(40), BINARY_SHA = "c".repeat(40);
    static final String JAVA = "class Demo {\n void run() {\n  System.out.println(\"hello\");\n }\n}\n";
    @BeforeEach void setup() {
        user = users.saveAndFlush(new User("M4", "m4-" + UUID.randomUUID() + "@example.test", "unused-test-hash"));
        other = users.saveAndFlush(new User("M4 Other", "m4-" + UUID.randomUUID() + "@example.test", "unused-test-hash"));
        access = jwt.createAccessToken(user); otherAccess = jwt.createAccessToken(other);
        var connection = new GitHubConnection(user.getId());
        connection.connect(99, "octocat", cipher.encrypt(TOKEN, "github-token:" + user.getId()), "repo");
        connections.saveAndFlush(connection);
        repository = repositories.saveAndFlush(new ConnectedRepository(user.getId(), new GitHubRepositoryDto(123, "octocat", "sample",
                "octocat/sample", true, "https://github.com/octocat/sample", "main", "Java", Instant.now())));
        when(upstream.snapshot(TOKEN, "octocat", "sample", 123)).thenReturn(sample());
        when(upstream.blob(TOKEN, "octocat", "sample", JAVA_SHA, 500000)).thenReturn(JAVA.getBytes(StandardCharsets.UTF_8));
        when(upstream.blob(TOKEN, "octocat", "sample", MD_SHA, 500000)).thenReturn("# Sample\nDocs\n".getBytes(StandardCharsets.UTF_8));
        when(upstream.blob(TOKEN, "octocat", "sample", BINARY_SHA, 500000)).thenReturn(new byte[]{65, 0});
    }
    @AfterEach void cleanup() throws Exception {
        gate.countDown();
        for (int i = 0; i < 500; i++) {
            if (!"INDEXING".equals(jdbc.queryForObject("SELECT status FROM repositories WHERE id = ?", String.class, repository.getId()))) break;
            if (i == 499) break;
            Thread.sleep(20);
        }
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", user.getId(), other.getId());
    }
    GitHubContentClient.Snapshot sample() {
        return new GitHubContentClient.Snapshot("d".repeat(40), "octocat", "sample", List.of(
                new GitHubContentClient.Entry("src/Demo.java", JAVA_SHA, JAVA.getBytes(StandardCharsets.UTF_8).length, "100644"),
                new GitHubContentClient.Entry("README.md", MD_SHA, 14, "100644"),
                new GitHubContentClient.Entry(".env", "e".repeat(40), 10, "100644"),
                new GitHubContentClient.Entry("node_modules/pkg/a.js", "f".repeat(40), 5, "100644"),
                new GitHubContentClient.Entry("src/binary.js", BINARY_SHA, 2, "100644"),
                new GitHubContentClient.Entry("Huge.java", "1".repeat(40), 500001, "100644")));
    }
    HttpResponse<String> request(String method, String suffix, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/repositories/" + repository.getId() + suffix));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> start() throws Exception { return request("POST", "/index", access); }
    JsonNode awaitDone() throws Exception {
        for (int i = 0; i < 500; i++) {
            var response = request("GET", "/index-status", access); assertThat(response.statusCode()).isEqualTo(200);
            var data = json.readTree(response.body());
            if (!"INDEXING".equals(data.get("status").asText())) return data;
            Thread.sleep(20);
        }
        throw new AssertionError("Index job did not finish within 10 seconds");
    }
    JsonNode success() throws Exception {
        assertThat(start().statusCode()).isEqualTo(202);
        var status = awaitDone(); assertThat(status.get("status").asText()).isEqualTo("READY"); return status;
    }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE repository_id = ?", Integer.class, repository.getId()); }
    @Test void ownerStartsAsyncAndSummaryIsAccurate() throws Exception {
        var response = start(); assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().firstValue("Location").orElseThrow()).endsWith("/index-status");
        var status = awaitDone(); assertThat(status.get("status").asText()).isEqualTo("READY");
        var summary = status.get("job").get("summary");
        assertThat(summary.get("filesDiscovered").asInt()).isEqualTo(6);
        assertThat(summary.get("filesIndexed").asInt()).isEqualTo(2);
        assertThat(summary.get("filesSkipped").asInt()).isEqualTo(4);
        assertThat(summary.get("chunksCreated").asInt()).isEqualTo(4);
        assertThat(summary.get("lineCount").asInt()).isEqualTo(7);
        assertThat(summary.get("languages").get("JAVA").asInt()).isEqualTo(1);
        assertThat(status.get("job").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(status.get("lastIndexedAt").isNull()).isFalse();
        assertThat(count("repository_files")).isEqualTo(2); assertThat(count("code_chunks")).isEqualTo(4);
        assertThat(status.toString()).doesNotContain(TOKEN, "System.out");
    }
    @Test void privateTokenIsDecryptedOnlyForMockedContentClient() throws Exception {
        success(); verify(upstream).snapshot(TOKEN, "octocat", "sample", 123);
        verify(upstream).blob(TOKEN, "octocat", "sample", JAVA_SHA, 500000);
        String stored = jdbc.queryForObject("SELECT access_token_encrypted FROM github_connections WHERE user_id = ?", String.class, user.getId());
        assertThat(stored).doesNotContain(TOKEN);
        verify(upstream, times(3)).blob(anyString(), anyString(), anyString(), anyString(), anyInt());
    }
    @Test void nonOwnerCannotStartOrReadStatus() throws Exception {
        assertThat(request("POST", "/index", otherAccess).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/index-status", otherAccess).statusCode()).isEqualTo(404);
        assertThat(count("indexing_jobs")).isZero(); verifyNoInteractions(upstream);
    }
    @Test void unauthenticatedIndexBlocked() throws Exception { assertThat(request("POST", "/index", null).statusCode()).isEqualTo(401); }
    @Test void concurrentStartsOnlyCreateOneJobAndDeletionIsBlocked() throws Exception {
        gate = new CountDownLatch(1); var entered = new CountDownLatch(1);
        when(upstream.snapshot(TOKEN, "octocat", "sample", 123)).thenAnswer(inv -> { entered.countDown(); gate.await(10, TimeUnit.SECONDS); return sample(); });
        assertThat(start().statusCode()).isEqualTo(202); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(start().statusCode()).isEqualTo(409);
        assertThat(request("DELETE", "", access).statusCode()).isEqualTo(409);
        assertThat(count("indexing_jobs")).isEqualTo(1); gate.countDown();
        assertThat(awaitDone().get("status").asText()).isEqualTo("READY");
    }
    @Test void rateLimitMarksJobAndRepositoryFailed() throws Exception {
        when(upstream.snapshot(TOKEN, "octocat", "sample", 123)).thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOKEN));
        assertThat(start().statusCode()).isEqualTo(202); var status = awaitDone();
        assertThat(status.get("status").asText()).isEqualTo("FAILED");
        assertThat(status.get("job").get("status").asText()).isEqualTo("FAILED");
        assertThat(status.get("lastError").asText()).contains("rate limit").doesNotContain(TOKEN);
        assertThat(count("code_chunks")).isZero();
    }
    @Test void failedReindexKeepsPreviousSnapshot() throws Exception {
        success(); var before = jdbc.queryForList("SELECT id, content_hash FROM code_chunks WHERE repository_id = ? ORDER BY id", repository.getId());
        when(upstream.blob(TOKEN, "octocat", "sample", MD_SHA, 500000)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, TOKEN));
        assertThat(start().statusCode()).isEqualTo(202); var status = awaitDone();
        assertThat(status.get("status").asText()).isEqualTo("FAILED");
        assertThat(status.get("filesIndexed").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT id, content_hash FROM code_chunks WHERE repository_id = ? ORDER BY id", repository.getId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM indexing_staged_files s JOIN indexing_jobs j ON j.id = s.job_id WHERE j.repository_id = ?", Integer.class, repository.getId())).isZero();
    }
    @Test void successfulReindexReplacesChunksWithoutDuplicates() throws Exception {
        success(); var hashes = jdbc.queryForList("SELECT content_hash FROM code_chunks WHERE repository_id = ? ORDER BY content_hash", repository.getId());
        success(); assertThat(count("repository_files")).isEqualTo(2); assertThat(count("code_chunks")).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT content_hash FROM code_chunks WHERE repository_id = ? ORDER BY content_hash", repository.getId())).isEqualTo(hashes);
        assertThat(count("indexing_jobs")).isEqualTo(2);
    }
    @Test void databasePublishFailureRollsBackReplacement() throws Exception {
        success(); var before = jdbc.queryForList("SELECT id, content_hash FROM code_chunks WHERE repository_id = ? ORDER BY id", repository.getId());
        doAnswer(inv -> {
            IndexJobStore.Ticket ticket = inv.getArgument(0);
            jdbc.update("UPDATE indexing_staged_files SET chunks = jsonb_set(chunks, '{0,startLine}', '0') WHERE job_id = ?", ticket.jobId());
            return inv.callRealMethod();
        }).when(store).publish(any(), any());
        assertThat(start().statusCode()).isEqualTo(202); var status = awaitDone();
        assertThat(status.get("status").asText()).isEqualTo("FAILED");
        assertThat(status.get("lastError").asText()).contains("storage failed");
        assertThat(jdbc.queryForList("SELECT id, content_hash FROM code_chunks WHERE repository_id = ? ORDER BY id", repository.getId())).isEqualTo(before);
    }
    @Test void lineRangesAndContentHashesPersistCorrectly() throws Exception {
        success();
        var rows = jdbc.queryForList("SELECT c.* FROM code_chunks c WHERE c.repository_id = ? AND symbol_name = 'run'", repository.getId());
        assertThat(rows).hasSize(1); var row = rows.getFirst();
        assertThat(row.get("start_line")).isEqualTo(2); assertThat(row.get("end_line")).isEqualTo(4);
        assertThat(row.get("content_hash")).isEqualTo(ContentHashes.sha256((String) row.get("content")));
        assertThat(jdbc.queryForObject("SELECT content_hash FROM repository_files WHERE repository_id = ? AND path = 'src/Demo.java'", String.class, repository.getId())).isEqualTo(ContentHashes.sha256(JAVA));
    }
    @Test void emptyRepositoryProducesReadyEmptySnapshot() throws Exception {
        when(upstream.snapshot(TOKEN, "octocat", "sample", 123)).thenReturn(new GitHubContentClient.Snapshot(null, "octocat", "sample", List.of()));
        var status = success(); assertThat(status.get("filesIndexed").asInt()).isZero(); assertThat(count("code_chunks")).isZero();
    }
    @Test void revokedTokenFailsSafely() throws Exception {
        when(upstream.snapshot(TOKEN, "octocat", "sample", 123)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, TOKEN));
        assertThat(start().statusCode()).isEqualTo(202); assertThat(awaitDone().get("lastError").asText()).contains("reconnect").doesNotContain(TOKEN);
    }
    @Test void malformedTextIsSkippedWithoutFailingOtherFiles() throws Exception {
        when(upstream.blob(TOKEN, "octocat", "sample", BINARY_SHA, 500000)).thenReturn(new byte[]{(byte) 0xc3, 0x28});
        var status = success(); assertThat(status.get("job").get("summary").get("skipReasons").get("INVALID_UTF8").asInt()).isEqualTo(1);
    }
    @Test void abandonedJobIsRecoveredAndCanBeRetried() throws Exception {
        var ticket = store.queue(user.getId(), repository.getId());
        jdbc.update("UPDATE indexing_jobs SET heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '3 minutes' WHERE id = ?", ticket.jobId());
        store.expire(ticket);
        assertThat(store.status(user.getId(), repository.getId()).status()).isEqualTo("FAILED");
        worker.run(ticket); verifyNoInteractions(upstream); success();
    }
    @Test void missingGitHubConnectionRejectsBeforeQueuing() throws Exception {
        jdbc.update("DELETE FROM github_connections WHERE user_id = ?", user.getId());
        assertThat(start().statusCode()).isEqualTo(409); assertThat(count("indexing_jobs")).isZero();
    }

    @Test void repositoryTotalByteLimitFailsWithoutPartialSnapshot() {
        var ticket = store.queue(user.getId(), repository.getId());
        var limits = new IndexingProperties(500000, 100, 15, 20000, 1, 20000, 100000, 1800);
        var limited = new IndexWorker(store, indexingAccess,
                new SourceFilePolicy(limits), new CodeChunker(limits), limits, embeddingStage);
        limited.run(ticket);
        assertThat(store.status(user.getId(), repository.getId()).status()).isEqualTo("FAILED");
        assertThat(store.status(user.getId(), repository.getId()).lastError()).contains("limits");
        assertThat(count("repository_files")).isZero();
    }

    @Test void executorRejectionMarksJobFailed() {
        var rejecting = new IndexingService(store, worker, task -> { throw new java.util.concurrent.RejectedExecutionException(); });
        assertThatThrownBy(() -> rejecting.start(user.getId(), repository.getId())).isInstanceOf(ApiException.class);
        assertThat(store.status(user.getId(), repository.getId()).status()).isEqualTo("FAILED");
        assertThat(store.status(user.getId(), repository.getId()).lastError()).contains("capacity");
        verifyNoInteractions(upstream);
    }
    @Test void previousSnapshotRemainsVisibleDuringReindex() throws Exception {
        success();
        gate = new CountDownLatch(1); var entered = new CountDownLatch(1);
        when(upstream.snapshot(TOKEN, "octocat", "sample", 123)).thenAnswer(inv -> {
            entered.countDown(); gate.await(10, TimeUnit.SECONDS); return sample();
        });
        assertThat(start().statusCode()).isEqualTo(202); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var progress = store.status(user.getId(), repository.getId());
        assertThat(progress.status()).isEqualTo("INDEXING");
        assertThat(progress.filesIndexed()).isEqualTo(2); assertThat(progress.chunksCreated()).isEqualTo(4);
        assertThat(count("code_chunks")).isEqualTo(4);
        gate.countDown(); assertThat(awaitDone().get("status").asText()).isEqualTo("READY");
    }
}
