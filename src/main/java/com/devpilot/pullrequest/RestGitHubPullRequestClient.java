package com.devpilot.pullrequest;

import com.devpilot.exception.ApiException;
import com.devpilot.github.GitHubErrors;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import tools.jackson.databind.*;

@Component
public class RestGitHubPullRequestClient implements GitHubPullRequestClient {
    private static final int MAX_RESPONSE_BYTES = 8_000_000;
    private final RestClient api;
    private final ObjectMapper json;
    @Autowired public RestGitHubPullRequestClient(ObjectMapper json) { this(client(), json); }
    public RestGitHubPullRequestClient(RestClient api, ObjectMapper json) { this.api = api; this.json = json; }
    private static RestClient client() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http); factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().baseUrl("https://api.github.com").requestFactory(factory).defaultHeader("User-Agent", "DevPilot").build();
    }
    private record Result(JsonNode data, boolean next) {}
    private Result get(String token, Repository repo, String suffix) {
        if (!repo.owner().matches("[A-Za-z0-9_-]+") || !repo.name().matches("[A-Za-z0-9_.-]+")) throw invalid();
        try {
            return api.get().uri("/repos/{owner}/{repo}" + suffix, repo.owner(), repo.name())
                    .header("Authorization", "Bearer " + token).header("X-GitHub-Api-Version", "2022-11-28")
                    .accept(MediaType.valueOf("application/vnd.github+json")).exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful())
                            throw GitHubErrors.failure(response.getStatusCode().value(), response.getHeaders(), false);
                        byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (body.length > MAX_RESPONSE_BYTES) throw tooLarge();
                        JsonNode data = json.readTree(body);
                        if (data == null) throw invalid();
                        // Only inspect relation; never follow an upstream URL with credentials.
                        boolean next = response.getHeaders().getOrEmpty("Link").stream().anyMatch(l -> l.contains("rel=\"next\""));
                        return new Result(data, next);
                    });
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException ex) { throw invalid(); }
        catch (RestClientException ex) { throw new ApiException(HttpStatus.BAD_GATEWAY, "GitHub is unavailable; retry later"); }
    }
    @Override public void verifyRepository(String token, Repository repo) {
        if (get(token, repo, "").data().path("id").asLong() != repo.githubId())
            throw new ApiException(HttpStatus.NOT_FOUND, "GitHub repository not found or inaccessible");
    }
    @Override public Page list(String token, Repository repo, int page, int size) {
        var result = get(token, repo, "/pulls?state=open&sort=updated&direction=desc&per_page=" + size + "&page=" + page);
        if (!result.data().isArray() || result.data().size() > size) throw invalid();
        var items = new ArrayList<PullRequest>();
        for (var item : result.data()) items.add(map(item, repo, false));
        return new Page(List.copyOf(items), result.next());
    }
    @Override public PullRequest detail(String token, Repository repo, int number) {
        var result = map(get(token, repo, "/pulls/" + number).data(), repo, true);
        if (result.number() != number) throw invalid();
        return result;
    }
    @Override public List<File> files(String token, Repository repo, int number) {
        var files = new ArrayList<File>(); var paths = new HashSet<String>(); long chars = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        for (int page = 1; page <= 30; page++) {
            if (System.nanoTime() > deadline) throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "Pull request retrieval timed out; retry");
            var result = get(token, repo, "/pulls/" + number + "/files?per_page=100&page=" + page);
            if (!result.data().isArray() || result.data().size() > 100) throw invalid();
            for (var node : result.data()) {
                String path = text(node, "filename"), patch = optional(node, "patch");
                if (!paths.add(path)) throw new ApiException(HttpStatus.CONFLICT, "Pull request changed during retrieval; retry");
                chars += patch == null ? 0 : patch.length(); if (chars > 10_000_000) throw tooLarge();
                files.add(new File(path, text(node, "status"), optional(node, "previous_filename"), count(node, "additions", true),
                        count(node, "deletions", true), count(node, "changes", true), patch));
            }
            if (!result.next()) return List.copyOf(files);
        }
        throw tooLarge(); // GitHub's 3000-file cap: never silently return a partial context.
    }
    private PullRequest map(JsonNode node, Repository repo, boolean detail) {
        try {
            if (node.path("base").path("repo").path("id").asLong() != repo.githubId()) throw invalid();
            if (!node.path("draft").isBoolean()) throw invalid();
            JsonNode mergeable = node.path("mergeable");
            if (!mergeable.isMissingNode() && !mergeable.isNull() && !mergeable.isBoolean()) throw invalid();
            return new PullRequest(count(node, "number", true), text(node, "title"), optional(node, "body"), text(node, "state"),
                    optional(node.path("user"), "login"), Instant.parse(text(node, "created_at")), Instant.parse(text(node, "updated_at")),
                    text(node.path("head"), "ref"), text(node.path("base"), "ref"), text(node.path("base"), "sha"), text(node.path("head"), "sha"),
                    mergeable.isBoolean() ? mergeable.asBoolean() : null, node.get("draft").asBoolean(), text(node, "html_url"),
                    count(node, "additions", detail), count(node, "deletions", detail), count(node, "changed_files", detail));
        } catch (DateTimeException ex) { throw invalid(); }
    }
    private static String text(JsonNode node, String field) { String value = optional(node, field); if (value == null || value.isBlank()) throw invalid(); return value; }
    private static String optional(JsonNode node, String field) {
        var value = node.path(field); if (value.isNull() || value.isMissingNode()) return null;
        if (!value.isTextual()) throw invalid(); return value.asText();
    }
    private static Integer count(JsonNode node, String field, boolean required) {
        var value = node.path(field);
        if (!required && (value.isMissingNode() || value.isNull())) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0 || (field.equals("number") && value.asInt() == 0)) throw invalid(); return value.asInt();
    }
    public static ApiException invalid() { return new ApiException(HttpStatus.BAD_GATEWAY, "GitHub returned an invalid pull request response"); }
    public static ApiException tooLarge() { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Pull request exceeds ingestion limits; no partial context returned"); }
}
