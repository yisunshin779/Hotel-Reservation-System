package com.Hotel.Module_TaiKhoan.security;

import java.io.IOException;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LinkedHotelIdentity {
    private final JdbcTemplate jdbc;
    private final JwtTokenProvider tokens;
    private final SecurityConfig accountSecurity;
    private final boolean enabled;

    public LinkedHotelIdentity(JdbcTemplate jdbc, JwtTokenProvider tokens, SecurityConfig accountSecurity,
                               @Value("${hotel.booking.canonical-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.accountSecurity = accountSecurity;
        this.enabled = enabled;
    }

    public void configure(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(accountSecurity.corsConfigurationSource()));
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws IOException, ServletException {
                String header = request.getHeader("Authorization");
                if (enabled && header != null && header.startsWith("Bearer ")) {
                    authenticate(header.substring(7));
                }
                chain.doFilter(request, response);
            }
        }, BasicAuthenticationFilter.class);
    }

    private void authenticate(String token) {
        JwtTokenProvider.Session session;
        try {
            session = tokens.readSession(token);
        } catch (JwtException | IllegalArgumentException ex) {
            return;
        }

        var users = jdbc.query("""
                SELECT u.email, r.code, a.token_version
                FROM hotel.account_user_links link
                JOIN hotel.account a ON a.id = link.account_id
                JOIN hotel.users u ON u.id = link.user_id
                JOIN hotel.roles r ON r.id = u.role_id
                WHERE a.id = ? AND a.trang_thai = 'DA_KICH_HOAT'
                    AND u.active AND u.deleted_at IS NULL
                    AND (u.locked_until IS NULL OR u.locked_until <= now())
                """, (rs, row) -> session.matches(rs.getInt("token_version"))
                        ? User.withUsername(rs.getString("email")).password("").roles(rs.getString("code")).build()
                        : null, session.accountId());
        if (!users.isEmpty() && users.get(0) != null) {
            var user = users.get(0);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
            SecurityContextHolder.setContext(context);
        }
    }
}
