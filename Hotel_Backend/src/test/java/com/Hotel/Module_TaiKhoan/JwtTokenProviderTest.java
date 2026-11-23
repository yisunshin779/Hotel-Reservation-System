package com.Hotel.Module_TaiKhoan;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.Hotel.Module_TaiKhoan.security.JwtTokenProvider;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {
    private final String key = UUID.randomUUID().toString();
    private final JwtTokenProvider tokens = new JwtTokenProvider(key, 60_000);

    @Test
    void rejectsWrongSignatureAndAcceptsConfiguredSignature() {
        UUID id = UUID.randomUUID();
        String token = tokens.generateToken(id, "guest@example.test", "ROLE_USER");
        assertEquals(id, tokens.readAccountId(token));
        var other = new JwtTokenProvider(UUID.randomUUID().toString(), 60_000);
        assertThrows(JwtException.class, () -> other.readAccountId(token));
    }

    @Test
    void rejectsExpiredTokensAndTokensWithoutExpirationOrAccountId() {
        var signingKey = Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder().setSubject("guest@example.test")
                .claim("account_id", UUID.randomUUID().toString())
                .setExpiration(Date.from(Instant.now().minusSeconds(10))).signWith(signingKey).compact();
        String missingExpiration = Jwts.builder().setSubject("guest@example.test")
                .claim("account_id", UUID.randomUUID().toString()).signWith(signingKey).compact();
        String missingAccountId = Jwts.builder().setSubject("guest@example.test")
                .setExpiration(Date.from(Instant.now().plusSeconds(10))).signWith(signingKey).compact();
        assertThrows(JwtException.class, () -> tokens.readAccountId(expired));
        assertThrows(JwtException.class, () -> tokens.readAccountId(missingExpiration));
        assertThrows(JwtException.class, () -> tokens.readAccountId(missingAccountId));
    }

    @Test
    void rejectsMissingSigningConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider("short", 60_000));
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenProvider(key, 0));
    }
}
