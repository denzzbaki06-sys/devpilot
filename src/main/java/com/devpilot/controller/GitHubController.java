package com.devpilot.controller;

import com.devpilot.github.*;
import com.devpilot.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/github")
public class GitHubController {
    private static final String COOKIE = "devpilot_github_oauth";
    private final GitHubService github;
    private final UserService users;
    private final GitHubProperties properties;
    private final GitHubFrontendRedirect frontend;
    public GitHubController(GitHubService github, UserService users, GitHubProperties properties, GitHubFrontendRedirect frontend) {
        this.github = github; this.users = users; this.properties = properties; this.frontend = frontend;
    }
    @GetMapping("/connect")
    public ResponseEntity<Map<String, String>> connect(Authentication auth) {
        var result = github.connect(users.currentUser(auth).getId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie(result.browserSecret(), 600).toString())
                .body(Map.of("authorizeUrl", result.authorizeUrl()));
    }
    @GetMapping("/callback")
    public Object callback(@RequestParam(required = false) String code,
            @RequestParam(required = false) String state, @RequestParam(required = false) String error,
            @CookieValue(name = COOKIE, required = false) String browser,
            @RequestHeader(value = "Accept", defaultValue = "application/json") String accept, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        boolean redirect = frontend.enabled() && accept.contains("text/html");
        // Validate the fixed destination before consuming the one-time state.
        var success = redirect ? frontend.destination(true) : null;
        try {
            var pending = github.consumeState(state, browser);
            response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
            var result = github.complete(pending, code, error);
            return redirect ? ResponseEntity.status(HttpStatus.SEE_OTHER).location(success).build() : result;
        } catch (com.devpilot.exception.ApiException ex) {
            if (!redirect) throw ex;
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(frontend.destination(false)).build();
        }
    }
    @GetMapping("/status")
    public GitHubService.Status status(Authentication auth) { return github.status(users.currentUser(auth).getId()); }
    @GetMapping("/repositories")
    public List<GitHubRepositoryDto> repositories(Authentication auth) { return github.repositories(users.currentUser(auth).getId()); }
    @PostMapping("/disconnect") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(Authentication auth, HttpServletResponse response) {
        github.disconnect(users.currentUser(auth).getId());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
    }
    private ResponseCookie cookie(String value, long maxAge) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(properties.secureCookie())
                .sameSite("Lax").path("/api/github").maxAge(Duration.ofSeconds(maxAge)).build();
    }
}
