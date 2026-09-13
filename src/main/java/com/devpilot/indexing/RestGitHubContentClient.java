package com.devpilot.indexing;

import com.devpilot.exception.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import tools.jackson.databind.*;

@Component
public class RestGitHubContentClient implements GitHubContentClient {
    private final RestClient client;
    private final ObjectMapper json;
    private final IndexingProperties properties;
    @Autowired public RestGitHubContentClient(ObjectMapper json, IndexingProperties properties) {
        this(defaultClient(), json, properties);
    }
    public RestGitHubContentClient(RestClient client, ObjectMapper json, IndexingProperties properties) {
        this.client = client; this.json = json; this.properties = properties;
    }
    private static RestClient defaultClient() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http); factory.setReadTimeout(Duration.ofSeconds(20));
        return RestClient.builder().baseUrl("https://api.github.com").requestFactory(factory).defaultHeader("User-Agent", "DevPilot").build();
    }
    @Override public Snapshot snapshot(String token, String owner, String name, long expectedId) {
        var metadata = treeJson(get(token, "/repos/{owner}/{repo}", 1000000, false, owner, name));
        if (metadata.path("id").asLong() != expectedId) throw failure("GitHub repository identity changed; reconnect repository");
        String branch = metadata.path("default_branch").asText("");
        if (branch.isBlank()) {
            if (metadata.path("size").asLong(-1) == 0) return new Snapshot(null, owner, name, List.of());
            throw failure("Repository has no default branch");
        }
        byte[] commitBytes = get(token, "/repos/{owner}/{repo}/commits/{branch}", 2000000, true, owner, name, branch);
        if (commitBytes.length == 0) return new Snapshot(null, owner, name, List.of());
        var commit = treeJson(commitBytes);
        String commitSha = sha(commit.path("sha").asText());
        String treeSha = sha(commit.path("commit").path("tree").path("sha").asText());
        var tree = treeJson(get(token, "/repos/{owner}/{repo}/git/trees/{sha}?recursive=1", 8000000, false, owner, name, treeSha));
        if (!tree.path("truncated").isBoolean() || tree.get("truncated").asBoolean()) throw failure("Repository tree is too large; partial indexing refused");
        if (!tree.path("tree").isArray()) throw failure("GitHub returned an invalid tree");
        if (tree.get("tree").size() > properties.maxFiles()) throw failure("Repository exceeds configured file count limit");
        var entries = new ArrayList<Entry>(); var paths = new HashSet<String>();
        for (JsonNode entry : tree.get("tree")) {
            String type = entry.path("type").asText();
            if ("tree".equals(type)) continue;
            String path = entry.path("path").asText();
            if (!paths.add(path)) throw failure("GitHub returned duplicate file paths");
            entries.add(new Entry(path, sha(entry.path("sha").asText()), entry.path("size").asLong(-1), entry.path("mode").asText()));
        }
        return new Snapshot(commitSha, owner, name, List.copyOf(entries));
    }
    @Override public byte[] blob(String token, String owner, String name, String sha, int maxBytes) {
        return get(token, "/repos/{owner}/{repo}/git/blobs/{sha}", maxBytes, false, owner, name, sha(sha));
    }
    private byte[] get(String token, String path, int limit, boolean emptyAllowed, Object... variables) {
        try {
            return client.get().uri(path, variables).header("Authorization", "Bearer " + token)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Accept", path.contains("/git/blobs/") ? "application/vnd.github.raw+json" : "application/vnd.github+json")
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (emptyAllowed && status == 409) return new byte[0];
                        if (status == 401) throw new ApiException(HttpStatus.UNAUTHORIZED, "GitHub authorization revoked; reconnect GitHub");
                        if (status == 429 || (status == 403 && ("0".equals(response.getHeaders().getFirst("X-RateLimit-Remaining"))
                                || response.getHeaders().containsHeader("Retry-After")))) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "GitHub rate limit reached; retry indexing later");
                        if (status == 403) throw new ApiException(HttpStatus.FORBIDDEN, "GitHub access forbidden or rate limited");
                        if (status == 404) throw new ApiException(HttpStatus.NOT_FOUND, "GitHub repository or content no longer accessible");
                        if (status < 200 || status >= 300) throw failure("GitHub content request failed");
                        // Bound the stream itself, even when Content-Length or tree size is incorrect.
                        byte[] bytes = response.getBody().readNBytes(limit + 1);
                        if (bytes.length > limit) {
                            if (path.contains("/git/blobs/")) throw new OversizedBlobException();
                            throw failure("GitHub response exceeds indexing limits");
                        }
                        return bytes;
                    });
        } catch (RestClientException ex) { throw failure("GitHub content request timed out or is unavailable"); }
    }
    public static class OversizedBlobException extends RuntimeException {}
    private JsonNode treeJson(byte[] bytes) {
        try { var result = json.readTree(bytes); if (result == null) throw failure("Invalid GitHub metadata"); return result; }
        catch (tools.jackson.core.JacksonException ex) { throw failure("Invalid GitHub metadata"); }
    }
    private String sha(String value) {
        if (value == null || !value.matches("[a-fA-F0-9]{40,64}")) throw failure("Invalid GitHub content reference");
        return value;
    }
    private ApiException failure(String message) { return new ApiException(HttpStatus.BAD_GATEWAY, message); }
}
