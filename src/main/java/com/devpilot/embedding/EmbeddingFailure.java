package com.devpilot.embedding;
import com.devpilot.exception.ApiException;
import org.springframework.http.HttpStatus;

public class EmbeddingFailure extends ApiException {
    public enum Reason {
        CONFIG("Embedding provider is not configured", 503),
        MISSING_KEY("Embedding API key is not configured", 503),
        DIMENSIONS("Embedding dimensions do not match database vector(1536)", 503),
        UNAUTHORIZED("Embedding provider authentication failed", 502),
        RATE_LIMIT("Embedding provider rate limited; retry later", 429),
        UNAVAILABLE("Embedding provider unavailable; retry later", 502),
        INVALID_INPUT("Embedding provider rejected the input", 502),
        MALFORMED("Embedding provider returned an invalid response", 502),
        INTERRUPTED("Embedding operation interrupted; retry indexing", 503);
        final String message; final int status;
        Reason(String message, int status) { this.message = message; this.status = status; }
    }
    public EmbeddingFailure(Reason reason) { super(HttpStatus.valueOf(reason.status), reason.message); }
}
