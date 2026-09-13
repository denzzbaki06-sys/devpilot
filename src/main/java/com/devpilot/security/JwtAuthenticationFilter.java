package com.devpilot.security;

import com.devpilot.service.JwtService;
import com.devpilot.repository.UserRepository;
import io.jsonwebtoken.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UserRepository users;
    private final SecurityErrorWriter errors;
    public JwtAuthenticationFilter(JwtService jwt, UserRepository users, SecurityErrorWriter errors) {
        this.jwt = jwt; this.users = users; this.errors = errors;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            try {
                if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) throw new MalformedJwtException("Invalid authorization");
                Claims claims = jwt.parseAccessToken(header.substring(7));
                var user = users.findByEmail(claims.getSubject()).orElseThrow(() -> new MalformedJwtException("Unknown user"));
                if (!user.getId().equals(claims.get("userId", Long.class))) throw new MalformedJwtException("Invalid user");
                var authentication = new UsernamePasswordAuthenticationToken(user.getEmail(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
                errors.write(response, 401, "Invalid or expired access token");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
