package com.Hotel.Module_Report.security;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
public class ReportIdentityService implements UserDetailsService {
    private final JdbcTemplate jdbc;
    public ReportIdentityService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Identity(long id, String email, String passwordHash, String role, Long branchId,
                           boolean active, boolean unlocked) {}
    public Identity find(String email) {
        var rows = jdbc.query("""
                SELECT u.id,u.email,u.password_hash,r.code,u.branch_id,
                       (u.active AND u.deleted_at IS NULL) AS active,
                       (u.locked_until IS NULL OR u.locked_until <= now()) AS unlocked
                FROM hotel.users u JOIN hotel.roles r ON r.id=u.role_id WHERE u.email=?
                """, (rs,n) -> new Identity(rs.getLong("id"),rs.getString("email"),
                rs.getString("password_hash"),rs.getString("code"),rs.getObject("branch_id",Long.class),
                rs.getBoolean("active"),rs.getBoolean("unlocked")),email);
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Override public UserDetails loadUserByUsername(String email) {
        Identity user = find(email);
        if (user == null) throw new UsernameNotFoundException("Invalid credentials");
        return User.withUsername(user.email()).password(user.passwordHash()).roles(user.role())
                .disabled(!user.active()).accountLocked(!user.unlocked()).build();
    }
}
