package com.devpilot.controller;
import com.devpilot.rag.RepositoryQuestionAnsweringService;
import com.devpilot.service.UserService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repositories/{id}/ask")
public class RepositoryQuestionController {
    public record Request(String question, Integer topK) {
        @Override public String toString() { return "AskRequest[REDACTED]"; }
    }
    private final RepositoryQuestionAnsweringService service;
    private final UserService users;
    public RepositoryQuestionController(RepositoryQuestionAnsweringService service, UserService users) { this.service = service; this.users = users; }
    @PostMapping public ResponseEntity<RepositoryQuestionAnsweringService.Response> ask(Authentication auth, @PathVariable Long id, @RequestBody Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.ask(users.currentUser(auth).getId(), id, request.question(), request.topK()));
    }
}
