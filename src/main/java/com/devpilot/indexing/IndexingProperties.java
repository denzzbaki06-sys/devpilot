package com.devpilot.indexing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("devpilot.indexing")
public record IndexingProperties(@DefaultValue("500000") int maxFileSizeBytes,
        @DefaultValue("100") int chunkLines, @DefaultValue("15") int chunkOverlapLines,
        @DefaultValue("20000") int maxFiles, @DefaultValue("50000000") long maxTotalBytes,
        @DefaultValue("20000") int maxLineLength, @DefaultValue("100000") int maxChunks,
        @DefaultValue("1800") int maxJobSeconds) {
    public IndexingProperties {
        if (maxFileSizeBytes < 1 || maxFileSizeBytes > 10000000 || chunkLines < 1 || chunkLines > 1000
                || chunkOverlapLines < 0 || chunkOverlapLines >= chunkLines || chunkOverlapLines > chunkLines / 2 || maxFiles < 1
                || maxFiles > 100000 || maxTotalBytes < 1 || maxLineLength < 1 || maxChunks < 1 || maxChunks > 200000 || maxJobSeconds < 1) {
            throw new IllegalArgumentException("Invalid indexing limits");
        }
    }
}
