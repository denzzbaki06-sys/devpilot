package com.devpilot.embedding;
import java.util.List;

public interface EmbeddingProvider {
    String model();
    int dimensions();
    String providerId();
    void requireConfigured();
    List<float[]> embedBatch(List<String> texts);
    default float[] embed(String text) { return embedBatch(List.of(text)).getFirst(); }
}
