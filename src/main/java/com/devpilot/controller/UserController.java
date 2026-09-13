package com.devpilot.controller;

import com.devpilot.dto.UserResponse;
import com.devpilot.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService users;
    public UserController(UserService users) { this.users = users; }
    @GetMapping("/me")
    public UserResponse me(Authentication authentication) { return UserResponse.from(users.currentUser(authentication)); }
}
