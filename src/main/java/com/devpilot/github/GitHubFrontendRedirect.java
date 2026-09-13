package com.devpilot.github;

import java.net.URI;
import com.devpilot.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Fixed configured destination, never derived from OAuth query parameters. */
@Component
public class GitHubFrontendRedirect {
    private final String baseUrl;
    public GitHubFrontendRedirect(@Value("${devpilot.frontend.base-url:}") String baseUrl) { this.baseUrl = baseUrl; }
    public boolean enabled() { return !baseUrl.isBlank(); }
    public URI destination(boolean success) {
        try {
            URI uri = URI.create(baseUrl);
            boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) throw new IllegalArgumentException();
            return URI.create(baseUrl.replaceAll("/+$", "") + "/github?" + (success ? "connected=true" : "error=oauth_failed"));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Frontend callback URL is not configured correctly");
        }
    }
}
