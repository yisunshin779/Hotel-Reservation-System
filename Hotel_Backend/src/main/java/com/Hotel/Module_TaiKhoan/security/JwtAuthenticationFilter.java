package com.Hotel.Module_TaiKhoan.security;

import java.io.IOException;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.Hotel.Module_TaiKhoan.repository.AccountRepository;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider tokenProvider;
    private final AccountRepository accounts;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, AccountRepository accounts) {
        this.tokenProvider = tokenProvider;
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            UUID accountId = readAccountId(header.substring(7));
            if (accountId != null) {
                accounts.findById(accountId)
                        .filter(account -> "DA_KICH_HOAT".equals(account.getTrangThai()))
                        .ifPresent(account -> authenticate(account.getEmail(), account.getVaiTro()));
            }
        }
        chain.doFilter(request, response);
    }

    private UUID readAccountId(String token) {
        try {
            return tokenProvider.readAccountId(token);
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }

    private void authenticate(String email, String role) {
        if (role == null || role.isBlank()) {
            return;
        }
        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        var user = User.withUsername(email).password("")
                .authorities(new SimpleGrantedAuthority(authority)).build();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }
}
