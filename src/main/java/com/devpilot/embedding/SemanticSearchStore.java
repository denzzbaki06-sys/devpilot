package com.devpilot.embedding;

import com.devpilot.exception.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SemanticSearchStore {
    public record Result(Long chunkId, String path, String language, String symbolName, String symbolType,
                         int startLine, int endLine, String content, double similarity) {
        @Override public String toString() { return "SemanticResult[REDACTED]"; }
    }
    private final JdbcTemplate jdbc;
    public SemanticSearchStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public int check(Long userId, Long repositoryId, EmbeddingProvider provider) {
        return available(userId, repositoryId, provider);
    }
    private int available(Long userId, Long repositoryId, EmbeddingProvider provider) {
        var rows = jdbc.queryForList("SELECT status FROM repositories WHERE id = ? AND user_id = ?", repositoryId, userId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Connected repository not found");
        String status = (String) rows.getFirst().get("status");
        if (!Set.of("READY", "INDEXING", "FAILED").contains(status)) throw new ApiException(HttpStatus.CONFLICT, "Repository has no searchable index; run indexing first");
        if (provider.dimensions() != 1536) throw new EmbeddingFailure(EmbeddingFailure.Reason.DIMENSIONS);
        int chunks = jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE repository_id = ?", Integer.class, repositoryId);
        int vectors = jdbc.queryForObject("""
                SELECT count(*) FROM chunk_embeddings e JOIN code_chunks c ON c.id = e.code_chunk_id AND c.repository_id = e.repository_id
                WHERE e.repository_id = ? AND e.model = ? AND e.provider = ? AND e.dimensions = ? AND e.content_hash = c.content_hash
                """, Integer.class, repositoryId, provider.model(), provider.providerId(), provider.dimensions());
        // INDEXING/FAILED may still expose the last complete, atomically published snapshot.
        if (chunks == 0 || vectors != chunks) throw new ApiException(HttpStatus.CONFLICT, "Repository embeddings are missing or use another model; reindex repository");
        return chunks;
    }
    private static final String CANDIDATES = """
            SELECT c.id AS chunk_id, f.path, f.language, c.symbol_name, c.symbol_type,
                   c.start_line, c.end_line, c.content, e.embedding
            FROM chunk_embeddings e JOIN code_chunks c ON c.id = e.code_chunk_id AND c.repository_id = e.repository_id
            JOIN repository_files f ON f.id = c.repository_file_id AND f.repository_id = e.repository_id
            WHERE e.repository_id = ? AND e.provider = ? AND e.model = ? AND e.dimensions = 1536 AND e.content_hash = c.content_hash
            """;
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Result> search(Long userId, Long repositoryId, EmbeddingProvider provider, float[] vector, int limit) {
        int chunks = available(userId, repositoryId, provider);
        String literal = VectorValues.sql(vector);
        jdbc.execute("SET LOCAL hnsw.iterative_scan = 'strict_order'");
        String ranking = " SELECT chunk_id, path, language, symbol_name, symbol_type, start_line, end_line, content, "
                + "GREATEST(0.0, LEAST(1.0, 1.0 - (embedding <=> ?::vector))) AS similarity "
                + "FROM candidates ORDER BY embedding <=> ?::vector LIMIT ?";
        List<Result> result = query("WITH candidates AS (" + CANDIDATES + ")" + ranking,
                repositoryId, provider.providerId(), provider.model(), literal, literal, limit);
        if (result.size() < Math.min(limit, chunks)) {
            // Filtered ANN can exhaust its candidate budget. Exact database fallback avoids undersized top-K.
            result = query("WITH candidates AS MATERIALIZED (" + CANDIDATES + ")" + ranking,
                    repositoryId, provider.providerId(), provider.model(), literal, literal, limit);
        }
        return result;
    }
    private List<Result> query(String sql, Object... parameters) {
        return jdbc.query(sql, (rs, row) -> new Result(rs.getLong("chunk_id"), rs.getString("path"), rs.getString("language"),
                rs.getString("symbol_name"), rs.getString("symbol_type"), rs.getInt("start_line"), rs.getInt("end_line"),
                rs.getString("content"), rs.getDouble("similarity")), parameters);
    }
}
