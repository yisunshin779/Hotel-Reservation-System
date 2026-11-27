package com.Hotel.Module_TaiKhoan.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtTokenProvider {
    private final SecretKey key;
    private final JwtParser parser;
    private final long expirationMs;

    public JwtTokenProvider(@Value("${app.jwt-secret}") String secret,
                            @Value("${app.jwt-expiration-ms:86400000}") long expirationMs) {
        if (secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("HOTEL_JWT_SECRET must be at least 32 UTF-8 bytes");
        }
        if (expirationMs <= 0) {
            throw new IllegalArgumentException("JWT expiration must be positive");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parserBuilder().setSigningKey(key).build();
        this.expirationMs = expirationMs;
    }

    public String generateToken(UUID accountId, String email, String role) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(email)
                .claim("account_id", accountId.toString())
                .claim("role", role)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expirationMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public UUID readAccountId(String token) {
        var signed = parser.parseClaimsJws(token);
        var claims = signed.getBody();
        String accountId = claims.get("account_id", String.class);
        if (!"HS256".equals(signed.getHeader().getAlgorithm())
                || claims.getExpiration() == null || claims.getSubject() == null
                || claims.getSubject().isBlank() || accountId == null) {
            throw new JwtException("Missing or invalid JWT claims");
        }
        return UUID.fromString(accountId);
    }
}
