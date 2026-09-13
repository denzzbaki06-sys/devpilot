package com.devpilot.embedding;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import tools.jackson.databind.*;

@Component
@EnableConfigurationProperties(EmbeddingProperties.class)
public class OpenAiEmbeddingProvider implements EmbeddingProvider {
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private final EmbeddingProperties properties;
    private final RestClient client;
    private final ObjectMapper json;
    private final Sleeper sleeper;
    @Autowired public OpenAiEmbeddingProvider(EmbeddingProperties properties, ObjectMapper json) {
        this(properties, defaultClient(), json, Thread::sleep);
    }
    public OpenAiEmbeddingProvider(EmbeddingProperties properties, RestClient client, ObjectMapper json, Sleeper sleeper) {
        this.properties = properties; this.client = client; this.json = json; this.sleeper = sleeper;
    }
    private static RestClient defaultClient() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http); factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().requestFactory(factory).build();
    }
    @Override public String model() { return properties.model(); }
    @Override public int dimensions() { return properties.dimensions(); }
    @Override public String providerId() { return properties.provider() + ":" + properties.baseUrl().replaceAll("/+$", ""); }
    @Override public void requireConfigured() {
        properties.validate();
        if (properties.apiKey() == null || properties.apiKey().isBlank()) throw new EmbeddingFailure(EmbeddingFailure.Reason.MISSING_KEY);
    }
    private static class Retryable extends RuntimeException {
        final boolean rateLimit;
        Retryable(boolean rateLimit) { this.rateLimit = rateLimit; }
    }
    @Override public List<float[]> embedBatch(List<String> texts) {
        requireConfigured();
        if (texts.isEmpty() || texts.size() > properties.batchSize()) throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_INPUT);
        long bytes = 0;
        for (String text : texts) {
            int size = text.getBytes(StandardCharsets.UTF_8).length;
            if (text.isBlank() || size > 6000) throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_INPUT);
            bytes += size;
        }
        if (bytes > 240000) throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_INPUT);
        for (int attempt = 0;; attempt++) {
            boolean rateLimit = false;
            try { return response(request(texts), texts.size()); }
            catch (Retryable ex) { rateLimit = ex.rateLimit; }
            catch (ResourceAccessException ex) { /* Only transient I/O failures are retried. */ }
            catch (RestClientException ex) { throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED); }
            if (attempt >= properties.maxRetries()) throw new EmbeddingFailure(rateLimit ? EmbeddingFailure.Reason.RATE_LIMIT : EmbeddingFailure.Reason.UNAVAILABLE);
            try { sleeper.sleep(properties.retryDelayMillis() * (1L << attempt)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new EmbeddingFailure(EmbeddingFailure.Reason.INTERRUPTED); }
        }
    }
    private byte[] request(List<String> texts) {
        return client.post().uri(properties.baseUrl().replaceAll("/+$", "") + "/embeddings")
                .header("Authorization", "Bearer " + properties.apiKey()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model(), "dimensions", dimensions(), "encoding_format", "float", "input", texts))
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 429 || status >= 500) throw new Retryable(status == 429);
                    if (status == 401 || status == 403) throw new EmbeddingFailure(EmbeddingFailure.Reason.UNAUTHORIZED);
                    if (status < 200 || status >= 300) throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_INPUT);
                    byte[] body = response.getBody().readNBytes(8000001);
                    if (body.length > 8000000) throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED);
                    return body;
                });
    }
    private List<float[]> response(byte[] body, int expected) {
        try {
            var data = json.readTree(body);
            if (data == null || !model().equals(data.path("model").asText()) || !data.path("data").isArray()
                    || data.get("data").size() != expected) throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED);
            var result = new float[expected][];
            for (var entry : data.get("data")) {
                var index = entry.path("index");
                if (!index.isIntegralNumber() || !index.canConvertToInt() || index.asInt() < 0 || index.asInt() >= expected
                        || result[index.asInt()] != null) throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED);
                var array = entry.path("embedding");
                if (!array.isArray() || array.size() != dimensions()) throw new EmbeddingFailure(EmbeddingFailure.Reason.DIMENSIONS);
                var vector = new float[dimensions()];
                for (int i = 0; i < vector.length; i++) {
                    if (!array.get(i).isNumber()) throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED);
                    vector[i] = (float) array.get(i).asDouble();
                }
                VectorValues.validate(vector, dimensions()); result[index.asInt()] = vector;
            }
            return Arrays.asList(result);
        } catch (tools.jackson.core.JacksonException ex) { throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED); }
    }
}
