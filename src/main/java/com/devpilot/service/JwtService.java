package com.devpilot.service;

import com.devpilot.model.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration ttl;
    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.access-token-ttl}") Duration ttl) {
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("JWT TTL must be positive");
        this.ttl = ttl;
    }
    public long expiresIn() { return ttl.toSeconds(); }
    public String createAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder().subject(user.getEmail()).claim("userId", user.getId())
                .claim("role", user.getRole().name()).claim("type", "access")
                .id(UUID.randomUUID().toString()).issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl))).signWith(key).compact();
    }
    public Claims parseAccessToken(String token) {
        Claims claims = Jwts.parser().verifyWith(key).require("type", "access")
                .build().parseSignedClaims(token).getPayload();
        if (claims.getSubject() == null || claims.getExpiration() == null
                || claims.get("userId", Long.class) == null || claims.get("role", String.class) == null) {
            throw new MalformedJwtException("Missing access claims");
        }
        return claims;
    }
}
