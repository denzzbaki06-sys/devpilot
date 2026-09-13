package com.devpilot.controller;
import com.devpilot.embedding.SemanticCodeSearchService;
import com.devpilot.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repositories/{id}/search")
public class SemanticSearchController {
    public record Request(@NotBlank @Size(max = 2000) String query, @Min(1) @Max(20) Integer limit) {
        public Request { if (limit == null) limit = 8; }
        @Override public String toString() { return "SemanticSearchRequest[REDACTED]"; }
    }
    private final SemanticCodeSearchService search;
    private final UserService users;
    public SemanticSearchController(SemanticCodeSearchService search, UserService users) { this.search = search; this.users = users; }
    @PostMapping
    public ResponseEntity<SemanticCodeSearchService.Response> search(Authentication auth, @PathVariable Long id, @Valid @RequestBody Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(search.search(users.currentUser(auth).getId(), id, request.query(), request.limit()));
    }
}
