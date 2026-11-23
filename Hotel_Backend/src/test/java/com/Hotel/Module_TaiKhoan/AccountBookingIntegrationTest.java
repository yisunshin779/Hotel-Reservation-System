package com.Hotel.Module_TaiKhoan;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.Hotel.Module_DatPhong.dto.BookingRequestDTO;
import com.Hotel.Module_DatPhong.entity.RoomType;
import com.Hotel.Module_DatPhong.repository.ReservationRepository;
import com.Hotel.Module_DatPhong.repository.RoomTypeRepository;
import com.Hotel.Module_DatPhong.service.BookingService;
import com.Hotel.Module_TaiKhoan.entity.Account;
import com.Hotel.Module_TaiKhoan.entity.VerificationToken;
import com.Hotel.Module_TaiKhoan.repository.AccountRepository;
import com.Hotel.Module_TaiKhoan.repository.VerificationTokenRepository;
import com.Hotel.Module_TaiKhoan.security.JwtTokenProvider;
import com.Hotel.Module_TaiKhoan.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
    "hotel.payment.expiry-enabled=false", "management.health.mail.enabled=false", "logging.level.root=WARN"
})
@AutoConfigureMockMvc
class AccountBookingIntegrationTest {
    private static final EmbeddedPostgres PG = startPostgres();

    @MockBean JavaMailSender mail;
    @MockBean RedissonClient redis;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired AccountRepository accounts;
    @Autowired VerificationTokenRepository tokens;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookingService booking;
    @Autowired RoomTypeRepository roomTypes;
    @Autowired ReservationRepository reservations;

