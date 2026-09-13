package com.devpilot.dto;

public record AuthResponse(String accessToken, String refreshToken, String tokenType,
                           long expiresIn, UserResponse user) {
    @Override public String toString() { return "AuthResponse[REDACTED]"; }
}
