package com.devpilot.dto;
import jakarta.validation.constraints.*;

public record RegisterRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(min = 8, max = 72) String password) {
    public RegisterRequest { email = com.devpilot.service.UserService.normalizeEmail(email); }
    @Override public String toString() { return "RegisterRequest[REDACTED]"; }
}
