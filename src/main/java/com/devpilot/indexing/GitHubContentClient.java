package com.devpilot.indexing;
import java.util.List;

public interface GitHubContentClient {
    record Entry(String path, String sha, long size, String mode) {}
    record Snapshot(String commitSha, String owner, String name, List<Entry> files) {}
    Snapshot snapshot(String token, String owner, String name, long expectedRepositoryId);
    byte[] blob(String token, String owner, String name, String sha, int maxBytes);
}
