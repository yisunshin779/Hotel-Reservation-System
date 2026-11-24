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

    /** Claim checked against hotel.account.token_version to revoke tokens issued before a change. */
    public static final String VERSION_CLAIM = "ver";

    /** Session read from a verified token. {@code version} is null for tokens issued before V13. */
    public record Session(UUID accountId, Integer version) {
        /** Tokens without a version stay valid only while no revocation event has happened. */
        public boolean matches(int currentVersion) {
            return version == null ? currentVersion == 0 : version == currentVersion;
        }
    }

    public String generateToken(UUID accountId, String email, String role, int tokenVersion) {
        return build(accountId, email, role, tokenVersion);
    }

    /** Pre-V13 token shape without the version claim. Kept for compatibility tests. */
    public String generateToken(UUID accountId, String email, String role) {
        return build(accountId, email, role, null);
    }

    private String build(UUID accountId, String email, String role, Integer tokenVersion) {
        Date now = new Date();
        var builder = Jwts.builder()
                .setSubject(email)
                .claim("account_id", accountId.toString())
                .claim("role", role)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expirationMs));
        if (tokenVersion != null) {
            builder.claim(VERSION_CLAIM, tokenVersion);
        }
        return builder.signWith(key, SignatureAlgorithm.HS256).compact();
    }

    public Session readSession(String token) {
        var claims = parser.parseClaimsJws(token).getBody();
        Object version = claims.get(VERSION_CLAIM);
        if (version != null && !(version instanceof Integer)) {
            throw new JwtException("Invalid token version");
        }
        return new Session(readAccountId(token), (Integer) version);
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
