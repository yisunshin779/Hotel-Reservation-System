package com.Hotel.Module_Report.security;

import com.Hotel.config.SchemaCapabilities;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class ReportIdentityService implements UserDetailsService {
    public static final String JWT_ONLY = "!linked-jwt-only!";
    private final JdbcTemplate jdbc;
    private final SchemaCapabilities schema;
    public ReportIdentityService(JdbcTemplate jdbc, SchemaCapabilities schema) { this.jdbc = jdbc; this.schema = schema; }
    // A user linked to an Account that is not active is unavailable through every mechanism, Basic included.
    private static final String LINKED_ACCOUNT_ACTIVE =
            " AND NOT EXISTS (SELECT 1 FROM hotel.account_user_links l JOIN hotel.account a ON a.id=l.account_id"
            + " WHERE l.user_id=u.id AND a.trang_thai<>'DA_KICH_HOAT')";
    public record Identity(long id, String email, String passwordHash, String role, Long branchId,
                           boolean active, boolean unlocked) {}
    public Identity find(String email) {
        var rows = jdbc.query("""
                SELECT u.id,u.email,u.password_hash,r.code,u.branch_id,
                       (u.active AND u.deleted_at IS NULL%s) AS active,
                       (u.locked_until IS NULL OR u.locked_until <= now()) AS unlocked
                FROM hotel.users u JOIN hotel.roles r ON r.id=u.role_id WHERE u.email=?
                """.formatted(schema.hasAccountLinks() ? LINKED_ACCOUNT_ACTIVE : ""), (rs,n) -> new Identity(rs.getLong("id"),rs.getString("email"),
                rs.getString("password_hash"),rs.getString("code"),rs.getObject("branch_id",Long.class),
                rs.getBoolean("active"),rs.getBoolean("unlocked")),email);
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Override public UserDetails loadUserByUsername(String email) {
        Identity user = find(email);
        if (user == null || JWT_ONLY.equals(user.passwordHash())) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        return User.withUsername(user.email()).password(user.passwordHash()).roles(user.role())
                .disabled(!user.active()).accountLocked(!user.unlocked()).build();
    }
}
