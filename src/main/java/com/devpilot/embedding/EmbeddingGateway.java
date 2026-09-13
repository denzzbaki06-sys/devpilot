package com.devpilot.embedding;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingGateway {
    public static final String FORMAT = "context-v1/utf8-segments-weighted-v1";
    private final EmbeddingProvider provider;
    private final EmbeddingProperties properties;
    private final JdbcTemplate jdbc;
    public EmbeddingGateway(EmbeddingProvider provider, EmbeddingProperties properties, JdbcTemplate jdbc) {
        this.provider = provider; this.properties = properties; this.jdbc = jdbc;
    }
    public EmbeddingProvider provider() { return provider; }
    public int batchSize() { return properties.batchSize(); }
    public void requireConfigured() {
        properties.validate(); provider.requireConfigured();
        if (provider.dimensions() != 1536) throw new EmbeddingFailure(EmbeddingFailure.Reason.DIMENSIONS);
        if (provider.model() == null || provider.model().isBlank() || provider.model().length() > 255
                || provider.providerId() == null || provider.providerId().length() > 512) throw new EmbeddingFailure(EmbeddingFailure.Reason.CONFIG);
        String type = jdbc.queryForObject("SELECT format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = 'chunk_embeddings'::regclass AND attname = 'embedding'", String.class);
        if (!"vector(1536)".equals(type)) throw new EmbeddingFailure(EmbeddingFailure.Reason.DIMENSIONS);
    }
    private record Part(int input, String text, int bytes) {
        @Override public String toString() { return "EmbeddingPart[REDACTED]"; }
    }
    public List<float[]> embed(List<String> texts, Runnable heartbeat) {
        if (texts.isEmpty()) return List.of();
        double[][] sums = new double[texts.size()][provider.dimensions()];
        var pending = new ArrayList<Part>(); int batchBytes = 0;
        // UTF-8 byte bounds conservatively bound token counts without adding a tokenizer dependency.
        // Large chunks retain all nonblank content through weighted pooling, rather than truncation.
        for (int input = 0; input < texts.size(); input++) {
            String text = texts.get(input);
            if (text == null || text.isBlank()) throw new EmbeddingFailure(EmbeddingFailure.Reason.INVALID_INPUT);
            for (int start = 0; start < text.length();) {
                int end = start, bytes = 0;
                while (end < text.length()) {
                    int point = text.codePointAt(end);
                    int size = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
                    if (bytes + size > 6000) break;
                    bytes += size; end += Character.charCount(point);
                }
                String part = text.substring(start, end); start = end;
                if (part.isBlank()) continue;
                if (!pending.isEmpty() && (pending.size() >= batchSize() || batchBytes + bytes > 240000)) {
                    flush(pending, sums, heartbeat); pending.clear(); batchBytes = 0;
                }
                pending.add(new Part(input, part, bytes)); batchBytes += bytes;
            }
        }
        if (!pending.isEmpty()) flush(pending, sums, heartbeat);
        var result = new ArrayList<float[]>();
        for (double[] sum : sums) {
            double norm = 0; for (double value : sum) norm += value * value;
            if (!Double.isFinite(norm) || norm == 0) throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED);
            norm = Math.sqrt(norm); var vector = new float[sum.length];
            for (int i = 0; i < sum.length; i++) vector[i] = (float) (sum[i] / norm);
            VectorValues.validate(vector, provider.dimensions()); result.add(vector);
        }
        return result;
    }
    private void flush(List<Part> parts, double[][] sums, Runnable heartbeat) {
        heartbeat.run();
        var vectors = provider.embedBatch(parts.stream().map(Part::text).toList());
        if (vectors == null || vectors.size() != parts.size()) throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED);
        for (int i = 0; i < vectors.size(); i++) {
            float[] vector = vectors.get(i); VectorValues.validate(vector, provider.dimensions());
            var part = parts.get(i);
            for (int j = 0; j < vector.length; j++) sums[part.input()][j] += (double) vector[j] * part.bytes();
        }
        heartbeat.run();
    }
}
