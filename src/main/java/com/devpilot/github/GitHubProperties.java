package com.devpilot.github;

import com.devpilot.exception.ApiException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GitHubProperties {
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    public GitHubProperties(@Value("${github.client-id}") String clientId,
                            @Value("${github.client-secret}") String clientSecret,
                            @Value("${github.redirect-uri}") String redirectUri) {
        this.clientId = clientId; this.clientSecret = clientSecret; this.redirectUri = redirectUri;
    }
    public String clientId() { return clientId; }
    public String clientSecret() { return clientSecret; }
    public String redirectUri() { return redirectUri; }
    public boolean secureCookie() { return redirectUri.startsWith("https://"); }
    public void requireConfigured() {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub OAuth is not configured");
        }
        try {
            URI uri = URI.create(redirectUri);
            boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if (uri.getHost() == null || uri.getFragment() != null || uri.getUserInfo() != null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub redirect URI is not configured correctly");
        }
    }
}
