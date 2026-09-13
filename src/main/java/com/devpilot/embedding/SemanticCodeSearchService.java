package com.devpilot.embedding;
import com.devpilot.exception.ApiException;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SemanticCodeSearchService {
    public record Response(Long repositoryId, String query, List<SemanticSearchStore.Result> results) {
        @Override public String toString() { return "SemanticSearchResponse[REDACTED]"; }
    }
    private final SemanticSearchStore store;
    private final EmbeddingGateway gateway;
    public SemanticCodeSearchService(SemanticSearchStore store, EmbeddingGateway gateway) { this.store = store; this.gateway = gateway; }
    public Response search(Long userId, Long repositoryId, String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 2000 || limit < 1 || limit > 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid semantic search request");
        }
        return retrieve(userId, repositoryId, query, limit);
    }
    /** Shared retrieval for validated RAG questions; public search keeps its existing 2000-character contract. */
    public Response retrieve(Long userId, Long repositoryId, String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 4000 || limit < 1 || limit > 20)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid retrieval request");
        try {
            store.check(userId, repositoryId, gateway.provider());
            gateway.requireConfigured();
            var vector = gateway.embed(List.of(query.strip()), () -> {}).getFirst();
            return new Response(repositoryId, query.strip(), store.search(userId, repositoryId, gateway.provider(), vector, limit));
        } catch (DataAccessException ex) { throw new ApiException(HttpStatus.BAD_GATEWAY, "Vector search unavailable; retry later"); }
    }
}
