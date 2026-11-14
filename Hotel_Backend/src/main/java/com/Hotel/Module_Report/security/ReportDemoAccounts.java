package com.Hotel.Module_Report.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("report-demo")
public class ReportDemoAccounts implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String password;
    public ReportDemoAccounts(JdbcTemplate jdbc,PasswordEncoder encoder,
            @Value("${REPORT_DEMO_PASSWORD:}") String password) {
        this.jdbc=jdbc;this.encoder=encoder;this.password=password;
    }
    @Override public void run(ApplicationArguments args) {
        if(password.isBlank()) return;
        if(!"hotel_report_demo".equals(jdbc.queryForObject("SELECT current_database()",String.class)))
            throw new IllegalStateException("Demo account creation is restricted to hotel_report_demo");
        String hash=encoder.encode(password);
        for(String role:new String[]{"ADMIN","MANAGER"}) {
            String email=role.equals("ADMIN")?"admin-report@example.test":"manager-report@example.test";
            jdbc.update("""
                INSERT INTO hotel.users(email,full_name,password_hash,role_id,branch_id)
                SELECT ?,?, ?,r.id,CASE WHEN r.code='MANAGER' THEN (SELECT min(id) FROM hotel.branches) ELSE NULL END
                FROM hotel.roles r WHERE r.code=?
                ON CONFLICT(email) DO UPDATE SET password_hash=EXCLUDED.password_hash,
                  role_id=EXCLUDED.role_id,branch_id=EXCLUDED.branch_id,active=true,locked_until=NULL
                """,email,"Report demo "+role,hash,role);
        }
    }
}
