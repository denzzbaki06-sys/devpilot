package com.devpilot.dto;
import jakarta.validation.constraints.*;

public record LoginRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(min = 8, max = 72) String password) {
    public LoginRequest { email = com.devpilot.service.UserService.normalizeEmail(email); }
    @Override public String toString() { return "LoginRequest[REDACTED]"; }
}
