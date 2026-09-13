package com.devpilot;

import com.devpilot.embedding.*;
import com.devpilot.indexing.*;
import com.devpilot.github.*;
import com.devpilot.model.*;
import com.devpilot.repository.*;
import com.devpilot.service.JwtService;
import com.devpilot.support.TestEmbeddings;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Import(TestEmbeddings.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "devpilot.ai.embedding.batch-size=2")
class SemanticSearchIntegrationTests {
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
    @Autowired EmbeddingGateway gateway;
    @Autowired SemanticCodeSearchService search;
    @MockitoSpyBean SemanticSearchStore vectorStore;
    @MockitoBean GitHubContentClient upstream;
    @MockitoBean com.devpilot.rag.LlmProvider chat;
    @MockitoSpyBean(name = "deterministicEmbeddings") TestEmbeddings.DeterministicProvider provider;
    final HttpClient http = HttpClient.newHttpClient();
    User user, other;
    ConnectedRepository repo, otherRepo;
    String access, otherAccess;
    static final String TOKEN = "m5-test-only-github-token";
    static final String JAVA_SHA = "a".repeat(40), SQL_SHA = "b".repeat(40);
    static final String JAVA = "class JwtService {\n void generateAccessToken() {\n  boolean jwt = true;\n }\n}\n";
    static final String SQL = "SELECT * FROM database_users;\n";
    @BeforeEach void setup() {
        user = createUser(); other = createUser(); access = jwt.createAccessToken(user); otherAccess = jwt.createAccessToken(other);
        repo = createRepo(user); otherRepo = createRepo(other);
        when(upstream.snapshot(TOKEN, "owner", "sample", 123)).thenReturn(new GitHubContentClient.Snapshot("c".repeat(40), "owner", "sample", List.of(
                new GitHubContentClient.Entry("src/JwtService.java", JAVA_SHA, JAVA.getBytes(StandardCharsets.UTF_8).length, "100644"),
                new GitHubContentClient.Entry("src/Database.sql", SQL_SHA, SQL.getBytes(StandardCharsets.UTF_8).length, "100644"))));
        when(upstream.blob(TOKEN, "owner", "sample", JAVA_SHA, 500000)).thenReturn(JAVA.getBytes(StandardCharsets.UTF_8));
        when(upstream.blob(TOKEN, "owner", "sample", SQL_SHA, 500000)).thenReturn(SQL.getBytes(StandardCharsets.UTF_8));
    }
    User createUser() {
        var user = users.saveAndFlush(new User("M5", "m5-" + UUID.randomUUID() + "@example.test", "unused-test-hash"));
        var connection = new GitHubConnection(user.getId());
        connection.connect(99, "owner", cipher.encrypt(TOKEN, "github-token:" + user.getId()), "repo"); connections.saveAndFlush(connection);
        return user;
    }
    ConnectedRepository createRepo(User user) {
        return repositories.saveAndFlush(new ConnectedRepository(user.getId(), new GitHubRepositoryDto(123, "owner", "sample", "owner/sample",
                true, "https://github.com/owner/sample", "main", "Java", Instant.now())));
    }
    @AfterEach void cleanup() throws Exception {
        for (int i = 0; i < 500; i++) {
            int active = jdbc.queryForObject("SELECT count(*) FROM repositories WHERE user_id IN (?, ?) AND status = 'INDEXING'", Integer.class, user.getId(), other.getId());
            if (active == 0) break; Thread.sleep(20);
        }
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", user.getId(), other.getId());
    }
    HttpResponse<String> request(String method, Long id, String suffix, Object body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/repositories/" + id + suffix));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode awaitDone(Long id, String token) throws Exception {
        for (int i = 0; i < 500; i++) {
            var response = request("GET", id, "/index-status", null, token);
            assertThat(response.statusCode()).isEqualTo(200); var data = json.readTree(response.body());
            if (!"INDEXING".equals(data.get("status").asText())) return data;
            Thread.sleep(20);
        }
        throw new AssertionError("Embedding job did not finish");
    }
    JsonNode index(Long id, String token) throws Exception {
        assertThat(request("POST", id, "/index", null, token).statusCode()).isEqualTo(202);
        return awaitDone(id, token);
    }
    JsonNode success() throws Exception {
        var status = index(repo.getId(), access); assertThat(status.get("status").asText()).isEqualTo("READY"); return status;
    }
    HttpResponse<String> query(String text, Integer limit) throws Exception {
        var body = new HashMap<String, Object>(); body.put("query", text); if (limit != null) body.put("limit", limit);
        return request("POST", repo.getId(), "/search", body, access);
    }
    int count() { return jdbc.queryForObject("SELECT count(*) FROM chunk_embeddings WHERE repository_id = ?", Integer.class, repo.getId()); }
    @Test void indexingCreatesVectorsBeforeReadyAndStoresMetadata() throws Exception {
        var status = success(); assertThat(count()).isEqualTo(4);
        assertThat(status.get("embeddingsCreated").asInt()).isEqualTo(4);
        assertThat(status.get("embeddingModel").asText()).isEqualTo("text-embedding-3-small");
        var metadata = jdbc.queryForList("SELECT dimensions, model, vector_dims(embedding) AS actual FROM chunk_embeddings WHERE repository_id = ?", repo.getId());
        assertThat(metadata).allMatch(row -> row.get("dimensions").equals(1536) && row.get("actual").equals(1536) && row.get("model").equals("text-embedding-3-small"));
        verify(provider, times(2)).embedBatch(anyList());
    }
    @Test void realPgvectorRanksNearestAuthorizedChunksWithFileLines() throws Exception {
        success(); var response = query("Where is JWT authentication implemented?", 3);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = json.readTree(response.body()); assertThat(data.get("results").size()).isEqualTo(3);
        for (var result : data.get("results")) {
            assertThat(result.get("path").asText()).isEqualTo("src/JwtService.java");
            assertThat(result.get("similarity").asDouble()).isCloseTo(1.0, within(0.00001));
            var stored = jdbc.queryForMap("SELECT content, start_line, end_line FROM code_chunks WHERE id = ?", result.get("chunkId").asLong());
            assertThat(result.get("content").asText()).isEqualTo(stored.get("content"));
            assertThat(result.get("startLine").asInt()).isEqualTo(stored.get("start_line"));
            assertThat(result.get("endLine").asInt()).isEqualTo(stored.get("end_line"));
        }
        assertThat(response.body()).doesNotContain("embedding", "vector", TOKEN, "access_token_encrypted");
    }
    @Test void databaseQueryFindsSqlChunk() throws Exception {
        success(); var response = query("database SQL query", 1); assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("results").get(0).get("path").asText()).isEqualTo("src/Database.sql");
    }
    @Test void searchIsRepositoryScopedEvenWithOtherVectors() throws Exception {
        success(); assertThat(index(otherRepo.getId(), otherAccess).get("status").asText()).isEqualTo("READY");
        var response = query("JWT authentication", 20); assertThat(response.statusCode()).isEqualTo(200);
        var ids = jdbc.queryForList("SELECT id FROM code_chunks WHERE repository_id = ?", Long.class, repo.getId());
        for (var result : json.readTree(response.body()).get("results")) assertThat(ids).contains(result.get("chunkId").asLong());
        assertThat(json.readTree(response.body()).get("results").size()).isEqualTo(4);
    }
    @Test void nonOwnerCannotSearchAndDoesNotCallProvider() throws Exception {
        success(); clearInvocations(provider);
        assertThat(request("POST", repo.getId(), "/search", Map.of("query", "JWT"), otherAccess).statusCode()).isEqualTo(404);
        verify(provider, never()).embedBatch(anyList());
    }
    @Test void anonymousSearchIsBlocked() throws Exception {
        assertThat(request("POST", repo.getId(), "/search", Map.of("query", "JWT"), null).statusCode()).isEqualTo(401);
    }
    @Test void blankQueryRejected() throws Exception { assertThat(query(" ", 8).statusCode()).isEqualTo(400); }
    @Test void oversizedQueryRejected() throws Exception { assertThat(query("a".repeat(2001), 8).statusCode()).isEqualTo(400); }
    @Test void invalidLimitsRejected() throws Exception {
        assertThat(query("JWT", 0).statusCode()).isEqualTo(400); assertThat(query("JWT", 21).statusCode()).isEqualTo(400);
    }
    @Test void defaultLimitWorks() throws Exception { success(); assertThat(query("JWT", null).statusCode()).isEqualTo(200); }
    @Test void repositoryWithoutReadySnapshotCannotSearch() throws Exception {
        assertThat(query("JWT", 8).statusCode()).isEqualTo(409); verify(provider, never()).embedBatch(anyList());
    }
    @Test void legacyOrIncompleteEmbeddingsRequireReindex() throws Exception {
        success(); jdbc.update("DELETE FROM chunk_embeddings WHERE repository_id = ?", repo.getId());
        assertThat(query("JWT", 8).statusCode()).isEqualTo(409);
    }
    @Test void unchangedEmbeddingsAreReusedWithoutProviderCalls() throws Exception {
        success(); clearInvocations(provider); var status = success();
        assertThat(count()).isEqualTo(4); verify(provider, never()).embedBatch(anyList());
        assertThat(status.get("job").get("summary").get("embeddingsReused").asInt()).isEqualTo(4);
    }
    @Test void modelChangeDoesNotReuseOldVectors() throws Exception {
        success(); doReturn("test-model-v2").when(provider).model(); clearInvocations(provider);
        var status = success(); verify(provider, times(2)).embedBatch(anyList());
        assertThat(status.get("embeddingModel").asText()).isEqualTo("test-model-v2");
        assertThat(status.get("job").get("summary").get("embeddingsReused").asInt()).isZero(); assertThat(count()).isEqualTo(4);
    }
    @Test void partialProviderFailureKeepsPreviousChunksAndVectorsSearchable() throws Exception {
        success(); var before = jdbc.queryForList("SELECT id, code_chunk_id, input_hash FROM chunk_embeddings WHERE repository_id = ? ORDER BY id", repo.getId());
        doReturn("test-model-v2").when(provider).model(); var calls = new AtomicInteger();
        doAnswer(inv -> { if (calls.incrementAndGet() == 2) throw new EmbeddingFailure(EmbeddingFailure.Reason.UNAVAILABLE); return inv.callRealMethod(); })
                .when(provider).embedBatch(anyList());
        var status = index(repo.getId(), access); assertThat(status.get("status").asText()).isEqualTo("FAILED");
        assertThat(status.get("lastError").asText()).contains("Embedding provider unavailable");
        assertThat(jdbc.queryForList("SELECT id, code_chunk_id, input_hash FROM chunk_embeddings WHERE repository_id = ? ORDER BY id", repo.getId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM indexing_staged_embeddings e JOIN indexing_jobs j ON j.id = e.job_id WHERE j.repository_id = ?", Integer.class, repo.getId())).isZero();
        doReturn("text-embedding-3-small").when(provider).model(); doCallRealMethod().when(provider).embedBatch(anyList());
        assertThat(query("JWT authentication", 3).statusCode()).isEqualTo(200);
    }
    @Test void existingSnapshotSearchWorksWhileReindexIsRunning() throws Exception {
        success(); jdbc.update("UPDATE repositories SET status = 'INDEXING' WHERE id = ?", repo.getId());
        try { assertThat(query("JWT", 3).statusCode()).isEqualTo(200); }
        finally { jdbc.update("UPDATE repositories SET status = 'READY' WHERE id = ?", repo.getId()); }
    }
    @Test void dimensionMismatchIsExplicitAndDoesNotReplaceVectors() throws Exception {
        success(); doReturn(768).when(provider).dimensions();
        var status = index(repo.getId(), access);
        assertThat(status.get("status").asText()).isEqualTo("FAILED"); assertThat(status.get("lastError").asText()).contains("dimensions");
        assertThat(count()).isEqualTo(4);
    }
    @Test void missingKeyIsSafeConfigurationError() throws Exception {
        success(); doThrow(new EmbeddingFailure(EmbeddingFailure.Reason.MISSING_KEY)).when(provider).requireConfigured();
        var response = query("JWT", 3); assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("key is not configured").doesNotContain(TOKEN, "stackTrace");
    }
    @Test void longInputIsBatchedWithoutTruncation() {
        gateway.requireConfigured(); clearInvocations(provider);
        var input = "JWT authentication ".repeat(1000);
        var vectors = gateway.embed(List.of(input), () -> {}); assertThat(vectors).hasSize(1);
        var captured = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(provider, atLeast(2)).embedBatch(captured.capture());
        var parts = new ArrayList<String>();
        for (var batch : captured.getAllValues()) for (Object text : batch) parts.add((String) text);
        assertThat(String.join("", parts)).isEqualTo(input);
        assertThat(parts).allMatch(text -> text.getBytes(StandardCharsets.UTF_8).length <= 6000);
    }
    @Test void hnswCosineIndexExists() {
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_chunk_embeddings_cosine'", String.class))
                .contains("hnsw", "vector_cosine_ops");
    }

    @Test void vectorQueryFailureIsSanitized() throws Exception {
        success();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("raw-provider-secret"))
                .when(vectorStore).search(anyLong(), anyLong(), any(), any(float[].class), anyInt());
        var response = query("JWT authentication", 3);
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("Vector search unavailable").doesNotContain("raw-provider-secret", "stackTrace");
    }
    @Test void searchDimensionMismatchIsExplicit() throws Exception {
        success(); doReturn(768).when(provider).dimensions();
        var response = query("JWT authentication", 3);
        assertThat(response.statusCode()).isEqualTo(503); assertThat(response.body()).contains("dimensions");
    }
    @Test void askUsesRealRetrievalAndOnlyCitedSources() throws Exception {
        success();
        when(chat.model()).thenReturn("test-chat");
        when(chat.complete(anyString(), anyString(), anyString())).thenReturn(new com.devpilot.rag.LlmProvider.Completion(
                List.of(new com.devpilot.rag.LlmProvider.Statement("The retrieved source declares JwtService.", List.of(1))), false, "test-chat"));
        var response = request("POST", repo.getId(), "/ask", Map.of("question", "JWT authentication"), access);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = json.readTree(response.body());
        assertThat(data.path("grounded").asBoolean()).isTrue();
        assertThat(data.path("sources").size()).isEqualTo(1);
        assertThat(data.path("answer").asText()).endsWith("[1]");
        long chunkId = data.path("sources").get(0).path("chunkId").asLong();
        assertThat(jdbc.queryForObject("SELECT repository_id FROM code_chunks WHERE id=?", Long.class, chunkId)).isEqualTo(repo.getId());
        assertThat(response.body()).doesNotContain(TOKEN, "embedding", "content", "vector");
        verify(chat).complete(contains("untrusted"), eq("JWT authentication"), contains("SOURCE 1\nFile: src/JwtService.java"));
    }
    @Test void askRejectsUnknownCitations() throws Exception {
        success();
        when(chat.complete(anyString(), anyString(), anyString())).thenReturn(new com.devpilot.rag.LlmProvider.Completion(
                List.of(new com.devpilot.rag.LlmProvider.Statement("Unsupported", List.of(99))), false, "test-chat"));
        assertThat(request("POST", repo.getId(), "/ask", Map.of("question", "JWT"), access).statusCode()).isEqualTo(502);
    }
    @Test void askAbstainsWithoutReturningSourcesOrModelText() throws Exception {
        success();
        when(chat.complete(anyString(), anyString(), anyString())).thenReturn(new com.devpilot.rag.LlmProvider.Completion(
                List.of(new com.devpilot.rag.LlmProvider.Statement("invented answer", List.of(1))), true, "test-chat"));
        var response = request("POST", repo.getId(), "/ask", Map.of("question", "Unknown system"), access);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = json.readTree(response.body());
        assertThat(data.path("grounded").asBoolean()).isFalse();
        assertThat(data.path("sources").size()).isZero();
        assertThat(response.body()).doesNotContain("invented answer");
    }
    @Test void askEnforcesAuthenticationOwnershipAndReadyBeforeProvider() throws Exception {
        var body = Map.of("question", "JWT");
        assertThat(request("POST", repo.getId(), "/ask", body, null).statusCode()).isEqualTo(401);
        assertThat(request("POST", repo.getId(), "/ask", body, otherAccess).statusCode()).isEqualTo(404);
        assertThat(request("POST", repo.getId(), "/ask", body, access).statusCode()).isEqualTo(409);
        verifyNoInteractions(chat);
    }
    @Test void askValidatesQuestionAndBoundedTopK() throws Exception {
        success();
        for (var body : List.of(Map.of("question", " "), Map.of("question", "x".repeat(4001)), Map.of("question", "JWT", "topK", 21)))
            assertThat(request("POST", repo.getId(), "/ask", body, access).statusCode()).isEqualTo(400);
        verifyNoInteractions(chat);
    }
    @Test void askRejectsIndexChangedDuringChat() throws Exception {
        success();
        when(chat.complete(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            jdbc.update("UPDATE repositories SET status='FAILED' WHERE id=?", repo.getId());
            return new com.devpilot.rag.LlmProvider.Completion(List.of(new com.devpilot.rag.LlmProvider.Statement("Answer", List.of(1))), false, "test-chat");
        });
        assertThat(request("POST", repo.getId(), "/ask", Map.of("question", "JWT"), access).statusCode()).isEqualTo(409);
    }
    @Test void askMissingChatKeyReturnsConfigurationError() throws Exception {
        success();
        doThrow(new com.devpilot.exception.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Chat API key is not configured"))
                .when(chat).requireConfigured();
        var response = request("POST", repo.getId(), "/ask", Map.of("question", "JWT"), access);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("Chat API key is not configured");
        verify(chat, never()).complete(anyString(), anyString(), anyString());
    }
    @Test void askAcceptsQuestionBeyondSearchEndpointLimit() throws Exception {
        success();
        when(chat.complete(anyString(), anyString(), anyString())).thenReturn(new com.devpilot.rag.LlmProvider.Completion(List.of(), true, "test-chat"));
        assertThat(request("POST", repo.getId(), "/ask", Map.of("question", "JWT " + "q".repeat(3000)), access).statusCode()).isEqualTo(200);
    }
    @Test void askRejectsUncitedStatements() throws Exception {
        success();
        when(chat.complete(anyString(), anyString(), anyString())).thenReturn(new com.devpilot.rag.LlmProvider.Completion(
                List.of(new com.devpilot.rag.LlmProvider.Statement("Unsupported", List.of())), false, "test-chat"));
        assertThat(request("POST", repo.getId(), "/ask", Map.of("question", "JWT"), access).statusCode()).isEqualTo(502);
    }
}
