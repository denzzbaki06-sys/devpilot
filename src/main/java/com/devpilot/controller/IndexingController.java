package com.devpilot.controller;
import com.devpilot.indexing.*;
import com.devpilot.service.UserService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repositories/{id}")
public class IndexingController {
    private final IndexingService indexing;
    private final IndexJobStore store;
    private final UserService users;
    public IndexingController(IndexingService indexing, IndexJobStore store, UserService users) { this.indexing = indexing; this.store = store; this.users = users; }
    @PostMapping("/index")
    public ResponseEntity<IndexingService.Accepted> index(Authentication auth, @PathVariable Long id) {
        var result = indexing.start(users.currentUser(auth).getId(), id);
        return ResponseEntity.accepted().location(URI.create("/api/repositories/" + id + "/index-status")).body(result);
    }
    @GetMapping("/index-status")
    public IndexJobStore.IndexStatus status(Authentication auth, @PathVariable Long id) { return store.status(users.currentUser(auth).getId(), id); }
}
