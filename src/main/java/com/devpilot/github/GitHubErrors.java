package com.devpilot.github;

import com.devpilot.exception.ApiException;
import org.springframework.http.*;

/** Shared safe upstream errors; never retain provider bodies or credentials. */
public final class GitHubErrors {
    private GitHubErrors() {}
    public static ApiException failure(int status, HttpHeaders headers, boolean exchange) {
        if (status == 429 || (status == 403 && ("0".equals(headers.getFirst("X-RateLimit-Remaining")) || headers.containsHeader("Retry-After"))))
            return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "GitHub rate limit reached; retry later");
        if (exchange) return new ApiException(HttpStatus.BAD_GATEWAY, "GitHub authorization code exchange failed");
        if (status == 401) return new ApiException(HttpStatus.UNAUTHORIZED, "GitHub authorization expired or revoked; reconnect GitHub");
        if (status == 403) return new ApiException(HttpStatus.FORBIDDEN, "GitHub access forbidden or rate limited");
        if (status == 404) return new ApiException(HttpStatus.NOT_FOUND, "GitHub repository or pull request not found or inaccessible");
        return new ApiException(HttpStatus.BAD_GATEWAY, "GitHub request failed");
    }
}
