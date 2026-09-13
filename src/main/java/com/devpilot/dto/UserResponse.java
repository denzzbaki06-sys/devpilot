package com.devpilot.dto;
import com.devpilot.model.Role;
import com.devpilot.model.User;

public record UserResponse(Long id, String name, String email, Role role) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