    private static EmbeddedPostgres startPostgres() {
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
    static void closeDatabase() throws Exception {
        PG.close();
    }

    @Test
    void baselinesLegacyDatabaseAndPreservesAccountAndBookingRows() throws Exception {
        jdbc.execute("CREATE DATABASE legacy_upgrade");
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl("postgres", "legacy_upgrade"), "postgres", "");
        var legacy = new JdbcTemplate(dataSource);
        for (String script : List.of("01_schema.sql", "02_operations.sql", "05_checkin_checkout.sql",
                "06_stay_contracts.sql", "07_payment.sql", "08_invoice_adjustments.sql")) {
            legacy.execute(Files.readString(Path.of("../Hotel_Database/sql", script)));
        }
        try (var sql = getClass().getResourceAsStream("/db/migration/V9__legacy_jpa_tables.sql")) {
            legacy.execute(new String(sql.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var id = UUID.randomUUID();
        var fixtureHash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("fixture-only");
        legacy.update("""
                INSERT INTO hotel.account(id, ho_ten, email, mat_khau, auth_provider, vai_tro, trang_thai, thoi_gian_tao)
                VALUES (?, 'Existing', 'legacy@example.test', ?, 'LOCAL', 'ROLE_USER', 'DA_KICH_HOAT', now())
                """, id, fixtureHash);
        legacy.update("""
                INSERT INTO hotel.reservation(id, ma_loai_phong, so_luong_phong, ngay_nhan, ngay_tra,
                                              trang_thai, thoi_gian_tao)
                VALUES (?, 'STD', 1, current_date, current_date+1, 'CONFIRMED', now())
                """, id);

        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas("hotel").baselineVersion("8").load();
        assertThrows(FlywayException.class, flyway::migrate);
        flyway.baseline();
        assertEquals(2, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(fixtureHash, legacy.queryForObject("SELECT mat_khau FROM hotel.account WHERE id=?", String.class, id));
        assertNull(legacy.queryForObject("SELECT google_sub FROM hotel.account WHERE id=?", String.class, id));
        assertEquals("CONFIRMED",
                legacy.queryForObject("SELECT trang_thai FROM hotel.reservation WHERE id=?", String.class, id));
    }

    @Test
    void registersVerifiesAndLogsInThroughHttp() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("""
                {"hoTen":"New Guest","email":"registered@example.test","soDienThoai":"0901234567",
                 "matKhau":"SampleOnly123","nhapLaiMatKhau":"SampleOnly123"}
                """)).andExpect(status().isCreated());
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        String token = message.getValue().getText().split("token=")[1].trim();
        mvc.perform(get("/api/v1/auth/verify").param("token", token)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("""
                {"email":"registered@example.test","matKhau":"WrongOnly123"}
                """)).andExpect(status().isBadRequest());
        assertEquals(1, accounts.findByEmail("registered@example.test").orElseThrow().getSoLanDangNhapSai());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("""
                {"email":"registered@example.test","matKhau":"SampleOnly123"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void canonicalReservationReachesClosedInvoiceThroughPaymentAndStayApis() throws Exception {
        long branch = jdbc.queryForObject("""
                INSERT INTO hotel.branches(name,address,city) VALUES('Lifecycle','Office','City') RETURNING id
                """, Long.class);
        long otherBranch = jdbc.queryForObject("""
                INSERT INTO hotel.branches(name,address,city) VALUES('Other','Office','City') RETURNING id
                """, Long.class);
        long guestRole = jdbc.queryForObject("""
                INSERT INTO hotel.roles(code,name) VALUES('GUEST','Guest') RETURNING id
                """, Long.class);
        long staffRole = jdbc.queryForObject("""
                INSERT INTO hotel.roles(code,name) VALUES('RECEPTIONIST','Receptionist') RETURNING id
                """, Long.class);
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("fixture-only");
        long guest = fixtureUser("lifecycle-guest@example.test", hash, guestRole, null);
        fixtureUser("lifecycle-staff@example.test", hash, staffRole, branch);
        fixtureUser("other-staff@example.test", hash, staffRole, otherBranch);
        long customer = jdbc.queryForObject("""
                INSERT INTO hotel.customers(user_id,full_name) VALUES(?,'Guest') RETURNING id
                """, Long.class, guest);
        long type = jdbc.queryForObject("""
                INSERT INTO hotel.room_types(branch_id,name,capacity,base_price)
                VALUES(?,'Standard',2,100) RETURNING id
                """, Long.class, branch);
        long room = jdbc.queryForObject("""
                INSERT INTO hotel.rooms(branch_id,room_type_id,room_number) VALUES(?,?,'101') RETURNING id
                """, Long.class, branch, type);
        assertEquals(1, jdbc.queryForObject("""
                SELECT hotel.available_count(?, (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
                """, Integer.class, type));
        long reservation = jdbc.queryForObject("""
                SELECT hotel.make_reservation(?, ?, (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                      (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+2,
                      jsonb_build_array(jsonb_build_object('room_type_id', ?::bigint, 'quantity', 1)), ?)
                """, Long.class, customer, branch, type, "IT-" + UUID.randomUUID());
        String url = "/api/reservations/" + reservation;
        var staff = httpBasic("lifecycle-staff@example.test", "fixture-only");
        var selected = mvc.perform(post(url + "/payments").with(httpBasic("lifecycle-guest@example.test", "fixture-only"))
                .contentType("application/json").content("{\"method\":\"CASH\",\"plan\":\"FULL\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reservationId").value(reservation))
                .andReturn();
        String intent = json.readTree(selected.getResponse().getContentAsString()).path("intent").path("id").asText();
        mvc.perform(post(url + "/payments/" + intent + "/collect-cash").with(staff)
                .contentType("application/json").content("{\"cashReceived\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.intent.paymentStatus").value("PAID"));

        String checkIn = """
                {"roomIds":[%d],"guest":{"fullName":"Guest","identificationInfo":"AB123456"}}
                """.formatted(room);
        mvc.perform(post(url + "/checkin").with(httpBasic("other-staff@example.test", "fixture-only"))
                .contentType("application/json").content(checkIn)).andExpect(status().isForbidden());
        mvc.perform(post(url + "/checkin").with(staff).contentType("application/json").content(checkIn))
                .andExpect(status().isOk());
        mvc.perform(post(url + "/checkout").with(staff)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationStatus").value("CHECKED_OUT"))
                .andExpect(jsonPath("$.invoiceStatus").value("CLOSED"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM hotel.payments p JOIN hotel.invoices i ON i.id=p.invoice_id
                WHERE p.reservation_id=? AND i.reservation_id=? AND p.status='PAID'
                """, Integer.class, reservation, reservation));
    }

    @Test
    void jwtUsesCurrentRoleAndRejectsLockedAccount() throws Exception {
        Account account = account("jwt@example.test", "ROLE_ADMIN", "DA_KICH_HOAT");
        String bearer = "Bearer " + jwt.generateToken(account.getId(), account.getEmail(), account.getVaiTro());
        mvc.perform(get("/api/v1/admin/accounts").header("Authorization", bearer))
                .andExpect(status().isOk());

        jdbc.update("UPDATE hotel.account SET vai_tro='ROLE_USER' WHERE id=?", account.getId());
        mvc.perform(get("/api/v1/admin/accounts").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer))
                .andExpect(status().isOk());

        jdbc.update("UPDATE hotel.account SET trang_thai='BI_KHOA' WHERE id=?", account.getId());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void emailVerificationChecksPurposeAndPreservesAdminLock() {
        Account account = account("locked@example.test", "ROLE_USER", "BI_KHOA");
        VerificationToken reset = token(account, "RESET_PASSWORD");
        assertThrows(IllegalArgumentException.class, () -> auth.verifyEmailToken(reset.getToken()));

        VerificationToken verification = token(account, "VERIFY_EMAIL");
        assertThrows(IllegalStateException.class, () -> auth.verifyEmailToken(verification.getToken()));
        assertEquals("BI_KHOA", accounts.findById(account.getId()).orElseThrow().getTrangThai());
    }

    @Test
    void googleRequiresExplicitLinkForExistingAccountAndSignsInBySubject() throws Exception {
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        ReflectionTestUtils.setField(auth, "googleVerifier", verifier);
        try {
            Account existing = account("existing@example.test", "ROLE_ADMIN", "DA_KICH_HOAT");
            googleToken(verifier, "valid", "google-123", existing.getEmail());
            googleToken(verifier, "changed-email", "google-123", "other@example.test");
            googleToken(verifier, "different-subject", "google-456", existing.getEmail());
            googleToken(verifier, "new-account", "google-789", "new-google@example.test");
            assertThrows(IllegalArgumentException.class, () -> auth.loginWithGoogle("invalid"));
            assertThrows(IllegalStateException.class, () -> auth.loginWithGoogle("valid"));

            mvc.perform(post("/api/v1/accounts/me/google").contentType("application/json")
                    .content("{\"idToken\":\"valid\"}")).andExpect(status().isForbidden());

            String bearer = "Bearer " + jwt.generateToken(existing.getId(), existing.getEmail(), existing.getVaiTro());
            mvc.perform(post("/api/v1/accounts/me/google").header("Authorization", bearer)
                    .contentType("application/json").content("{\"idToken\":\"valid\"}"))
                    .andExpect(status().isNoContent());

            assertEquals(existing.getId(), jwt.readAccountId(auth.loginWithGoogle("changed-email").getToken()));
            assertThrows(IllegalStateException.class, () -> auth.linkGoogle(existing.getEmail(), "different-subject"));
            Account other = account("other@example.test", "ROLE_USER", "DA_KICH_HOAT");
            assertThrows(IllegalStateException.class, () -> auth.linkGoogle(other.getEmail(), "valid"));
            assertEquals("ROLE_USER", auth.loginWithGoogle("new-account").getVaiTro());

            jdbc.update("UPDATE hotel.account SET thoi_gian_khoa=now()+interval '10 minutes' WHERE id=?",
                    existing.getId());
            assertEquals(existing.getId(), jwt.readAccountId(auth.loginWithGoogle("valid").getToken()));
            jdbc.update("UPDATE hotel.account SET trang_thai='BI_KHOA' WHERE id=?", existing.getId());
            assertThrows(IllegalStateException.class, () -> auth.loginWithGoogle("valid"));
        } finally {
            ReflectionTestUtils.setField(auth, "googleVerifier", null);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentHoldsPersistOnlyOneReservation() throws Exception {
        LocalDate start = LocalDate.now().plusDays(1);
        RoomType room = new RoomType();
        room.setId("TEST-SINGLE");
        room.setTenLoaiPhong("Single");
        room.setTongSoPhong(1);
        room.setGiaTienCoBan(java.math.BigDecimal.TEN);
        roomTypes.save(room);
        var request = new BookingRequestDTO(room.getId(), 1, start, start.plusDays(1), null);
        var insideFirstTransaction = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        RBucket<String> bucket = mock(RBucket.class);
        when(redis.<String>getBucket(anyString())).thenReturn(bucket);
        doAnswer(call -> {
            insideFirstTransaction.countDown();
            assertTrue(releaseFirst.await(10, TimeUnit.SECONDS));
            return null;
        }).when(bucket).set(eq("AWAITING_PAYMENT"), eq(15L), eq(TimeUnit.MINUTES));

        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> booking.holdRoomForPayment(request));
            assertTrue(insideFirstTransaction.await(10, TimeUnit.SECONDS));
            var second = workers.submit(() ->
                    assertThrows(IllegalStateException.class, () -> booking.holdRoomForPayment(request)));
            releaseFirst.countDown();
            UUID id = UUID.fromString(first.get(10, TimeUnit.SECONDS));
            second.get(10, TimeUnit.SECONDS);
            assertNotNull(reservations.findById(id).orElseThrow().getThoiGianTao());
            assertEquals(1, reservations.countOccupiedRooms(room.getId(), start, start.plusDays(1)));
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }
    }

    private void googleToken(GoogleIdTokenVerifier verifier, String input, String subject, String email) throws Exception {
        GoogleIdToken.Payload claims = new GoogleIdToken.Payload();
        claims.setIssuer("https://accounts.google.com");
        claims.setSubject(subject);
        claims.setExpirationTimeSeconds(Instant.now().plusSeconds(120).getEpochSecond());
        claims.setEmail(email);
        claims.setEmailVerified(true);
        GoogleIdToken idToken = mock(GoogleIdToken.class);
        when(idToken.getPayload()).thenReturn(claims);
        when(verifier.verify(input)).thenReturn(idToken);
    }

    private Account account(String email, String role, String status) {
        Account account = new Account();
        account.setEmail(email);
        account.setHoTen("Fixture Account");
        account.setVaiTro(role);
        account.setTrangThai(status);
        return accounts.save(account);
    }

    private long fixtureUser(String email, String hash, long role, Long branch) {
        return jdbc.queryForObject("""
                INSERT INTO hotel.users(email,full_name,password_hash,role_id,branch_id)
                VALUES(?,'Fixture User',?,?,?) RETURNING id
                """, Long.class, email, hash, role, branch);
    }

    private VerificationToken token(Account account, String purpose) {
        VerificationToken token = new VerificationToken();
        token.setAccount(account);
        token.setLoaiToken(purpose);
        token.setToken(UUID.randomUUID().toString());
        token.setNgayHetHan(LocalDateTime.now().plusMinutes(15));
        return tokens.save(token);
    }
}
