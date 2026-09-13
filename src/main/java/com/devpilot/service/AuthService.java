package com.devpilot.service;

import com.devpilot.dto.*;
import com.devpilot.model.*;
import com.devpilot.repository.*;
import com.devpilot.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository tokens, PasswordEncoder passwords,
                       JwtService jwt, @Value("${jwt.refresh-token-ttl}") Duration refreshTtl) {
        this.users = users; this.tokens = tokens; this.passwords = passwords;
        this.jwt = jwt; this.refreshTtl = refreshTtl;
        if (refreshTtl.isNegative() || refreshTtl.isZero()) throw new IllegalArgumentException("Refresh TTL must be positive");
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        validatePasswordBytes(request.password());
        String email = UserService.normalizeEmail(request.email());
        if (users.existsByEmail(email)) throw new ApiException(HttpStatus.CONFLICT, "Email already registered");
        User user = users.saveAndFlush(new User(request.name().trim(), email, passwords.encode(request.password())));
        return issue(user);
    }
    @Transactional
    public AuthResponse login(LoginRequest request) {
        validatePasswordBytes(request.password());
        User user = users.findByEmail(UserService.normalizeEmail(request.email())).orElse(null);
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches) throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        return issue(user);
    }
    @Transactional
    public AuthResponse refresh(String rawToken) {
        RefreshToken token = activeToken(rawToken);
        token.revoke();
        return issue(token.getUser());
    }
    @Transactional
    public void logout(String rawToken) { activeToken(rawToken).revoke(); }

    private RefreshToken activeToken(String rawToken) {
        // The write lock serializes rotation/logout for this token until transaction commit.
        RefreshToken token = tokens.findByHashForUpdate(hashToken(rawToken)).orElseThrow(this::invalidRefresh);
        if (token.isRevoked() || !token.getExpiresAt().isAfter(Instant.now())) throw invalidRefresh();
        return token;
    }
    private ApiException invalidRefresh() { return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"); }
    private AuthResponse issue(User user) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new RefreshToken(hashToken(raw), user, Instant.now().plus(refreshTtl)));
        return new AuthResponse(jwt.createAccessToken(user), raw, "Bearer", jwt.expiresIn(), UserResponse.from(user));
    }
    public static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must not exceed 72 UTF-8 bytes");
        }
    }
}
