package com.devpilot.controller;

import com.devpilot.dto.*;
import com.devpilot.service.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {
    private final ConnectedRepositoryService repositories;
    private final UserService users;
    public RepositoryController(ConnectedRepositoryService repositories, UserService users) { this.repositories = repositories; this.users = users; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public RepositoryResponse connect(Authentication auth, @Valid @RequestBody ConnectRepositoryRequest request) {
        return repositories.connect(users.currentUser(auth).getId(), request.githubRepositoryId());
    }
    @GetMapping public List<RepositoryResponse> list(Authentication auth) { return repositories.list(users.currentUser(auth).getId()); }
    @GetMapping("/{id}") public RepositoryResponse get(Authentication auth, @PathVariable Long id) { return repositories.get(users.currentUser(auth).getId(), id); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable Long id) { repositories.delete(users.currentUser(auth).getId(), id); }
}
