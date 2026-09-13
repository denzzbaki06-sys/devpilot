package com.devpilot.embedding;

public final class VectorValues {
    private VectorValues() {}
    public static void validate(float[] vector, int dimensions) {
        if (vector == null || vector.length != dimensions) throw new EmbeddingFailure(EmbeddingFailure.Reason.DIMENSIONS);
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED);
            norm += (double) value * value;
        }
        if (norm == 0) throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED);
    }
    public static String sql(float[] vector) {
        validate(vector, 1536);
        var result = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) { if (i > 0) result.append(','); result.append(Float.toString(vector[i])); }
        return result.append(']').toString();
    }
}
