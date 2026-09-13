package com.devpilot.embedding;

import com.devpilot.indexing.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ChunkEmbeddingStage {
    private final EmbeddingGateway gateway;
    private final EmbeddingStore embeddings;
    private final IndexJobStore jobs;
    public ChunkEmbeddingStage(EmbeddingGateway gateway, EmbeddingStore embeddings, IndexJobStore jobs) {
        this.gateway = gateway; this.embeddings = embeddings; this.jobs = jobs;
    }
    public void generate(IndexJobStore.Ticket ticket, IndexingStats stats, long deadline) {
        gateway.requireConfigured();
        stats.embeddingModel = gateway.provider().model();
        String repository = ticket.owner() + "/" + ticket.name(), path = ""; int index = -1;
        Runnable heartbeat = () -> {
            if (System.nanoTime() > deadline || !jobs.heartbeat(ticket.jobId())) throw new EmbeddingFailure(EmbeddingFailure.Reason.INTERRUPTED);
        };
        while (true) {
            heartbeat.run();
            var inputs = embeddings.batch(ticket.jobId(), path, index, gateway.batchSize());
            if (inputs.isEmpty()) break;
            var hashes = inputs.stream().map(input -> input.inputHash(repository)).toList();
            var cache = embeddings.reusable(ticket.repositoryId(), gateway.provider(), hashes);
            var missing = inputs.stream().filter(input -> !cache.containsKey(input.inputHash(repository))).toList();
            var generated = gateway.embed(missing.stream().map(input -> input.contextual(repository)).toList(), heartbeat);
            var rows = new ArrayList<EmbeddingStore.Staged>(); int generatedIndex = 0;
            for (var input : inputs) {
                String hash = input.inputHash(repository), vector = cache.get(hash);
                if (vector == null) vector = VectorValues.sql(generated.get(generatedIndex++));
                else stats.embeddingsReused++;
                rows.add(new EmbeddingStore.Staged(input, hash, vector));
            }
            embeddings.stage(ticket.jobId(), gateway.provider(), rows);
            stats.embeddingsCreated += rows.size();
            var last = inputs.getLast(); path = last.path(); index = last.chunkIndex();
        }
    }
}
