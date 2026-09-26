package com.devpilot.controller;

import com.devpilot.pullrequest.*;
import com.devpilot.service.UserService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/pull-requests")
public class PullRequestController {
    private final PullRequestService service;
    private final UserService users;
    public PullRequestController(PullRequestService service, UserService users) { this.service = service; this.users = users; }
    @GetMapping public ResponseEntity<PullRequestResponses.Page> list(Authentication auth, @PathVariable long repositoryId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        var result = service.list(users.currentUser(auth).getId(), repositoryId, page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PullRequestResponses.Page.from(repositoryId, page, size, result));
    }
    @GetMapping("/{number}") public ResponseEntity<PullRequestResponses.Detail> detail(Authentication auth, @PathVariable long repositoryId, @PathVariable int number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(PullRequestResponses.Detail.from(service.context(users.currentUser(auth).getId(), repositoryId, number)));
    }
}
