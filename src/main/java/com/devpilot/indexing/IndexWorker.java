package com.devpilot.indexing;

import com.devpilot.exception.ApiException;
import com.devpilot.embedding.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class IndexWorker {
    private final IndexJobStore store;
    private final GitHubIndexAccess access;
    private final SourceFilePolicy policy;
    private final CodeChunker chunker;
    private final IndexingProperties properties;
    private final ChunkEmbeddingStage embeddings;
    public IndexWorker(IndexJobStore store, GitHubIndexAccess access, SourceFilePolicy policy, CodeChunker chunker, IndexingProperties properties, ChunkEmbeddingStage embeddings) {
        this.store = store; this.access = access; this.policy = policy; this.chunker = chunker; this.properties = properties; this.embeddings = embeddings;
    }
    public void run(IndexJobStore.Ticket ticket) {
        var stats = new IndexingStats();
        try {
            if (!store.running(ticket)) return;
            long deadline = System.nanoTime() + properties.maxJobSeconds() * 1_000_000_000L;
            var snapshot = access.snapshot(ticket); stats.sourceCommit = snapshot.commitSha();
            stats.filesDiscovered = snapshot.files().size();
            if (stats.filesDiscovered > properties.maxFiles()) throw limit();
            long totalBytes = 0;
            for (var entry : snapshot.files()) {
                if (System.nanoTime() > deadline) throw new ApiException(HttpStatus.REQUEST_TIMEOUT, "Indexing time limit exceeded; retry with a smaller repository");
                if (!store.heartbeat(ticket.jobId())) return;
                String reason = policy.skipReason(entry.path(), entry.mode(), entry.size());
                if (reason != null) { stats.skip(reason); continue; }
                if (totalBytes + entry.size() > properties.maxTotalBytes()) throw limit();
                byte[] bytes;
                try { bytes = access.blob(ticket, snapshot, entry.sha(), properties.maxFileSizeBytes()); }
                catch (RestGitHubContentClient.OversizedBlobException ex) { stats.skip("OVERSIZED"); continue; }
                totalBytes += bytes.length;
                if (totalBytes > properties.maxTotalBytes()) throw limit();
                var text = policy.normalize(bytes);
                if (text.skipReason() != null) { stats.skip(text.skipReason()); continue; }
                if (entry.size() != bytes.length) throw new ApiException(HttpStatus.BAD_GATEWAY, "GitHub file size changed unexpectedly");
                String language = policy.language(entry.path());
                var chunks = chunker.chunk(text.content(), language);
                if (stats.chunksCreated + chunks.size() > properties.maxChunks()) throw limit();
                store.stage(ticket, entry, language, text, chunks);
                stats.indexed(language, text.lineCount(), chunks.size());
            }
            if (System.nanoTime() > deadline) throw new ApiException(HttpStatus.REQUEST_TIMEOUT, "Indexing time limit exceeded");
            embeddings.generate(ticket, stats, deadline);
            store.publish(ticket, stats);
        } catch (Exception ex) {
            try { store.fail(ticket, stats, safeError(ex)); }
            catch (DataAccessException unavailable) { /* The lease reaper finalizes this job when the DB recovers. */ }
        }
    }
    private ApiException limit() { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Repository exceeds configured indexing limits"); }
    private String safeError(Exception error) {
        if (error instanceof EmbeddingFailure embedding) return embedding.getMessage();
        if (error instanceof ApiException api) {
            return switch (api.getStatus()) {
                case UNAUTHORIZED -> "GitHub authorization revoked; reconnect GitHub";
                case FORBIDDEN -> "GitHub access forbidden or rate limited";
                case NOT_FOUND -> "GitHub repository or content no longer accessible";
                case TOO_MANY_REQUESTS -> "GitHub rate limit reached; retry indexing later";
                case CONFLICT -> "GitHub connection unavailable; reconnect GitHub";
                case SERVICE_UNAVAILABLE -> "GitHub token configuration unavailable";
                case PAYLOAD_TOO_LARGE -> "Repository exceeds configured indexing limits";
                case REQUEST_TIMEOUT -> "Indexing time limit exceeded";
                default -> "GitHub content unavailable, invalid, or too large; retry indexing";
            };
        }
        return error instanceof DataAccessException ? "Index storage failed; previous snapshot preserved" : "Indexing failed; previous snapshot preserved";
    }
}
