package com.devpilot.indexing;

import com.devpilot.exception.ApiException;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class IndexingService {
    public record Accepted(Long repositoryId, Long jobId, String status) {}
    private final IndexJobStore store;
    private final IndexWorker worker;
    private final TaskExecutor executor;
    public IndexingService(IndexJobStore store, IndexWorker worker, @Qualifier("indexingExecutor") TaskExecutor executor) {
        this.store = store; this.worker = worker; this.executor = executor;
    }
    public Accepted start(Long userId, Long repositoryId) {
        var ticket = store.queue(userId, repositoryId); // Transaction commits before asynchronous dispatch.
        try { executor.execute(() -> worker.run(ticket)); }
        catch (RejectedExecutionException ex) {
            store.fail(ticket, new IndexingStats(), "Indexing capacity reached; retry later");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Indexing capacity reached; retry later");
        }
        return new Accepted(repositoryId, ticket.jobId(), "INDEXING");
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void recoverAbandonedJobs() {
        for (var ticket : store.abandoned()) store.expire(ticket);
    }
}
