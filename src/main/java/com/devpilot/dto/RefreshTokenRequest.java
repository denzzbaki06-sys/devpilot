package com.devpilot.dto;
import jakarta.validation.constraints.*;

public record RefreshTokenRequest(@NotBlank @Size(max = 128) String refreshToken) {
    @Override public String toString() { return "RefreshTokenRequest[REDACTED]"; }
}
