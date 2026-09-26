package com.devpilot.github;

import com.devpilot.exception.ApiException;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;
import tools.jackson.databind.*;

@Component
public class RestGitHubClient implements GitHubClient {
    private final RestClient api;
    private final RestClient oauth;
    private final GitHubProperties properties;
    private final ObjectMapper json;

    @Autowired
    public RestGitHubClient(GitHubProperties properties, ObjectMapper json) {
        this(client("https://api.github.com"), client("https://github.com"), properties, json);
    }
    public RestGitHubClient(RestClient api, RestClient oauth, GitHubProperties properties, ObjectMapper json) {
        this.api = api; this.oauth = oauth; this.properties = properties; this.json = json;
    }
    private static RestClient client(String baseUrl) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("User-Agent", "DevPilot").build();
    }
    @Override public Token exchangeCode(String code, String verifier) {
        properties.requireConfigured();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", properties.clientId()); form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri()); form.add("code", code); form.add("code_verifier", verifier);
        JsonNode data = parse(send(oauth.post().uri("/login/oauth/access_token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON).body(form), true).getBody());
        if (data.has("error") || !data.path("access_token").isTextual()
                || data.path("access_token").asText().isBlank()
                || !"bearer".equalsIgnoreCase(data.path("token_type").asText())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GitHub authorization code exchange failed; start connection again");
        }
        return new Token(data.get("access_token").asText(), data.path("scope").asText(""));
    }
    @Override public Profile currentUser(String token) {
        JsonNode data = parse(get("/user", token).getBody());
        return new Profile(id(data), text(data, "login"));
    }
    @Override public List<GitHubRepositoryDto> repositories(String token) {
        var repositories = new LinkedHashMap<Long, GitHubRepositoryDto>();
        for (int page = 1; page <= 1000; page++) {
            var response = get("/user/repos?per_page=100&page=" + page + "&sort=full_name&direction=asc", token);
            JsonNode data = parse(response.getBody());
            if (!data.isArray()) throw malformed();
            for (JsonNode item : data) {
                var repo = mapRepository(item); repositories.put(repo.githubRepositoryId(), repo);
            }
            // Only inspect next relation; never follow a server-provided URL with the bearer token.
            boolean next = response.getHeaders().getOrEmpty("Link").stream()
                    .anyMatch(link -> link.contains("rel=\"next\""));
            if (!next) return List.copyOf(repositories.values());
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "GitHub repository pagination limit exceeded; no partial list returned");
    }
    @Override public GitHubRepositoryDto repository(String token, long repositoryId) {
        // Resolve the numeric ID from the caller's accessible list, then verify current details
        // using GitHub's documented GET /repos/{owner}/{repo} endpoint.
        var visible = repositories(token).stream().filter(repo -> repo.githubRepositoryId() == repositoryId)
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GitHub repository not found or inaccessible"));
        var response = send(api.get().uri("/repos/{owner}/{repo}", visible.owner(), visible.name())
                .header("Authorization", "Bearer " + token).header("X-GitHub-Api-Version", "2022-11-28")
                .accept(MediaType.valueOf("application/vnd.github+json")), false);
        var result = mapRepository(parse(response.getBody()));
        if (result.githubRepositoryId() != repositoryId) throw malformed();
        return result;
    }
    private ResponseEntity<String> get(String path, String token) {
        return send(api.get().uri(path).header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .accept(MediaType.valueOf("application/vnd.github+json")), false);
    }
    private ResponseEntity<String> send(RestClient.RequestHeadersSpec<?> request, boolean exchange) {
        try {
            return request.retrieve().onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                // Preserve the established repository-specific 404 contract.
                if (!exchange && res.getStatusCode().value() == 404)
                    throw new ApiException(HttpStatus.NOT_FOUND, "GitHub repository not found or inaccessible");
                throw GitHubErrors.failure(res.getStatusCode().value(), res.getHeaders(), exchange);
            }).toEntity(String.class);
        } catch (RestClientException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GitHub is unavailable; retry later");
        }
    }
    private JsonNode parse(String body) {
        try {
            JsonNode data = json.readTree(body);
            if (data == null) throw malformed();
            return data;
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException ex) { throw malformed(); }
    }
    private GitHubRepositoryDto mapRepository(JsonNode data) {
        try {
            if (!data.path("private").isBoolean()) throw malformed();
            String updated = optional(data, "updated_at");
            return new GitHubRepositoryDto(id(data), text(data.path("owner"), "login"), text(data, "name"),
                    text(data, "full_name"), data.get("private").asBoolean(), text(data, "html_url"),
                    optional(data, "default_branch"), optional(data, "language"), updated == null ? null : Instant.parse(updated));
        } catch (DateTimeException ex) { throw malformed(); }
    }
    private long id(JsonNode data) {
        if (!data.path("id").isIntegralNumber() || data.get("id").asLong() <= 0) throw malformed();
        return data.get("id").asLong();
    }
    private String text(JsonNode data, String name) {
        if (!data.path(name).isTextual() || data.get(name).asText().isBlank()) throw malformed();
        return data.get(name).asText();
    }
    private String optional(JsonNode data, String name) { return data.path(name).isTextual() ? data.get(name).asText() : null; }
    private ApiException malformed() { return new ApiException(HttpStatus.BAD_GATEWAY, "GitHub returned an invalid response"); }
}
