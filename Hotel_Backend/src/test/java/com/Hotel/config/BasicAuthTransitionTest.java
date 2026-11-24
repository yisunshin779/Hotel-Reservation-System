package com.Hotel.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.Hotel.Module_TaiKhoan.entity.Account;
import com.Hotel.Module_TaiKhoan.repository.AccountRepository;
import com.Hotel.Module_TaiKhoan.security.JwtTokenProvider;

/** #12: with HOTEL_BASIC_AUTH_ENABLED=false only linked Bearer tokens reach Payment, Stay and Report. */
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "hotel.booking.canonical-enabled=true",
    "hotel.security.basic-auth-enabled=false", "hotel.payment.expiry-enabled=false",
    "hotel.notifications.dispatch-enabled=false", "management.health.mail.enabled=false", "logging.level.root=WARN"
})
@AutoConfigureMockMvc
class BasicAuthTransitionTest {
    private static final EmbeddedPostgres PG = start();

    @MockBean JavaMailSender mail;
    @MockBean org.redisson.api.RedissonClient redis;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts;
    @Autowired JwtTokenProvider jwt;

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().setPort(0).start();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        properties.add("spring.datasource.username", () -> "postgres");
        properties.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void close() throws Exception {
        PG.close();
    }

    @Test
    void basicIsRefusedEverywhereWhileLinkedBearerStillWorks() throws Exception {
        long branch = jdbc.queryForObject("INSERT INTO hotel.branches(name,address,city) VALUES('B','A','C') RETURNING id", Long.class);
        long role = jdbc.queryForObject("INSERT INTO hotel.roles(code,name) VALUES('ADMIN','Admin') RETURNING id", Long.class);
        long user = jdbc.queryForObject("""
                INSERT INTO hotel.users(email,full_name,password_hash,role_id,branch_id) VALUES('admin@hotel.test','A',?,?,?) RETURNING id
                """, Long.class, new BCryptPasswordEncoder().encode("fixture-only"), role, branch);
        var basic = httpBasic("admin@hotel.test", "fixture-only");

        for (String url : java.util.List.of("/api/reservations/1/payments", "/api/reservations/1/checkout/preview",
                "/api/reports/options")) {
            var response = mvc.perform(get(url).with(basic)).andExpect(status().isUnauthorized()).andReturn().getResponse();
            assertNull(response.getHeader("WWW-Authenticate"), url);
        }
        mvc.perform(get("/api/reports/options")).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"status\":401,\"message\":\"Authentication required\"}"));

        Account account = new Account();
        account.setEmail("admin-login@hotel.test");
        account.setHoTen("Admin");
        account.setVaiTro("ROLE_USER");
        account.setTrangThai("DA_KICH_HOAT");
        account = accounts.save(account);
        jdbc.update("INSERT INTO hotel.account_user_links(account_id,user_id) VALUES(?,?)", account.getId(), user);
        String bearer = "Bearer " + jwt.generateToken(account.getId(), account.getEmail(), account.getVaiTro(), 0);
        mvc.perform(get("/api/reports/options").header("Authorization", bearer)).andExpect(status().isOk());
    }
}
