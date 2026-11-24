package com.Hotel.Module_TaiKhoan.security;

import java.io.IOException;
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
            JwtTokenProvider.Session session = readSession(header.substring(7));
            if (session != null) {
                accounts.findById(session.accountId())
                        .filter(account -> "DA_KICH_HOAT".equals(account.getTrangThai()))
                        .filter(account -> session.matches(account.getTokenVersion()))
                        .ifPresent(account -> authenticate(account.getEmail(), account.getVaiTro(), session));
            }
        }
        chain.doFilter(request, response);
    }

    private JwtTokenProvider.Session readSession(String token) {
        try {
            return tokenProvider.readSession(token);
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * The verified session (account UUID and token version) is kept as the authentication details, so a
     * write can re-check the caller under its own locks instead of trusting the request-time snapshot.
     */
    private void authenticate(String email, String role, JwtTokenProvider.Session session) {
        if (role == null || role.isBlank()) {
            return;
        }
        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        var user = User.withUsername(email).password("")
                .authorities(new SimpleGrantedAuthority(authority)).build();
        var context = SecurityContextHolder.createEmptyContext();
        var authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        authentication.setDetails(session);
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
