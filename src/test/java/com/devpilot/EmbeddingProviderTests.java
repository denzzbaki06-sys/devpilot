package com.devpilot;

import com.devpilot.embedding.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class EmbeddingProviderTests {
    ObjectMapper json = new ObjectMapper();
    MockRestServiceServer server;
    OpenAiEmbeddingProvider provider;
    final List<Long> delays = new ArrayList<>();
    static final String URL = "https://api.openai.com/v1/embeddings", KEY = "test-only-key-not-real";
    EmbeddingProperties settings(String key, int dimensions) {
        return new EmbeddingProperties("openai", key, "https://api.openai.com/v1", "text-embedding-3-small", dimensions, 50, 3, 500);
    }
    @BeforeEach void setup() {
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenAiEmbeddingProvider(settings(KEY, 1536), builder.build(), json, delays::add);
    }
    @AfterEach void verify() { server.verify(); }
    Map<String, Object> item(int index, int axis) {
        var vector = new float[1536]; vector[axis] = 1;
        return Map.of("index", index, "embedding", vector);
    }
    String response(Object... items) { return json.writeValueAsString(Map.of("model", "text-embedding-3-small", "data", List.of(items))); }
    void error(Runnable action, String message) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(EmbeddingFailure.class, ex -> {
            assertThat(ex.getMessage()).contains(message).doesNotContain(KEY, "raw-provider-secret");
        });
    }
    @Test void singleRequestIncludesModelDimensionsAndInput() {
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(content().json(json.writeValueAsString(Map.of("model", "text-embedding-3-small", "dimensions", 1536,
                        "encoding_format", "float", "input", List.of("code")))))
                .andRespond(withSuccess(response(item(0, 0)), MediaType.APPLICATION_JSON));
        assertThat(provider.embed("code")[0]).isEqualTo(1);
    }
    @Test void shuffledBatchResponseIsReorderedByIndex() {
        server.expect(requestTo(URL)).andRespond(withSuccess(response(item(1, 1), item(0, 0)), MediaType.APPLICATION_JSON));
        var batch = provider.embedBatch(List.of("first", "second"));
        assertThat(batch.get(0)[0]).isEqualTo(1); assertThat(batch.get(1)[1]).isEqualTo(1);
    }
    @Test void wrongCountFails() {
        server.expect(requestTo(URL)).andRespond(withSuccess(response(item(0, 0)), MediaType.APPLICATION_JSON));
        error(() -> provider.embedBatch(List.of("first", "second")), "invalid response");
    }
    @Test void duplicateIndexFails() {
        server.expect(requestTo(URL)).andRespond(withSuccess(response(item(0, 0), item(0, 1)), MediaType.APPLICATION_JSON));
        error(() -> provider.embedBatch(List.of("first", "second")), "invalid response");
    }
    @Test void missingKeyIsLazyAndNeverExposed() {
        var absent = new OpenAiEmbeddingProvider(settings("", 1536), RestClient.create(), json, delays::add);
        error(() -> absent.embed("query"), "key is not configured");
        assertThat(settings(KEY, 1536).toString()).doesNotContain(KEY);
    }
    @Test void rateLimitRetriesWithBackoff() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(KEY));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(KEY));
        server.expect(requestTo(URL)).andRespond(withSuccess(response(item(0, 0)), MediaType.APPLICATION_JSON));
        provider.embed("query"); assertThat(delays).containsExactly(500L, 1000L);
    }
    @Test void retryIsBounded() {
        for (int i = 0; i < 4; i++) server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(KEY));
        error(() -> provider.embed("query"), "rate limited"); assertThat(delays).containsExactly(500L, 1000L, 2000L);
    }
    @Test void permanent400DoesNotRetryOrLeakRawResponse() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body(KEY + "raw-provider-secret"));
        error(() -> provider.embed("query"), "rejected the input"); assertThat(delays).isEmpty();
    }
    @Test void unauthorizedDoesNotRetry() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(KEY));
        error(() -> provider.embed("query"), "authentication failed"); assertThat(delays).isEmpty();
    }
    @Test void transient500Retries() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body(KEY));
        server.expect(requestTo(URL)).andRespond(withSuccess(response(item(0, 0)), MediaType.APPLICATION_JSON));
        provider.embed("query"); assertThat(delays).hasSize(1);
    }
    @Test void timeoutRetries() {
        server.expect(requestTo(URL)).andRespond(withException(new java.net.SocketTimeoutException(KEY)));
        server.expect(requestTo(URL)).andRespond(withSuccess(response(item(0, 0)), MediaType.APPLICATION_JSON));
        provider.embed("query"); assertThat(delays).hasSize(1);
    }
    @Test void configuredDimensionMismatchFailsBeforeNetwork() {
        var wrong = new OpenAiEmbeddingProvider(settings(KEY, 768), RestClient.create(), json, delays::add);
        error(() -> wrong.embed("query"), "dimensions");
    }
    @Test void responseDimensionMismatchFails() {
        server.expect(requestTo(URL)).andRespond(withSuccess(response(Map.of("index", 0, "embedding", new float[]{1, 0})), MediaType.APPLICATION_JSON));
        error(() -> provider.embed("query"), "dimensions");
    }
    @Test void zeroVectorRejected() {
        server.expect(requestTo(URL)).andRespond(withSuccess(response(Map.of("index", 0, "embedding", new float[1536])), MediaType.APPLICATION_JSON));
        error(() -> provider.embed("query"), "invalid response");
    }
    @Test void nonFiniteVectorRejected() {
        var vector = new float[1536]; vector[0] = Float.POSITIVE_INFINITY;
        error(() -> VectorValues.validate(vector, 1536), "invalid response");
    }
    @Test void malformedJsonSanitized() {
        server.expect(requestTo(URL)).andRespond(withSuccess("raw-provider-secret", MediaType.APPLICATION_JSON));
        error(() -> provider.embed("query"), "invalid response");
    }
    @Test void wrongModelRejected() {
        server.expect(requestTo(URL)).andRespond(withSuccess(json.writeValueAsString(Map.of("model", "other", "data", List.of(item(0, 0)))), MediaType.APPLICATION_JSON));
        error(() -> provider.embed("query"), "invalid response");
    }
    @Test void contextualInputAndHashIncludeMetadata() {
        var input = new ChunkEmbeddingInput("src/JwtService.java", 0, "JAVA", "void generate() {}", 20, 22, "generate", "METHOD", "hash");
        assertThat(input.contextual("owner/repo")).contains("File: src/JwtService.java", "Lines: 20-22", "Symbol: generate", "void generate() {}");
        var moved = new ChunkEmbeddingInput("src/Moved.java", 0, "JAVA", "void generate() {}", 20, 22, "generate", "METHOD", "hash");
        assertThat(input.inputHash("owner/repo")).isNotEqualTo(moved.inputHash("owner/repo"));
    }
}
