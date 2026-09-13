package com.devpilot.embedding;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("devpilot.ai.embedding")
public record EmbeddingProperties(@DefaultValue("openai") String provider, @DefaultValue("") String apiKey,
        @DefaultValue("https://api.openai.com/v1") String baseUrl,
        @DefaultValue("text-embedding-3-small") String model, @DefaultValue("1536") int dimensions,
        @DefaultValue("50") int batchSize, @DefaultValue("3") int maxRetries, @DefaultValue("500") long retryDelayMillis) {
    public void validate() {
        if (dimensions != 1536) throw new EmbeddingFailure(EmbeddingFailure.Reason.DIMENSIONS);
        if (model == null || model.isBlank() || model.length() > 255 || batchSize < 1 || batchSize > 100
                || maxRetries < 0 || maxRetries > 3 || retryDelayMillis < 0 || retryDelayMillis > 1000
                || !("openai".equals(provider) || "openai-compatible".equals(provider))) throw new EmbeddingFailure(EmbeddingFailure.Reason.CONFIG);
        try {
            var uri = URI.create(baseUrl);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || baseUrl.length() > 480 || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                    && SetHolder.LOCAL.contains(uri.getHost())))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw new EmbeddingFailure(EmbeddingFailure.Reason.CONFIG); }
    }
    private static class SetHolder { static final java.util.Set<String> LOCAL = java.util.Set.of("localhost", "127.0.0.1"); }
    @Override public String toString() { return "EmbeddingProperties[REDACTED]"; }
}
