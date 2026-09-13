package com.devpilot.embedding;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingStore {
    public record Staged(ChunkEmbeddingInput input, String inputHash, String vector) {
        @Override public String toString() { return "StagedEmbedding[REDACTED]"; }
    }
    private final JdbcTemplate jdbc;
    public EmbeddingStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<ChunkEmbeddingInput> batch(Long jobId, String afterPath, int afterChunk, int size) {
        return jdbc.query("""
                SELECT s.path, s.language, c->>'content' AS content, (c->>'chunkIndex')::integer AS chunk_index,
                       (c->>'startLine')::integer AS start_line, (c->>'endLine')::integer AS end_line,
                       c->>'symbolName' AS symbol_name, c->>'symbolType' AS symbol_type, c->>'contentHash' AS content_hash
                FROM indexing_staged_files s CROSS JOIN LATERAL jsonb_array_elements(s.chunks) AS c
                WHERE s.job_id = ? AND (s.path, (c->>'chunkIndex')::integer) > (?, ?)
                ORDER BY s.path, (c->>'chunkIndex')::integer LIMIT ?
                """, (rs, row) -> new ChunkEmbeddingInput(rs.getString("path"), rs.getInt("chunk_index"), rs.getString("language"),
                        rs.getString("content"), rs.getInt("start_line"), rs.getInt("end_line"), rs.getString("symbol_name"),
                        rs.getString("symbol_type"), rs.getString("content_hash")), jobId, afterPath, afterChunk, size);
    }
    public Map<String, String> reusable(Long repositoryId, EmbeddingProvider provider, List<String> hashes) {
        if (hashes.isEmpty()) return Map.of();
        var result = new HashMap<String, String>();
        new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT input_hash, embedding::text AS vector FROM chunk_embeddings
                WHERE repository_id = :repo AND provider = :provider AND model = :model AND dimensions = :dimensions
                    AND input_hash IN (:hashes)
                """, Map.of("repo", repositoryId, "provider", provider.providerId(), "model", provider.model(),
                        "dimensions", provider.dimensions(), "hashes", hashes), (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.put(rs.getString("input_hash"), rs.getString("vector")));
        return result;
    }
    @Transactional
    public void stage(Long jobId, EmbeddingProvider provider, List<Staged> rows) {
        if (jdbc.queryForList("SELECT id FROM indexing_jobs WHERE id = ? AND status = 'RUNNING' FOR UPDATE", jobId).isEmpty()) {
            throw new EmbeddingFailure(EmbeddingFailure.Reason.INTERRUPTED);
        }
        jdbc.batchUpdate("""
                INSERT INTO indexing_staged_embeddings(job_id, path, chunk_index, embedding, provider, model, dimensions, content_hash, input_hash)
                VALUES (?, ?, ?, ?::vector, ?, ?, ?, ?, ?)
                """, rows, rows.size(), (ps, row) -> {
                    ps.setLong(1, jobId); ps.setString(2, row.input().path()); ps.setInt(3, row.input().chunkIndex());
                    ps.setString(4, row.vector()); ps.setString(5, provider.providerId()); ps.setString(6, provider.model());
                    ps.setInt(7, provider.dimensions()); ps.setString(8, row.input().contentHash()); ps.setString(9, row.inputHash());
                });
    }
    public void verifyStaged(Long jobId, int expected) {
        if (jdbc.queryForObject("SELECT count(*) FROM indexing_staged_embeddings WHERE job_id = ?", Integer.class, jobId) != expected) {
            throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED);
        }
    }
    public void publish(Long jobId, Long repositoryId, int expected) {
        int inserted = jdbc.update("""
                INSERT INTO chunk_embeddings(code_chunk_id, repository_id, embedding, provider, model, dimensions, content_hash, input_hash)
                SELECT c.id, c.repository_id, e.embedding, e.provider, e.model, e.dimensions, e.content_hash, e.input_hash
                FROM indexing_staged_embeddings e JOIN repository_files f ON f.repository_id = ? AND f.path = e.path
                JOIN code_chunks c ON c.repository_file_id = f.id AND c.chunk_index = e.chunk_index AND c.content_hash = e.content_hash
                WHERE e.job_id = ?
                """, repositoryId, jobId);
        if (inserted != expected) throw new EmbeddingFailure(EmbeddingFailure.Reason.MALFORMED);
    }
}
