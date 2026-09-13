package com.devpilot.support;
import com.devpilot.embedding.EmbeddingProvider;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

// Explicitly imported test source only. Never available to a production application build.
@TestConfiguration(proxyBeanMethods = false)
public class TestEmbeddings {
    @Bean @Primary public DeterministicProvider deterministicEmbeddings() { return new DeterministicProvider(); }
    public static class DeterministicProvider implements EmbeddingProvider {
        public String model() { return "text-embedding-3-small"; }
        public int dimensions() { return 1536; }
        public String providerId() { return "test-only-deterministic"; }
        public void requireConfigured() {}
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(text -> {
                var vector = new float[1536]; String lower = text.toLowerCase(Locale.ROOT);
                vector[lower.contains("jwt") || lower.contains("authentication") ? 0 : lower.contains("sql") || lower.contains("database") ? 1 : 2] = 1;
                return vector;
            }).toList();
        }
    }
}
