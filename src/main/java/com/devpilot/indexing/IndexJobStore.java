package com.devpilot.indexing;

import com.devpilot.exception.ApiException;
import com.devpilot.embedding.EmbeddingStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
public class IndexJobStore {
    public record Ticket(Long jobId, Long repositoryId, Long userId, String owner, String name, Long githubRepositoryId) {}
    public record Summary(int filesDiscovered, int filesIndexed, int filesSkipped, int chunksCreated, long lineCount,
                          Map<String, Integer> languages, Map<String, Integer> skipReasons, int embeddingsCreated, int embeddingsReused, String embeddingModel) {}
    public record JobView(Long id, String status, Instant startedAt, Instant completedAt, String sourceCommit, Summary summary) {}
    public record IndexStatus(Long repositoryId, String status, int filesIndexed, int chunksCreated, long lineCount,
            Map<String, Integer> languages, Instant lastIndexedAt, String lastError, JobView job, int embeddingsCreated, String embeddingModel) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final EmbeddingStore embeddings;
    public IndexJobStore(JdbcTemplate jdbc, ObjectMapper json, EmbeddingStore embeddings) { this.jdbc = jdbc; this.json = json; this.embeddings = embeddings; }
    @Transactional
    public Ticket queue(Long userId, Long repositoryId) {
        var rows = jdbc.queryForList("SELECT * FROM repositories WHERE id = ? AND user_id = ? FOR UPDATE", repositoryId, userId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Connected repository not found");
        var repo = rows.getFirst();
        if ("INDEXING".equals(repo.get("status"))) throw new ApiException(HttpStatus.CONFLICT, "Repository indexing is already running");
        if (jdbc.queryForObject("SELECT count(*) FROM github_connections WHERE user_id = ?", Integer.class, userId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Connect GitHub first");
        }
        Long id = jdbc.queryForObject("INSERT INTO indexing_jobs(repository_id, status) VALUES (?, 'QUEUED') RETURNING id", Long.class, repositoryId);
        jdbc.update("UPDATE repositories SET status = 'INDEXING' WHERE id = ?", repositoryId);
        return new Ticket(id, repositoryId, userId, (String) repo.get("owner"), (String) repo.get("name"), ((Number) repo.get("github_repository_id")).longValue());
    }
    @Transactional
    public boolean running(Ticket ticket) {
        return jdbc.update("UPDATE indexing_jobs SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP, heartbeat_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'QUEUED'", ticket.jobId()) == 1;
    }
    public boolean heartbeat(Long jobId) {
        return jdbc.update("UPDATE indexing_jobs SET heartbeat_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'RUNNING'", jobId) == 1;
    }
    @Transactional
    public void stage(Ticket ticket, GitHubContentClient.Entry entry, String language, SourceFilePolicy.Text text, List<CodeChunker.Chunk> chunks) {
        active(ticket.jobId());
        String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
        jdbc.update("INSERT INTO indexing_staged_files(job_id, path, file_name, language, github_sha, size_bytes, content_hash, line_count, chunks) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                ticket.jobId(), entry.path(), name, language, entry.sha(), entry.size(), ContentHashes.sha256(text.content()), text.lineCount(), json.writeValueAsString(chunks));
        heartbeat(ticket.jobId());
    }
    @Transactional
    public void publish(Ticket ticket, IndexingStats stats) {
        lockRepository(ticket.repositoryId()); active(ticket.jobId());
        embeddings.verifyStaged(ticket.jobId(), stats.chunksCreated);
        // All replacement operations share one transaction. Any write failure restores the old live index.
        jdbc.update("DELETE FROM repository_files WHERE repository_id = ?", ticket.repositoryId());
        jdbc.update("""
                INSERT INTO repository_files(repository_id, path, file_name, language, github_sha, size_bytes, content_hash, line_count)
                SELECT ?, path, file_name, language, github_sha, size_bytes, content_hash, line_count
                FROM indexing_staged_files WHERE job_id = ?
                """, ticket.repositoryId(), ticket.jobId());
        jdbc.update("""
                INSERT INTO code_chunks(repository_file_id, repository_id, chunk_index, content, start_line, end_line, symbol_name, symbol_type, content_hash)
                SELECT f.id, f.repository_id, (c->>'chunkIndex')::integer, c->>'content',
                       (c->>'startLine')::integer, (c->>'endLine')::integer, c->>'symbolName', c->>'symbolType', c->>'contentHash'
                FROM indexing_staged_files s JOIN repository_files f ON f.repository_id = ? AND f.path = s.path
                CROSS JOIN LATERAL jsonb_array_elements(s.chunks) AS c WHERE s.job_id = ?
                """, ticket.repositoryId(), ticket.jobId());
        embeddings.publish(ticket.jobId(), ticket.repositoryId(), stats.chunksCreated);
        saveStats(ticket.jobId(), stats);
        jdbc.update("UPDATE indexing_jobs SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP, heartbeat_at = CURRENT_TIMESTAMP WHERE id = ?", ticket.jobId());
        jdbc.update("UPDATE repositories SET status = 'READY' WHERE id = ?", ticket.repositoryId());
        jdbc.update("DELETE FROM indexing_staged_files WHERE job_id = ?", ticket.jobId());
    }
    @Transactional
    public void fail(Ticket ticket, IndexingStats stats, String safeMessage) {
        if (jdbc.queryForList("SELECT id FROM repositories WHERE id = ? FOR UPDATE", ticket.repositoryId()).isEmpty()) return;
        int changed = jdbc.update("UPDATE indexing_jobs SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP, error_message = ? WHERE id = ? AND status IN ('QUEUED', 'RUNNING')", safeMessage, ticket.jobId());
        if (changed == 0) return; // A stale worker cannot overwrite a newer job or completed snapshot.
        saveStats(ticket.jobId(), stats);
        jdbc.update("UPDATE repositories SET status = 'FAILED' WHERE id = ?", ticket.repositoryId());
        jdbc.update("DELETE FROM indexing_staged_files WHERE job_id = ?", ticket.jobId());
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IndexStatus status(Long userId, Long repositoryId) {
        var rows = jdbc.queryForList("SELECT status FROM repositories WHERE id = ? AND user_id = ?", repositoryId, userId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Connected repository not found");
        var latest = jdbc.queryForList("SELECT * FROM indexing_jobs WHERE repository_id = ? ORDER BY id DESC LIMIT 1", repositoryId);
        var published = jdbc.queryForList("SELECT * FROM indexing_jobs WHERE repository_id = ? AND status = 'COMPLETED' ORDER BY id DESC LIMIT 1", repositoryId);
        var snapshot = published.isEmpty() ? Map.<String, Object>of() : published.getFirst();
        var job = latest.isEmpty() ? null : latest.getFirst();
        return new IndexStatus(repositoryId, (String) rows.getFirst().get("status"), number(snapshot, "files_indexed"),
                number(snapshot, "chunks_created"), longNumber(snapshot, "line_count"), mapping(snapshot.get("languages")),
                instant(snapshot.get("completed_at")), job == null ? null : (String) job.get("error_message"), job == null ? null : view(job), number(snapshot, "embeddings_created"), (String) snapshot.get("embedding_model"));
    }
    public List<Ticket> abandoned() {
        return jdbc.query("""
                SELECT j.id AS job_id, r.* FROM indexing_jobs j JOIN repositories r ON r.id = j.repository_id
                WHERE j.status IN ('QUEUED', 'RUNNING') AND j.heartbeat_at < CURRENT_TIMESTAMP - INTERVAL '2 minutes'
                """, (rs, row) -> new Ticket(rs.getLong("job_id"), rs.getLong("id"), rs.getLong("user_id"), rs.getString("owner"), rs.getString("name"), rs.getLong("github_repository_id")));
    }
    @Transactional
    public void expire(Ticket ticket) {
        lockRepository(ticket.repositoryId());
        var rows = jdbc.queryForList("SELECT id FROM indexing_jobs WHERE id = ? AND status IN ('QUEUED', 'RUNNING') AND heartbeat_at < CURRENT_TIMESTAMP - INTERVAL '2 minutes' FOR UPDATE", ticket.jobId());
        if (rows.isEmpty()) return;
        jdbc.update("UPDATE indexing_jobs SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP, error_message = 'Indexing worker interrupted; retry indexing' WHERE id = ?", ticket.jobId());
        jdbc.update("UPDATE repositories SET status = 'FAILED' WHERE id = ?", ticket.repositoryId());
        jdbc.update("DELETE FROM indexing_staged_files WHERE job_id = ?", ticket.jobId());
    }
    private void lockRepository(Long id) {
        if (jdbc.queryForList("SELECT id FROM repositories WHERE id = ? FOR UPDATE", id).isEmpty()) throw new IllegalStateException("Indexing repository removed");
    }
    private void active(Long id) {
        if (jdbc.queryForList("SELECT id FROM indexing_jobs WHERE id = ? AND status = 'RUNNING' FOR UPDATE", id).isEmpty()) throw new IllegalStateException("Indexing job no longer active");
    }
    private void saveStats(Long jobId, IndexingStats s) {
        jdbc.update("UPDATE indexing_jobs SET source_commit = ?, files_discovered = ?, files_indexed = ?, files_skipped = ?, chunks_created = ?, line_count = ?, languages = ?::jsonb, skip_reasons = ?::jsonb, embeddings_created = ?, embeddings_reused = ?, embedding_model = ? WHERE id = ?",
                s.sourceCommit, s.filesDiscovered, s.filesIndexed, s.filesSkipped, s.chunksCreated, s.lineCount,
                json.writeValueAsString(s.languages), json.writeValueAsString(s.skipReasons), s.embeddingsCreated, s.embeddingsReused, s.embeddingModel, jobId);
    }
    private JobView view(Map<String, Object> row) {
        return new JobView(((Number) row.get("id")).longValue(), (String) row.get("status"), instant(row.get("started_at")),
                instant(row.get("completed_at")), (String) row.get("source_commit"), new Summary(number(row, "files_discovered"),
                number(row, "files_indexed"), number(row, "files_skipped"), number(row, "chunks_created"), longNumber(row, "line_count"), mapping(row.get("languages")), mapping(row.get("skip_reasons")), number(row, "embeddings_created"), number(row, "embeddings_reused"), (String) row.get("embedding_model")));
    }
    private int number(Map<String, Object> row, String key) { return ((Number) row.getOrDefault(key, 0)).intValue(); }
    private long longNumber(Map<String, Object> row, String key) { return ((Number) row.getOrDefault(key, 0L)).longValue(); }
    private Instant instant(Object value) { return value == null ? null : ((Timestamp) value).toInstant(); }
    private Map<String, Integer> mapping(Object value) {
        if (value == null) return Map.of();
        var result = new TreeMap<String, Integer>();
        json.readTree(value.toString()).properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asInt()));
        return result;
    }
}
