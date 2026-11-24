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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "hotel.booking.canonical-enabled=true",
    "hotel.payment.expiry-enabled=false", "management.health.mail.enabled=false", "logging.level.root=WARN",
    "hotel.notifications.dispatch-enabled=false"
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
    @Autowired com.Hotel.notification.NotificationDispatcher dispatcher;
    @Autowired com.Hotel.Module_TaiKhoan.service.AccountService accountService;
    @Autowired com.Hotel.Module_TaiKhoan.service.HotelCustomerProvisioning provisioning;
    @Autowired com.Hotel.Module_DatPhong.service.CanonicalBookingService canonical;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired org.springframework.context.ApplicationContext applicationContext;

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
        Flyway toV11 = Flyway.configure().dataSource(dataSource).schemas("hotel").baselineVersion("8").target("11").load();
        assertEquals(3, toV11.migrate().migrationsExecuted);

        // V11 sample data, including an expired hold left with an ACTIVE QR selection by the pre-V12 routine.
        long branch = legacy.queryForObject("INSERT INTO hotel.branches(name,address,city) VALUES('Upgrade','A','C') RETURNING id", Long.class);
        long guestRole = legacy.queryForObject("SELECT id FROM hotel.roles WHERE code='GUEST'", Long.class);
        long user = legacy.queryForObject("""
                INSERT INTO hotel.users(email,full_name,password_hash,role_id) VALUES('legacy@example.test','Existing','!',?) RETURNING id
                """, Long.class, guestRole);
        long customer = legacy.queryForObject("INSERT INTO hotel.customers(user_id,full_name) VALUES(?,'Existing') RETURNING id", Long.class, user);
        legacy.update("INSERT INTO hotel.account_user_links(account_id,user_id) VALUES(?,?)", id, user);
        long type = legacy.queryForObject("INSERT INTO hotel.room_types(branch_id,name,capacity,base_price) VALUES(?,'T',2,100) RETURNING id", Long.class, branch);
        legacy.update("INSERT INTO hotel.rooms(branch_id,room_type_id,room_number) VALUES(?,?,'1')", branch, type);
        long held = legacy.queryForObject("""
                SELECT hotel.make_reservation(?,?,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+1,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+2,
                    jsonb_build_array(jsonb_build_object('room_type_id',?::bigint,'quantity',1)),'UPGRADE-1')
                """, Long.class, customer, branch, type);
        legacy.update("INSERT INTO hotel.booking_hold_requests(customer_id,request_id,reservation_id) VALUES(?,?,?)", customer, UUID.randomUUID(), held);
        long pending = legacy.queryForObject("""
                INSERT INTO hotel.payments(reservation_id,amount,method,status,obligation_key) VALUES(?,100,'MOCK_QR','PENDING','UPGRADE-QR') RETURNING id
                """, Long.class, held);
        UUID intent = UUID.randomUUID();
        legacy.update("INSERT INTO hotel.payment_intents(id,reservation_id,payment_id,payment_plan,state) VALUES(?,?,?,'FULL','ACTIVE')", intent, held, pending);
        legacy.update("UPDATE hotel.reservations SET hold_expires_at=now()-interval '1 minute' WHERE id=?", held);
        assertEquals(1, legacy.queryForObject("SELECT hotel.expire_holds()", Integer.class));
        assertEquals("ACTIVE", legacy.queryForObject("SELECT state FROM hotel.payment_intents WHERE id=?", String.class, intent));

        assertEquals(2, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals("13", flyway.info().current().getVersion().getVersion());
        assertEquals("CANCELLED", legacy.queryForObject("SELECT state FROM hotel.payment_intents WHERE id=?", String.class, intent));
        assertEquals("FAILED", legacy.queryForObject("SELECT status::text FROM hotel.payments WHERE id=?", String.class, pending));
        assertEquals("RELEASED", legacy.queryForObject("SELECT outcome FROM hotel.payment_reconciliation_items WHERE intent_id=?", String.class, intent));
        assertEquals("CANCELLED", legacy.queryForObject("SELECT status::text FROM hotel.reservations WHERE id=?", String.class, held));
        assertEquals(user, legacy.queryForObject("SELECT user_id FROM hotel.account_user_links WHERE account_id=?", Long.class, id));
        assertEquals(1, legacy.queryForObject("SELECT count(*) FROM hotel.booking_hold_requests WHERE reservation_id=?", Integer.class, held));
        assertEquals(0, legacy.queryForObject("SELECT token_version FROM hotel.account WHERE id=?", Integer.class, id));
        assertEquals(0, legacy.queryForObject("SELECT count(*) FROM hotel.account_email_outbox", Integer.class));
        assertEquals(fixtureHash, legacy.queryForObject("SELECT mat_khau FROM hotel.account WHERE id=?", String.class, id));
        assertNull(legacy.queryForObject("SELECT google_sub FROM hotel.account WHERE id=?", String.class, id));
        assertEquals("CONFIRMED",
                legacy.queryForObject("SELECT trang_thai FROM hotel.reservation WHERE id=?", String.class, id));
    }

    @Test
    void registersVerifiesAndLogsInThroughHttp() throws Exception {
        registerAndVerify("registered@example.test");

        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("""
                {"email":"registered@example.test","matKhau":"WrongOnly123"}
                """)).andExpect(status().isBadRequest());
        assertEquals(1, accounts.findByEmail("registered@example.test").orElseThrow().getSoLanDangNhapSai());
        assertNotNull(loginBearer("registered@example.test"));
    }

    private String registerAndVerify(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("""
                {"hoTen":"New Guest","email":"%s","soDienThoai":"0901234567",
                 "matKhau":"SampleOnly123","nhapLaiMatKhau":"SampleOnly123"}
                """.formatted(email))).andExpect(status().isCreated());
        // The verification e-mail is queued; nothing is sent inside the registration transaction.
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM hotel.account_email_outbox o JOIN hotel.account a ON a.id=o.account_id
                WHERE a.email=? AND o.purpose='VERIFY_EMAIL' AND o.status='PENDING'
                """, Integer.class, email));
        dispatcher.dispatchOnce();
        String token = mailTo(email, "auth/verify").split("token=")[1].trim();
        var verified = mvc.perform(get("/api/v1/auth/verify").param("token", token)).andExpect(status().isOk()).andReturn();
        return json.readTree(verified.getResponse().getContentAsString()).path("bookingProfile").asText();
    }

    /** Text of the last e-mail sent to {@code email} that contains {@code marker}. */
    private String mailTo(String email, String marker) {
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeastOnce()).send(message.capture());
        return message.getAllValues().stream()
                .filter(m -> List.of(m.getTo()).contains(email) && m.getText().contains(marker))
                .reduce((first, second) -> second).orElseThrow().getText();
    }

    private long role(String code) {
        return jdbc.queryForObject("""
                INSERT INTO hotel.roles(code,name) VALUES(?,?) ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name RETURNING id
                """, Long.class, code, code);
    }

    private String bearer(Account account) {
        Account current = accounts.findById(account.getId()).orElseThrow();
        return "Bearer " + jwt.generateToken(current.getId(), current.getEmail(), current.getVaiTro(), current.getTokenVersion());
    }

    private String loginBearer(String email) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("""
                {"email":"%s","matKhau":"SampleOnly123"}
                """.formatted(email))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty()).andReturn();
        return "Bearer " + json.readTree(result.getResponse().getContentAsString()).get("data").get("token").asText();
    }

    @Test
    void canonicalReservationReachesClosedInvoiceThroughPaymentAndStayApis() throws Exception {
        long branch = jdbc.queryForObject("""
                INSERT INTO hotel.branches(name,address,city) VALUES('Lifecycle','Office','City') RETURNING id
                """, Long.class);
        long otherBranch = jdbc.queryForObject("""
                INSERT INTO hotel.branches(name,address,city) VALUES('Other','Office','City') RETURNING id
                """, Long.class);
        long staffRole = role("RECEPTIONIST");
        long cleaningRole = role("HOUSEKEEPING");
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("fixture-only");
        long staff = fixtureUser("lifecycle-staff@example.test", hash, staffRole, branch);
        long otherStaff = fixtureUser("other-staff@example.test", hash, staffRole, otherBranch);
        long cleaner = fixtureUser("cleaner@example.test", hash, cleaningRole, branch);
        long otherCleaner = fixtureUser("other-cleaner@example.test", hash, cleaningRole, otherBranch);
        assertEquals("READY", registerAndVerify("lifecycle-guest@example.test"));
        String guestToken = loginBearer("lifecycle-guest@example.test");
        String staffToken = linkedBearer("lifecycle-login@example.test", staff);
        String otherStaffToken = linkedBearer("other-login@example.test", otherStaff);
        String cleanerToken = linkedBearer("cleaner-login@example.test", cleaner);
        String otherCleanerToken = linkedBearer("other-cleaner-login@example.test", otherCleaner);
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
        LocalDate start = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        LocalDate end = start.plusDays(2);
        mvc.perform(get("/api/v1/bookings/search").header("Authorization", guestToken)
                .param("ngayNhan", start.toString()).param("ngayTra", end.toString()).param("soNguoiLon", "2")
                .param("soTreEm", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.hasItem("hotel:" + type)));
        String request = """
                {"maLoaiPhong":"hotel:%d","soLuongPhong":1,"ngayNhan":"%s","ngayTra":"%s","soKhach":2}
                """.formatted(type, start, end);
        String key = UUID.randomUUID().toString();
        var unlinked = account("lifecycle-staff@example.test", "ROLE_RECEPTIONIST", "DA_KICH_HOAT");
        String unlinkedToken = "Bearer " + jwt.generateToken(unlinked.getId(), unlinked.getEmail(), unlinked.getVaiTro());
        // The key is checked before anything else, then the missing booking profile.
        mvc.perform(post("/api/v1/bookings/hold").header("Authorization", guestToken)
                .contentType("application/json").content(request)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/bookings/hold").header("Authorization", guestToken).header("Idempotency-Key", "not-a-uuid")
                .contentType("application/json").content(request)).andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM hotel.reservations WHERE branch_id=?", Integer.class, branch));
        mvc.perform(post("/api/v1/bookings/hold").header("Authorization", unlinkedToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").content(request)).andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("BOOKING_PROFILE_REQUIRED")));
        String code = mvc.perform(post("/api/v1/bookings/hold").header("Authorization", guestToken)
                .header("Idempotency-Key", key).contentType("application/json").content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/bookings/hold").header("Authorization", guestToken)
                .header("Idempotency-Key", key).contentType("application/json").content(request))
                .andExpect(status().isCreated()).andExpect(content().string(code));
        mvc.perform(post("/api/v1/bookings/hold").header("Authorization", guestToken)
                .header("Idempotency-Key", key).contentType("application/json")
                .content(request.replace("\"soKhach\":2", "\"soKhach\":1")))
                .andExpect(status().isConflict());
        long reservation = jdbc.queryForObject("""
                SELECT id FROM hotel.reservations WHERE code=? AND guest_count=2
                """, Long.class, code);
        String url = "/api/reservations/" + reservation;
        var selected = mvc.perform(post("/api/reservations/" + code + "/payments").header("Authorization", guestToken)
                .contentType("application/json").content("{\"method\":\"CASH\",\"plan\":\"FULL\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reservationId").value(reservation))
                .andReturn();
        mvc.perform(get(url + "/payments").header("Authorization", unlinkedToken))
                .andExpect(status().isUnauthorized());
        String intent = json.readTree(selected.getResponse().getContentAsString()).path("intent").path("id").asText();
        mvc.perform(post(url + "/payments/" + intent + "/collect-cash").header("Authorization", staffToken)
                .contentType("application/json").content("{\"cashReceived\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.intent.paymentStatus").value("PAID"));

        String checkIn = """
                {"roomIds":[%d],"guest":{"fullName":"Guest","identificationInfo":"AB123456"}}
                """.formatted(room);
        mvc.perform(post(url + "/checkin").header("Authorization", otherStaffToken)
                .contentType("application/json").content(checkIn)).andExpect(status().isForbidden());
        mvc.perform(post(url + "/checkin").header("Authorization", staffToken).contentType("application/json").content(checkIn))
                .andExpect(status().isOk());
        String cleaningUrl = "/api/v1/housekeeping/rooms/" + room + "/cleaning-status";
        int occupiedVersion = roomVersion(cleanerToken, room);
        mvc.perform(put(cleaningUrl).header("Authorization", cleanerToken)
                .contentType("application/json").content(cleaning("CLEANING", occupiedVersion, "READY")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_OCCUPIED"));
        mvc.perform(post(url + "/checkout").header("Authorization", staffToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationStatus").value("CHECKED_OUT"))
                .andExpect(jsonPath("$.invoiceStatus").value("CLOSED"));
        assertEquals("DIRTY", jdbc.queryForObject("SELECT cleaning::text FROM hotel.rooms WHERE id=?", String.class, room));
        // The read path returns the version the caller sends back; checkout increased it.
        mvc.perform(get("/api/v1/housekeeping/rooms").param("cleaningStatus", "DIRTY").header("Authorization", cleanerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.roomId==" + room + ")].cleaningStatus").value("DIRTY"));
        int v = roomVersion(cleanerToken, room);
        assertTrue(v > occupiedVersion);
        mvc.perform(get("/api/v1/housekeeping/rooms/" + room).header("Authorization", otherCleanerToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/housekeeping/rooms").header("Authorization", staffToken)).andExpect(status().isForbidden());
        String startCleaning = cleaning("DIRTY", v, "CLEANING");
        String cleaned = cleaning("CLEANING", v + 1, "READY");
        mvc.perform(put(cleaningUrl).header("Authorization", otherCleanerToken)
                .contentType("application/json").content(startCleaning)).andExpect(status().isNotFound());
        mvc.perform(put(cleaningUrl).header("Authorization", staffToken)
                .contentType("application/json").content(startCleaning)).andExpect(status().isForbidden());
        mvc.perform(put(cleaningUrl).header("Authorization", cleanerToken).contentType("application/json")
                .content("{\"expectedVersion\":" + v + ",\"cleaningStatus\":\"CLEANING\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(cleaningUrl).header("Authorization", cleanerToken).contentType("application/json")
                .content("{\"expectedStatus\":\"DIRTY\",\"cleaningStatus\":\"CLEANING\"}")).andExpect(status().isBadRequest());
        mvc.perform(put(cleaningUrl).header("Authorization", cleanerToken).contentType("application/json")
                .content(cleaning("DIRTY", v, "READY")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_CLEANING_TRANSITION"));
        mvc.perform(put(cleaningUrl).header("Authorization", cleanerToken)
                .contentType("application/json").content(cleaning("CLEANING", v, "READY")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_CLEANING_STATUS"));
        // A request prepared before checkout (older version) is stale although the status matches now.
        mvc.perform(put(cleaningUrl).header("Authorization", cleanerToken)
                .contentType("application/json").content(cleaning("DIRTY", occupiedVersion, "CLEANING")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_CLEANING_STATUS"));
        mvc.perform(put(cleaningUrl).header("Authorization", cleanerToken)
                .contentType("application/json").content(startCleaning))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cleaningStatus").value("CLEANING"))
                .andExpect(jsonPath("$.version").value(v + 1)).andExpect(jsonPath("$.changed").value(true));
        // A retried step is acknowledged without a second write.
        mvc.perform(put(cleaningUrl).header("Authorization", cleanerToken)
                .contentType("application/json").content(startCleaning))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(v + 1))
                .andExpect(jsonPath("$.changed").value(false));
        mvc.perform(put(cleaningUrl).header("Authorization", cleanerToken)
                .contentType("application/json").content(cleaned))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cleaningStatus").value("READY"))
                .andExpect(jsonPath("$.version").value(v + 2));
        assertEquals("READY", jdbc.queryForObject("SELECT cleaning::text FROM hotel.rooms WHERE id=?", String.class, room));
        // A delayed DIRTY -> CLEANING request cannot move a READY room back.
        mvc.perform(put(cleaningUrl).header("Authorization", cleanerToken)
                .contentType("application/json").content(startCleaning))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_CLEANING_STATUS"));
        assertEquals(List.of("DIRTY>CLEANING", "CLEANING>READY"), jdbc.queryForList("""
                SELECT (before_data->>'cleaning')||'>'||(after_data->>'cleaning') FROM hotel.audit_logs
                WHERE action='ROOM_CLEANING_STATUS_CHANGED' AND entity_id=? AND actor_id=? ORDER BY id
                """, String.class, room, cleaner));
        assertEquals("READY", jdbc.queryForObject("SELECT cleaning::text FROM hotel.rooms WHERE id=?", String.class, room));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM hotel.payments p JOIN hotel.invoices i ON i.id=p.invoice_id
                WHERE p.reservation_id=? AND i.reservation_id=? AND p.status='PAID'
                """, Integer.class, reservation, reservation));

        // UC08 confirmation and UC19 invoice delivery reach the guest through the outbox worker.
        dispatcher.dispatchOnce();
        assertEquals("SENT", jdbc.queryForObject(
                "SELECT status FROM hotel.booking_confirmation_requests WHERE reservation_id=?", String.class, reservation));
        assertEquals("SENT", jdbc.queryForObject("""
                SELECT d.status FROM hotel.invoice_delivery_requests d JOIN hotel.invoices i ON i.id=d.invoice_id
                WHERE i.reservation_id=?
                """, String.class, reservation));
        assertTrue(mailTo("lifecycle-guest@example.test", code).contains(code));
        assertTrue(mailTo("lifecycle-guest@example.test", "Hóa đơn").contains("Tổng thanh toán"));
    }

    private static String cleaning(String expected, int version, String target) {
        return "{\"expectedStatus\":\"%s\",\"expectedVersion\":%d,\"cleaningStatus\":\"%s\"}"
                .formatted(expected, version, target);
    }

    private int roomVersion(String token, long room) throws Exception {
        String body = mvc.perform(get("/api/v1/housekeeping/rooms/" + room).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("version").asInt();
    }

    /** Session of the account as the JWT filter would verify it now. */
    private JwtTokenProvider.Session session(Account account) {
        int version = jdbc.queryForObject("SELECT token_version FROM hotel.account WHERE id=?", Integer.class, account.getId());
        return new JwtTokenProvider.Session(account.getId(), version);
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

    private String linkedBearer(String email, long user) {
        Account account = account(email, "ROLE_USER", "DA_KICH_HOAT");
        jdbc.update("INSERT INTO hotel.account_user_links(account_id, user_id) VALUES(?, ?)", account.getId(), user);
        return "Bearer " + jwt.generateToken(account.getId(), email, account.getVaiTro());
    }

    private VerificationToken token(Account account, String purpose) {
        VerificationToken token = new VerificationToken();
        token.setAccount(account);
        token.setLoaiToken(purpose);
        token.setToken(UUID.randomUUID().toString());
        token.setNgayHetHan(LocalDateTime.now().plusMinutes(15));
        return tokens.save(token);
    }

    // ---- #3 forgot password / #8 revocation after reset ----
    @Test
    void forgotPasswordIsUniformAndResetRevokesEarlierSessions() throws Exception {
        registerAndVerify("reset@example.test");
        String before = loginBearer("reset@example.test");
        account("reset-locked@example.test", "ROLE_USER", "BI_KHOA");
        account("reset-google@example.test", "ROLE_USER", "DA_KICH_HOAT");
        String businessUrl = "/api/reservations/999999/payments";
        mvc.perform(get(businessUrl).header("Authorization", before)).andExpect(status().isNotFound());

        var original = ReflectionTestUtils.getField(auth, "clock");
        var fixed = java.time.Clock.fixed(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), java.time.ZoneOffset.UTC);
        ReflectionTestUtils.setField(auth, "clock", fixed);
        String reference = null;
        try {
            for (String email : List.of("reset@example.test", "nobody@example.test", "reset-locked@example.test", "reset-google@example.test")) {
                String body = mvc.perform(post("/api/v1/auth/forgot-password").contentType("application/json")
                        .content("{\"email\":\"" + email + "\"}")).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                if (reference == null) reference = body; else assertEquals(reference, body);
            }
        } finally {
            ReflectionTestUtils.setField(auth, "clock", original);
        }
        assertTrue(reference.contains(com.Hotel.Module_TaiKhoan.controller.AuthController.FORGOT_PASSWORD_MESSAGE));
        mvc.perform(post("/api/v1/auth/forgot-password").contentType("application/json").content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(List.of("reset@example.test"), jdbc.queryForList("""
                SELECT a.email FROM hotel.verification_token t JOIN hotel.account a ON a.id=t.account_id
                WHERE t.loai_token='RESET_PASSWORD' AND a.email LIKE 'reset%' ORDER BY 1
                """, String.class));
        var expiry = jdbc.queryForObject("""
                SELECT t.ngay_het_han FROM hotel.verification_token t JOIN hotel.account a ON a.id=t.account_id
                WHERE a.email='reset@example.test' AND t.loai_token='RESET_PASSWORD'
                """, java.sql.Timestamp.class).toLocalDateTime();
        assertEquals(LocalDateTime.now(fixed.withZone(java.time.ZoneId.systemDefault())).plusMinutes(30), expiry);

        dispatcher.dispatchOnce();
        String text = mailTo("reset@example.test", "reset-password");
        assertTrue(text.contains("30 phút"));
        String token = text.split("token=")[1].split("\\s")[0];
        String reset = "{\"token\":\"%s\",\"matKhauMoi\":\"NewSample123\",\"nhapLaiMatKhauMoi\":\"NewSample123\"}".formatted(token);
        // #19: the new password follows the registration rule (8+ characters with letters and digits).
        mvc.perform(post("/api/v1/auth/reset-password").contentType("application/json")
                .content("{\"token\":\"%s\",\"matKhauMoi\":\"abc123\",\"nhapLaiMatKhauMoi\":\"abc123\"}".formatted(token)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/reset-password").contentType("application/json").content(reset)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/reset-password").contentType("application/json").content(reset)).andExpect(status().isBadRequest());

        // Tokens issued before the reset are rejected by the Account chain and the linked business chain.
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", before)).andExpect(status().isForbidden());
        mvc.perform(get(businessUrl).header("Authorization", before)).andExpect(status().isUnauthorized());
        var login = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"email\":\"reset@example.test\",\"matKhau\":\"NewSample123\"}")).andExpect(status().isOk()).andReturn();
        String after = "Bearer " + json.readTree(login.getResponse().getContentAsString()).path("data").path("token").asText();
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", after)).andExpect(status().isOk());
        mvc.perform(get(businessUrl).header("Authorization", after)).andExpect(status().isNotFound());
        dispatcher.dispatchOnce();
        assertTrue(mailTo("reset@example.test", "Mật khẩu").contains("phiên đăng nhập"));
    }

    @Test
    void tokenWithoutVersionClaimIsRevokedByTheFirstRevocationEvent() throws Exception {
        Account account = account("legacy-token@example.test", "ROLE_USER", "DA_KICH_HOAT");
        String legacy = "Bearer " + jwt.generateToken(account.getId(), account.getEmail(), account.getVaiTro());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", legacy)).andExpect(status().isOk());
        jdbc.update("UPDATE hotel.account SET token_version=token_version+1 WHERE id=?", account.getId());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", legacy)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer(account))).andExpect(status().isOk());
    }

    // ---- #9 / #10 audit, revocation and last active Admin ----
    @Test
    void adminChangesAreAuditedRevokeSessionsAndKeepOneActiveAdmin() throws Exception {
        jdbc.update("UPDATE hotel.account SET vai_tro='ROLE_USER' WHERE vai_tro='ROLE_ADMIN'");
        Account first = account("admin-a@example.test", "ROLE_ADMIN", "DA_KICH_HOAT");
        Account second = account("admin-b@example.test", "ROLE_ADMIN", "DA_KICH_HOAT");
        Account target = account("managed@example.test", "ROLE_USER", "DA_KICH_HOAT");
        String admin = bearer(first);
        String targetBefore = bearer(target);
        String statusUrl = "/api/v1/admin/accounts/" + target.getId() + "/status";

        mvc.perform(patch(statusUrl).header("Authorization", admin).contentType("application/json")
                .content("{\"trangThai\":\"BI_KHOA\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", targetBefore)).andExpect(status().isForbidden());
        mvc.perform(patch(statusUrl).header("Authorization", admin).contentType("application/json")
                .content("{\"trangThai\":\"DA_KICH_HOAT\"}")).andExpect(status().isOk());
        // A token from before the lock stays rejected after unlocking.
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", targetBefore)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer(target))).andExpect(status().isOk());
        mvc.perform(patch(statusUrl).header("Authorization", admin).contentType("application/json")
                .content("{\"trangThai\":\"DA_KICH_HOAT\"}")).andExpect(status().isOk());

        String afterUnlock = bearer(target);
        mvc.perform(patch("/api/v1/admin/accounts/" + target.getId() + "/role").header("Authorization", admin)
                .contentType("application/json").content("{\"vaiTro\":\"ROLE_RECEPTIONIST\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", afterUnlock)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/accounts/" + target.getId() + "/role").header("Authorization", admin)
                .contentType("application/json").content("{\"vaiTro\":\"ROLE_MANAGER\"}")).andExpect(status().isBadRequest());
        assertEquals(List.of("ACCOUNT_LOCKED", "ACCOUNT_UNLOCKED", "ACCOUNT_ROLE_CHANGED"), jdbc.queryForList("""
                SELECT action FROM hotel.account_audit_logs WHERE target_account_id=? AND actor_account_id=? ORDER BY id
                """, String.class, target.getId(), first.getId()));
        assertEquals("ROLE_RECEPTIONIST", jdbc.queryForObject("""
                SELECT after_data->>'vai_tro' FROM hotel.account_audit_logs WHERE target_account_id=? AND action='ACCOUNT_ROLE_CHANGED'
                """, String.class, target.getId()));

        // Locking and unlocking an unverified account never activates it.
        Account pending = account("pending-lock@example.test", "ROLE_USER", "CHO_XAC_THUC");
        accountService.updateAccountStatus(pending.getId(), "BI_KHOA", session(first));
        accountService.updateAccountStatus(pending.getId(), "DA_KICH_HOAT", session(first));
        assertEquals("CHO_XAC_THUC", accounts.findById(pending.getId()).orElseThrow().getTrangThai());
        mvc.perform(patch("/api/v1/admin/accounts/" + pending.getId() + "/status").header("Authorization", admin)
                .contentType("application/json").content("{\"trangThai\":\"DA_KICH_HOAT\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_STATUS_CHANGE"));

        // Two Admins demoting each other at the same time cannot remove the last active Admin: the loser is
        // re-checked under the lock and is no longer an Admin.
        var firstSession = session(first);
        var secondSession = session(second);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { start.await(); return demote(second, firstSession); });
            var b = pool.submit(() -> { start.await(); return demote(first, secondSession); });
            start.countDown();
            List<String> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("OK"::equals).count(), results.toString());
            assertTrue(results.contains("ADMIN_ACCESS_REVOKED"), results.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM hotel.account WHERE vai_tro='ROLE_ADMIN' AND trang_thai='DA_KICH_HOAT'", Integer.class));
        UUID remaining = jdbc.queryForObject(
                "SELECT id FROM hotel.account WHERE vai_tro='ROLE_ADMIN' AND trang_thai='DA_KICH_HOAT'", UUID.class);
        Account demoted = accounts.findById(remaining.equals(first.getId()) ? second.getId() : first.getId()).orElseThrow();
        var denied = assertThrows(com.Hotel.Module_TaiKhoan.exception.AccountAccessDeniedException.class,
                () -> accountService.updateAccountStatus(remaining, "BI_KHOA", session(demoted)));
        assertEquals("ADMIN_ACCESS_REVOKED", denied.code());
        assertEquals("DA_KICH_HOAT", accounts.findById(remaining).orElseThrow().getTrangThai());
    }

    private String demote(Account target, JwtTokenProvider.Session actor) {
        try {
            accountService.updateAccountRole(target.getId(), "ROLE_USER", actor);
            return "OK";
        } catch (com.Hotel.Module_TaiKhoan.exception.AccountConflictException ex) {
            return ex.code();
        } catch (com.Hotel.Module_TaiKhoan.exception.AccountAccessDeniedException ex) {
            return ex.code();
        }
    }

    @Test
    void linkedAccountRoleIsManagedByHotelUsers() throws Exception {
        Account admin = account("role-admin@example.test", "ROLE_ADMIN", "DA_KICH_HOAT");
        long user = fixtureUser("linked-role@example.test", "!", role("RECEPTIONIST"), null);
        Account linked = account("linked-role-login@example.test", "ROLE_USER", "DA_KICH_HOAT");
        jdbc.update("INSERT INTO hotel.account_user_links(account_id, user_id) VALUES(?, ?)", linked.getId(), user);
        mvc.perform(patch("/api/v1/admin/accounts/" + linked.getId() + "/role").header("Authorization", bearer(admin))
                .contentType("application/json").content("{\"vaiTro\":\"ROLE_ADMIN\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_MANAGED_BY_HOTEL_USERS"));
        assertEquals("ROLE_USER", accounts.findById(linked.getId()).orElseThrow().getVaiTro());
        assertEquals("RECEPTIONIST", jdbc.queryForObject(
                "SELECT r.code FROM hotel.users u JOIN hotel.roles r ON r.id=u.role_id WHERE u.id=?", String.class, user));

        // Locking the Account also blocks the linked user's HTTP Basic login.
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("fixture-only");
        jdbc.update("UPDATE hotel.users SET password_hash=? WHERE id=?", hash, user);
        var basic = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .httpBasic("linked-role@example.test", "fixture-only");
        mvc.perform(get("/api/reservations/999999/payments").with(basic)).andExpect(status().isNotFound());
        accountService.updateAccountStatus(linked.getId(), "BI_KHOA", session(admin));
        mvc.perform(get("/api/reservations/999999/payments").with(basic)).andExpect(status().isUnauthorized());
    }

    // ---- #11 profile synchronisation ----
    @Test
    void profileUpdateSyncsLinkedRowsAndRollsBackTogether() throws Exception {
        registerAndVerify("profile@example.test");
        String token = loginBearer("profile@example.test");
        mvc.perform(put("/api/v1/accounts/me").header("Authorization", token).contentType("application/json")
                .content("{\"hoTen\":\"  Updated Guest  \",\"soDienThoai\":\"0911222333\"}")).andExpect(status().isOk());
        var linked = jdbc.queryForMap("""
                SELECT u.full_name AS user_name, u.phone AS user_phone, c.full_name AS customer_name, c.phone AS customer_phone
                FROM hotel.account a JOIN hotel.account_user_links l ON l.account_id=a.id JOIN hotel.users u ON u.id=l.user_id
                JOIN hotel.customers c ON c.user_id=u.id WHERE a.email='profile@example.test'
                """);
        assertEquals(java.util.Map.of("user_name", "Updated Guest", "user_phone", "0911222333",
                "customer_name", "Updated Guest", "customer_phone", "0911222333"), linked);

        Account unlinked = account("profile-unlinked@example.test", "ROLE_USER", "DA_KICH_HOAT");
        mvc.perform(put("/api/v1/accounts/me").header("Authorization", bearer(unlinked)).contentType("application/json")
                .content("{\"hoTen\":\"Solo\",\"soDienThoai\":\"\"}")).andExpect(status().isOk());
        assertEquals("Solo", accounts.findById(unlinked.getId()).orElseThrow().getHoTen());
        assertNull(accounts.findById(unlinked.getId()).orElseThrow().getSoDienThoai());

        jdbc.execute("ALTER TABLE hotel.customers ADD CONSTRAINT reject_profile_test CHECK (phone IS DISTINCT FROM '0999999999')");
        try {
            mvc.perform(put("/api/v1/accounts/me").header("Authorization", token).contentType("application/json")
                    .content("{\"hoTen\":\"Should Roll Back\",\"soDienThoai\":\"0999999999\"}"))
                    .andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("ALTER TABLE hotel.customers DROP CONSTRAINT reject_profile_test");
        }
        assertEquals("Updated Guest", accounts.findByEmail("profile@example.test").orElseThrow().getHoTen());
        assertEquals("0911222333", jdbc.queryForObject("SELECT phone FROM hotel.users WHERE email='profile@example.test'", String.class));
    }

    // ---- #5 verified account whose e-mail already belongs to hotel.users ----
    @Test
    void verificationWithExistingHotelUserWaitsForReviewAndBookingSaysSo() throws Exception {
        fixtureUser("shared@example.test", "!", role("RECEPTIONIST"), null);
        assertEquals("PENDING_REVIEW", registerAndVerify("shared@example.test"));
        Account account = accounts.findByEmail("shared@example.test").orElseThrow();
        assertEquals("DA_KICH_HOAT", account.getTrangThai());
        assertEquals("EMAIL_EXISTS_IN_HOTEL_USERS", jdbc.queryForObject(
                "SELECT reason FROM hotel.account_link_reviews WHERE account_id=?", String.class, account.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM hotel.account_user_links WHERE account_id=?", Integer.class, account.getId()));
        mvc.perform(post("/api/v1/bookings/hold").header("Authorization", loginBearer("shared@example.test"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"maLoaiPhong\":\"hotel:1\",\"soLuongPhong\":1,\"ngayNhan\":\"%s\",\"ngayTra\":\"%s\"}"
                        .formatted(LocalDate.now().plusDays(5), LocalDate.now().plusDays(6))))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("BOOKING_PROFILE_PENDING_REVIEW")));

        assertEquals("READY", registerAndVerify("fresh-guest@example.test"));
        assertEquals(com.Hotel.Module_TaiKhoan.service.HotelCustomerProvisioning.Result.ALREADY_LINKED,
                provisioning.provision(accounts.findByEmail("fresh-guest@example.test").orElseThrow()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM hotel.users WHERE email='fresh-guest@example.test'", Integer.class));
    }

    // ---- #4 idempotent hold / #6 search by room count and nightly price ----
    private long[] stayFixture(String name, int rooms) {
        long branch = jdbc.queryForObject("INSERT INTO hotel.branches(name,address,city) VALUES(?,'A','C') RETURNING id", Long.class, name);
        long type = jdbc.queryForObject("""
                INSERT INTO hotel.room_types(branch_id,name,capacity,base_price) VALUES(?,?,2,100) RETURNING id
                """, Long.class, branch, name);
        long[] ids = new long[rooms + 2];
        ids[0] = branch; ids[1] = type;
        for (int i = 0; i < rooms; i++) {
            ids[i + 2] = jdbc.queryForObject("INSERT INTO hotel.rooms(branch_id,room_type_id,room_number) VALUES(?,?,?) RETURNING id",
                    Long.class, branch, type, "R" + i);
        }
        return ids;
    }

    private com.fasterxml.jackson.databind.JsonNode searchResult(String token, long type, LocalDate from, LocalDate to,
                                                                 int adults, Integer rooms) throws Exception {
        var request = get("/api/v1/bookings/search").header("Authorization", token)
                .param("ngayNhan", from.toString()).param("ngayTra", to.toString())
                .param("soNguoiLon", String.valueOf(adults)).param("soTreEm", "0");
        if (rooms != null) request.param("soLuongPhong", rooms.toString());
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        if (!body.startsWith("[")) return null;
        for (var node : json.readTree(body)) {
            if (("hotel:" + type).equals(node.path("id").asText())) return node;
        }
        return null;
    }

    @Test
    void searchPricesEveryNightFiltersByRoomCountAndMatchesHold() throws Exception {
        long[] fixture = stayFixture("Search", 2);
        long type = fixture[1];
        registerAndVerify("search@example.test");
        String token = loginBearer("search@example.test");
        LocalDate start = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(10);
        LocalDate end = start.plusDays(3);
        jdbc.update("""
                INSERT INTO hotel.pricing_rules(room_type_id,name,starts_on,ends_on,priority,nightly_price)
                VALUES(?,'Peak',?,?,1,150)
                """, type, start.plusDays(1), start.plusDays(1));

        var two = searchResult(token, type, start, end, 2, 2);
        assertEquals(0, new java.math.BigDecimal("700").compareTo(two.path("tongGiaKyO").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("100").compareTo(two.path("giaTienCoBan").decimalValue()));
        assertEquals(2, two.path("soLuongPhong").asInt());
        assertEquals(3, two.path("soDem").asInt());
        assertEquals(2, two.path("tongSoPhong").asInt());
        assertEquals(List.of(start.toString(), start.plusDays(1).toString(), start.plusDays(2).toString()),
                List.of(two.path("giaTheoDem").get(0).path("ngay").asText(), two.path("giaTheoDem").get(1).path("ngay").asText(),
                        two.path("giaTheoDem").get(2).path("ngay").asText()));
        assertEquals(List.of(100, 150, 100), List.of(two.path("giaTheoDem").get(0).path("gia").asInt(),
                two.path("giaTheoDem").get(1).path("gia").asInt(), two.path("giaTheoDem").get(2).path("gia").asInt()));
        // Without soLuongPhong the minimum fitting count is priced: 3 guests need 2 rooms of capacity 2.
        assertEquals(2, searchResult(token, type, start, end, 3, null).path("soLuongPhong").asInt());
        assertNull(searchResult(token, type, start, end, 3, 1), "1 room cannot hold 3 guests");
        assertNull(searchResult(token, type, start, end, 2, 3), "only 2 rooms exist");

        // One night short: a room blocked on the middle night removes the 2-room offer only.
        jdbc.queryForObject("SELECT hotel.maintenance_block(?,?,?,'test')", Long.class, fixture[2], start.plusDays(1), start.plusDays(2));
        assertNull(searchResult(token, type, start, end, 2, 2));
        var one = searchResult(token, type, start, end, 2, 1);
        assertEquals(1, one.path("tongSoPhong").asInt());
        assertEquals(0, new java.math.BigDecimal("350").compareTo(one.path("tongGiaKyO").decimalValue()));

        String code = mvc.perform(post("/api/v1/bookings/hold").header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"maLoaiPhong\":\"hotel:%d\",\"soLuongPhong\":1,\"ngayNhan\":\"%s\",\"ngayTra\":\"%s\",\"soKhach\":2}"
                        .formatted(type, start, end))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(0, one.path("tongGiaKyO").decimalValue().compareTo(jdbc.queryForObject(
                "SELECT quoted_total FROM hotel.reservations WHERE code=?", java.math.BigDecimal.class, code)));

        for (String query : List.of("ngayNhan=%1$s&ngayTra=%1$s&soNguoiLon=1&soTreEm=0",
                "ngayNhan=%2$s&ngayTra=%1$s&soNguoiLon=1&soTreEm=0",
                "ngayNhan=%1$s&ngayTra=%2$s&soNguoiLon=1&soTreEm=0&soLuongPhong=0",
                "ngayNhan=%1$s&ngayTra=%2$s&soNguoiLon=0&soTreEm=0",
                "ngayNhan=not-a-date&ngayTra=%2$s&soNguoiLon=1&soTreEm=0")) {
            mvc.perform(get("/api/v1/bookings/search?" + query.formatted(start, end)).header("Authorization", token))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void concurrentHoldsWithTheSameKeyCreateOneReservation() throws Exception {
        long type = stayFixture("Replay", 2)[1];
        registerAndVerify("replay@example.test");
        LocalDate start = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(4);
        var request = new BookingRequestDTO("hotel:" + type, 1, start, start.plusDays(1), null, 2);
        UUID key = UUID.randomUUID();
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { go.await(); return canonical.hold(request, "replay@example.test", key); });
            var second = pool.submit(() -> { go.await(); return canonical.hold(request, "replay@example.test", key); });
            go.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM hotel.booking_hold_requests WHERE request_id=?", Integer.class, key));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM hotel.reservations r JOIN hotel.reservation_details d ON d.reservation_id=r.id
                WHERE d.room_type_id=?
                """, Integer.class, type));
        assertThrows(IllegalArgumentException.class, () -> canonical.hold(request, "replay@example.test", null));
    }

    // ---- #13 account e-mails go through the outbox ----
    @Test
    void registrationCommitsWhileSmtpIsDownAndTheWorkerRetries() throws Exception {
        doThrow(new org.springframework.mail.MailSendException("smtp down")).when(mail).send(any(SimpleMailMessage.class));
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("""
                {"hoTen":"Queued","email":"queued@example.test","soDienThoai":"0901234567",
                 "matKhau":"SampleOnly123","nhapLaiMatKhau":"SampleOnly123"}
                """)).andExpect(status().isCreated());
        Account queued = accounts.findByEmail("queued@example.test").orElseThrow();
        String outbox = "SELECT %s FROM hotel.account_email_outbox WHERE account_id=?";
        dispatcher.dispatchOnce();
        assertEquals("PENDING", jdbc.queryForObject(outbox.formatted("status"), String.class, queued.getId()));
        assertEquals(1, jdbc.queryForObject(outbox.formatted("attempts"), Integer.class, queued.getId()));
        assertEquals("SEND_ERROR", jdbc.queryForObject(outbox.formatted("last_error"), String.class, queued.getId()));
        assertEquals("CHO_XAC_THUC", accounts.findById(queued.getId()).orElseThrow().getTrangThai());
        // The stored row carries identifiers only: no address, link or token text.
        assertEquals(List.of("id", "account_id", "purpose", "token_id", "status", "attempts", "next_attempt_at",
                "locked_until", "last_error", "created_at", "sent_at"), jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns WHERE table_schema='hotel' AND table_name='account_email_outbox'
                ORDER BY ordinal_position
                """, String.class));

        reset(mail);
        // Backoff is PostgreSQL time: move the retry into the past instead of waiting for it.
        jdbc.update("UPDATE hotel.account_email_outbox SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE account_id=?", queued.getId());
        dispatcher.dispatchOnce();
        assertEquals("SENT", jdbc.queryForObject(outbox.formatted("status"), String.class, queued.getId()));
        String token = mailTo("queued@example.test", "auth/verify").split("token=")[1].trim();
        mvc.perform(get("/api/v1/auth/verify").param("token", token)).andExpect(status().isOk());

        var dto = json.readValue("""
                {"hoTen":"Rolled","email":"rolled@example.test","soDienThoai":"0901234567",
                 "matKhau":"SampleOnly123","nhapLaiMatKhau":"SampleOnly123"}
                """, com.Hotel.Module_TaiKhoan.dto.RegistrationRequestDTO.class);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            auth.registerUser(dto);
            status.setRollbackOnly();
        });
        assertTrue(accounts.findByEmail("rolled@example.test").isEmpty());
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM hotel.account_email_outbox o LEFT JOIN hotel.account a ON a.id=o.account_id
                WHERE a.id IS NULL OR a.email='rolled@example.test'
                """, Integer.class));
    }

    // ---- Admin rights and session are re-checked under the write lock ----
    private static final long ADMIN_CHANGE_LOCK = 7_201_000_001L;

    private void waitUntil(String condition, String sql) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(sql, Integer.class) == 0) {
            assertTrue(System.nanoTime() < deadline, condition);
            Thread.sleep(10);
        }
    }

    /** Runs {@code write} while another transaction holds the Admin lock, applies {@code revoke}, then releases. */
    private <T> T whileAdminLockIsHeld(java.util.concurrent.Callable<T> write, Runnable revoke) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(status -> {
                        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", ADMIN_CHANGE_LOCK);
                        held.countDown();
                        try {
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException ex) {
                            throw new IllegalStateException(ex);
                        }
                        return null;
                    }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var pending = pool.submit(write);
            waitUntil("the write never waited for the Admin lock",
                    "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted");
            revoke.run();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return pending.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void adminWriteWaitingForTheLockIsRefusedWhenTheCallerLosesAccess() throws Exception {
        Account actor = account("waiting-admin@example.test", "ROLE_ADMIN", "DA_KICH_HOAT");
        account("standing-admin@example.test", "ROLE_ADMIN", "DA_KICH_HOAT");
        Account target = account("waiting-target@example.test", "ROLE_USER", "DA_KICH_HOAT");
        String statusUrl = "/api/v1/admin/accounts/" + target.getId() + "/status";
        var revocations = new java.util.LinkedHashMap<String, String>();
        revocations.put("role removed", "UPDATE hotel.account SET vai_tro='ROLE_USER' WHERE id=?");
        revocations.put("account locked", "UPDATE hotel.account SET trang_thai='BI_KHOA' WHERE id=?");
        revocations.put("session revoked", "UPDATE hotel.account SET token_version=token_version+1 WHERE id=?");
        for (var revocation : revocations.entrySet()) {
            jdbc.update("UPDATE hotel.account SET vai_tro='ROLE_ADMIN', trang_thai='DA_KICH_HOAT' WHERE id=?", actor.getId());
            String bearer = bearer(actor); // valid when the request passes the filter
            var response = whileAdminLockIsHeld(() -> mvc.perform(patch(statusUrl).header("Authorization", bearer)
                            .contentType("application/json").content("{\"trangThai\":\"BI_KHOA\"}")).andReturn().getResponse(),
                    () -> jdbc.update(revocation.getValue(), actor.getId()));
            assertEquals(403, response.getStatus(), revocation.getKey());
            assertEquals("ADMIN_ACCESS_REVOKED", json.readTree(response.getContentAsString()).path("code").asText());
            assertEquals("DA_KICH_HOAT", accounts.findById(target.getId()).orElseThrow().getTrangThai());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM hotel.account_audit_logs WHERE target_account_id=?",
                    Integer.class, target.getId()), revocation.getKey());
        }

        // Role change: the caller is demoted while the change waits.
        jdbc.update("UPDATE hotel.account SET vai_tro='ROLE_ADMIN', trang_thai='DA_KICH_HOAT' WHERE id=?", actor.getId());
        var waiting = session(actor);
        String code = whileAdminLockIsHeld(() -> {
            try {
                accountService.updateAccountRole(target.getId(), "ROLE_RECEPTIONIST", waiting);
                return "OK";
            } catch (com.Hotel.Module_TaiKhoan.exception.AccountAccessDeniedException ex) {
                return ex.code();
            }
        }, () -> jdbc.update("UPDATE hotel.account SET vai_tro='ROLE_USER', token_version=token_version+1 WHERE id=?",
                actor.getId()));
        assertEquals("ADMIN_ACCESS_REVOKED", code);
        assertEquals("ROLE_USER", accounts.findById(target.getId()).orElseThrow().getVaiTro());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM hotel.account_audit_logs WHERE target_account_id=?",
                Integer.class, target.getId()));

        // Control: a caller that keeps its rights writes and is recorded as the actor.
        jdbc.update("UPDATE hotel.account SET vai_tro='ROLE_ADMIN' WHERE id=?", actor.getId());
        mvc.perform(patch(statusUrl).header("Authorization", bearer(actor)).contentType("application/json")
                .content("{\"trangThai\":\"BI_KHOA\"}")).andExpect(status().isOk());
        assertEquals(actor.getId(), jdbc.queryForObject(
                "SELECT actor_account_id FROM hotel.account_audit_logs WHERE target_account_id=?", UUID.class, target.getId()));
    }

    // ---- A successful reset invalidates every reset link of the account ----
    private com.Hotel.Module_TaiKhoan.dto.ResetPasswordRequestDTO resetRequest(String token, String password) {
        var request = new com.Hotel.Module_TaiKhoan.dto.ResetPasswordRequestDTO();
        request.setToken(token);
        request.setMatKhauMoi(password);
        request.setNhapLaiMatKhauMoi(password);
        return request;
    }

    private List<String> resetLinks(UUID account) {
        return jdbc.queryForList("""
                SELECT token FROM hotel.verification_token WHERE account_id=? AND loai_token='RESET_PASSWORD' ORDER BY token
                """, String.class, account);
    }

    @Test
    void passwordResetInvalidatesEveryEarlierLinkAndItsQueuedEmail() throws Exception {
        registerAndVerify("links@example.test");
        Account account = accounts.findByEmail("links@example.test").orElseThrow();
        String before = loginBearer("links@example.test");
        auth.forgotPassword("links@example.test");
        dispatcher.dispatchOnce();
        String first = mailTo("links@example.test", "reset-password").split("token=")[1].split("\\s")[0];
        auth.forgotPassword("links@example.test"); // a second link whose e-mail is still queued
        String second = resetLinks(account.getId()).stream().filter(t -> !t.equals(first)).findFirst().orElseThrow();
        String queued = """
                SELECT count(*) FROM hotel.account_email_outbox WHERE account_id=? AND purpose='RESET_PASSWORD' AND status='PENDING'
                """;
        assertEquals(1, jdbc.queryForObject(queued, Integer.class, account.getId()));
        VerificationToken verification = token(account, "VERIFY_EMAIL"); // another purpose is not touched

        mvc.perform(post("/api/v1/auth/reset-password").contentType("application/json").content(
                "{\"token\":\"%s\",\"matKhauMoi\":\"Changed123\",\"nhapLaiMatKhauMoi\":\"Changed123\"}".formatted(first)))
                .andExpect(status().isOk());
        assertEquals(List.of(), resetLinks(account.getId()));
        assertEquals(0, jdbc.queryForObject(queued, Integer.class, account.getId()));
        assertTrue(tokens.findById(verification.getId()).isPresent());
        assertEquals(1, accounts.findById(account.getId()).orElseThrow().getTokenVersion());
        mvc.perform(post("/api/v1/auth/reset-password").contentType("application/json").content(
                "{\"token\":\"%s\",\"matKhauMoi\":\"Other1234\",\"nhapLaiMatKhauMoi\":\"Other1234\"}".formatted(second)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", before)).andExpect(status().isForbidden());

        reset(mail);
        dispatcher.dispatchOnce();
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeastOnce()).send(sent.capture());
        var toAccount = sent.getAllValues().stream().filter(m -> List.of(m.getTo()).contains("links@example.test")).toList();
        assertEquals(1, toAccount.size());
        assertTrue(toAccount.get(0).getText().contains("phiên đăng nhập"));
        assertFalse(toAccount.get(0).getText().contains(second));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"email\":\"links@example.test\",\"matKhau\":\"Changed123\"}")).andExpect(status().isOk());
    }

    @Test
    void concurrentResetsWithTwoLinksChangeThePasswordOnce() throws Exception {
        registerAndVerify("race-reset@example.test");
        Account account = accounts.findByEmail("race-reset@example.test").orElseThrow();
        auth.forgotPassword("race-reset@example.test");
        auth.forgotPassword("race-reset@example.test");
        List<String> links = resetLinks(account.getId());
        assertEquals(2, links.size());
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 2; i++) {
                var request = resetRequest(links.get(i), "Parallel" + i + "Pass");
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        auth.resetPassword(request);
                        return "OK";
                    } catch (IllegalArgumentException ex) {
                        return "REJECTED";
                    }
                }));
            }
            go.countDown();
            var outcome = List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
            assertEquals(1, outcome.stream().filter("OK"::equals).count(), outcome.toString());
            assertTrue(outcome.contains("REJECTED"), outcome.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, accounts.findById(account.getId()).orElseThrow().getTokenVersion());
        assertEquals(List.of(), resetLinks(account.getId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM hotel.account_email_outbox WHERE account_id=? AND purpose='PASSWORD_CHANGED'
                """, Integer.class, account.getId()));
    }

    @Test
    void resetLinkRequestedWhileAResetCommitsStaysValid() throws Exception {
        registerAndVerify("serial-reset@example.test");
        Account account = accounts.findByEmail("serial-reset@example.test").orElseThrow();
        auth.forgotPassword("serial-reset@example.test");
        String used = resetLinks(account.getId()).get(0);
        var inReset = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var resetter = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(status -> {
                        auth.resetPassword(resetRequest(used, "Serial123Pass"));
                        inReset.countDown();
                        try {
                            assertTrue(commit.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException ex) {
                            throw new IllegalStateException(ex);
                        }
                        return null;
                    }));
            assertTrue(inReset.await(10, TimeUnit.SECONDS));
            var requester = pool.submit(() -> { auth.forgotPassword("serial-reset@example.test"); return null; });
            // The new request waits for the account row held by the reset.
            waitUntil("forgot-password did not wait for the account lock", """
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                    """);
            commit.countDown();
            resetter.get(10, TimeUnit.SECONDS);
            requester.get(10, TimeUnit.SECONDS);
        } finally {
            commit.countDown();
            pool.shutdownNow();
        }
        List<String> left = resetLinks(account.getId());
        assertEquals(1, left.size());
        assertNotEquals(used, left.get(0));
        auth.resetPassword(resetRequest(left.get(0), "Another123Pass"));
        assertEquals(2, accounts.findById(account.getId()).orElseThrow().getTokenVersion());
    }

    // ---- Verification links are checked against their deadline when sent ----
    @Test
    void verificationLinkIsNotSentAfterItsDeadlineButIsRetriedBefore() throws Exception {
        for (String email : List.of("late-verify@example.test", "edge-verify@example.test")) {
            mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("""
                    {"hoTen":"Late","email":"%s","soDienThoai":"0901234567",
                     "matKhau":"SampleOnly123","nhapLaiMatKhau":"SampleOnly123"}
                    """.formatted(email))).andExpect(status().isCreated());
        }
        Account late = accounts.findByEmail("late-verify@example.test").orElseThrow();
        Account edge = accounts.findByEmail("edge-verify@example.test").orElseThrow();
        String outbox = "SELECT %s FROM hotel.account_email_outbox WHERE account_id=?";
        java.util.function.Function<Account, java.time.Instant> deadline = a -> jdbc.queryForObject(
                "SELECT ngay_het_han FROM hotel.verification_token WHERE account_id=? AND loai_token='VERIFY_EMAIL'",
                java.sql.Timestamp.class, a.getId()).toLocalDateTime().atZone(java.time.ZoneId.systemDefault()).toInstant();
        // Only these two rows are due while the clock is moved.
        jdbc.update("UPDATE hotel.account_email_outbox SET next_attempt_at=now()+interval '30 days' WHERE status='PENDING' AND account_id NOT IN (?,?)",
                late.getId(), edge.getId());
        jdbc.update("UPDATE hotel.account_email_outbox SET next_attempt_at=now()+interval '30 days' WHERE account_id=?", edge.getId());
        Object originalDispatcher = ReflectionTestUtils.getField(dispatcher, "clock");
        Object originalAuth = ReflectionTestUtils.getField(auth, "clock");
        try {
            // One minute before the deadline SMTP is down: the valid link stays queued for a retry.
            ReflectionTestUtils.setField(dispatcher, "clock",
                    java.time.Clock.fixed(deadline.apply(late).minusSeconds(60), java.time.ZoneOffset.UTC));
            doThrow(new org.springframework.mail.MailSendException("smtp down")).when(mail).send(any(SimpleMailMessage.class));
            dispatcher.dispatchOnce();
            assertEquals("PENDING", jdbc.queryForObject(outbox.formatted("status"), String.class, late.getId()));
            assertEquals("SEND_ERROR", jdbc.queryForObject(outbox.formatted("last_error"), String.class, late.getId()));
            reset(mail);

            // The retry runs after the deadline: the request ends without sending a dead link. The backoff is
            // database time, so the row is made due in the database and the link deadline moved with the clock.
            jdbc.update("UPDATE hotel.account_email_outbox SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE account_id=?",
                    late.getId());
            ReflectionTestUtils.setField(dispatcher, "clock",
                    java.time.Clock.fixed(deadline.apply(late).plusSeconds(1), java.time.ZoneOffset.UTC));
            dispatcher.dispatchOnce();
            assertEquals("FAILED", jdbc.queryForObject(outbox.formatted("status"), String.class, late.getId()));
            assertEquals("TOKEN_EXPIRED", jdbc.queryForObject(outbox.formatted("last_error"), String.class, late.getId()));
            org.mockito.ArgumentMatcher<SimpleMailMessage> toLate = m -> List.of(m.getTo()).contains("late-verify@example.test");
            verify(mail, never()).send(argThat(toLate));
            // The verification API rejects the same token at the same instant.
            String lateToken = jdbc.queryForObject("SELECT token FROM hotel.verification_token WHERE account_id=?",
                    String.class, late.getId());
            ReflectionTestUtils.setField(auth, "clock",
                    java.time.Clock.fixed(deadline.apply(late).plusSeconds(1), java.time.ZoneOffset.UTC));
            mvc.perform(get("/api/v1/auth/verify").param("token", lateToken)).andExpect(status().isBadRequest());

            // Exactly at the deadline the link is still valid for both the worker and the API.
            jdbc.update("UPDATE hotel.account_email_outbox SET next_attempt_at=now() WHERE account_id=?", edge.getId());
            java.time.Instant edgeDeadline = deadline.apply(edge);
            ReflectionTestUtils.setField(dispatcher, "clock", java.time.Clock.fixed(edgeDeadline, java.time.ZoneOffset.UTC));
            dispatcher.dispatchOnce();
            assertEquals("SENT", jdbc.queryForObject(outbox.formatted("status"), String.class, edge.getId()));
            String edgeToken = mailTo("edge-verify@example.test", "auth/verify").split("token=")[1].trim();
            ReflectionTestUtils.setField(auth, "clock", java.time.Clock.fixed(edgeDeadline, java.time.ZoneOffset.UTC));
            mvc.perform(get("/api/v1/auth/verify").param("token", edgeToken)).andExpect(status().isOk());
        } finally {
            ReflectionTestUtils.setField(dispatcher, "clock", originalDispatcher);
            ReflectionTestUtils.setField(auth, "clock", originalAuth);
        }
    }

    // ---- One stay-length limit for canonical search and hold ----
    @Test
    void stayLengthLimitAppliesToSearchAndHoldBeforeAnyWrite() throws Exception {
        long type = stayFixture("Long stay", 1)[1];
        registerAndVerify("long-stay@example.test");
        String token = loginBearer("long-stay@example.test");
        LocalDate start = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(1);
        LocalDate atLimit = start.plusDays(90);
        LocalDate beyond = start.plusDays(91);

        assertEquals(90, searchResult(token, type, start, atLimit, 1, null).path("soDem").asInt());
        mvc.perform(get("/api/v1/bookings/search").header("Authorization", token).param("ngayNhan", start.toString())
                .param("ngayTra", beyond.toString()).param("soNguoiLon", "1").param("soTreEm", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("STAY_TOO_LONG")));

        String tooLong = "{\"maLoaiPhong\":\"hotel:%d\",\"soLuongPhong\":1,\"ngayNhan\":\"%s\",\"ngayTra\":\"%s\",\"soKhach\":1}"
                .formatted(type, start, beyond);
        String allowed = tooLong.replace(beyond.toString(), atLimit.toString());
        // The Idempotency-Key contract is checked first, then the stay length, before any write.
        mvc.perform(post("/api/v1/bookings/hold").header("Authorization", token).contentType("application/json").content(tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Idempotency-Key")));
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/bookings/hold").header("Authorization", token).header("Idempotency-Key", key)
                .contentType("application/json").content(tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("STAY_TOO_LONG")));
        String reservations = """
                SELECT count(*) FROM hotel.reservations r JOIN hotel.reservation_details d ON d.reservation_id=r.id
                WHERE d.room_type_id=?
                """;
        assertEquals(0, jdbc.queryForObject(reservations, Integer.class, type));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM hotel.booking_hold_requests WHERE request_id=?::uuid",
                Integer.class, key));

        // The refused key was never stored, so it can be used for a valid stay; replays return the same code.
        String code = mvc.perform(post("/api/v1/bookings/hold").header("Authorization", token).header("Idempotency-Key", key)
                .contentType("application/json").content(allowed)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/bookings/hold").header("Authorization", token).header("Idempotency-Key", key)
                .contentType("application/json").content(allowed)).andExpect(status().isCreated())
                .andExpect(content().string(code));
        // A used key is bound to its payload: another payload is 409, also when it exceeds the limit.
        mvc.perform(post("/api/v1/bookings/hold").header("Authorization", token).header("Idempotency-Key", key)
                .contentType("application/json").content(tooLong))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Idempotency-Key")));
        assertEquals(1, jdbc.queryForObject(reservations, Integer.class, type));
        assertEquals(90, jdbc.queryForObject("""
                SELECT check_out - check_in FROM hotel.reservations WHERE code=?
                """, Integer.class, code));
    }

    // ---- A verification e-mail survives an Admin lock followed by an unlock ----
    private Account registered(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("""
                {"hoTen":"Held","email":"%s","soDienThoai":"0901234567",
                 "matKhau":"SampleOnly123","nhapLaiMatKhau":"SampleOnly123"}
                """.formatted(email))).andExpect(status().isCreated());
        return accounts.findByEmail(email).orElseThrow();
    }

    private List<String> verifyOutbox(Account account) {
        return jdbc.queryForList("""
                SELECT status || coalesce(':' || last_error, '') FROM hotel.account_email_outbox
                WHERE account_id=? AND purpose='VERIFY_EMAIL' ORDER BY created_at, id
                """, String.class, account.getId());
    }

    private long mailsTo(String email) {
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeast(0)).send(message.capture());
        return message.getAllValues().stream().filter(m -> List.of(m.getTo()).contains(email)).count();
    }

    private String verificationToken(Account account) {
        return jdbc.queryForObject("SELECT token FROM hotel.verification_token WHERE account_id=? AND loai_token='VERIFY_EMAIL'",
                String.class, account.getId());
    }

    @Test
    void verificationEmailHeldByAnAdminLockIsSentAfterTheUnlock() throws Exception {
        Account admin = account("unlock-admin@example.test", "ROLE_ADMIN", "DA_KICH_HOAT");

        // Locked while the e-mail is queued; the worker runs during the lock; unlocked while the token is valid.
        Account held = registered("held-verify@example.test");
        String link = verificationToken(held);
        accountService.updateAccountStatus(held.getId(), "BI_KHOA", session(admin));
        dispatcher.dispatchOnce();
        assertEquals(0, mailsTo("held-verify@example.test"));
        assertEquals(1, verifyOutbox(held).size());
        assertEquals(List.of("FAILED:ACCOUNT_LOCKED"), verifyOutbox(held));
        accountService.updateAccountStatus(held.getId(), "DA_KICH_HOAT", session(admin));
        assertEquals("CHO_XAC_THUC", accounts.findById(held.getId()).orElseThrow().getTrangThai());
        assertEquals(link, verificationToken(held), "the existing token is reused");
        assertEquals(List.of("FAILED:ACCOUNT_LOCKED", "PENDING"), verifyOutbox(held), "a new row; the FAILED one stays");
        dispatcher.dispatchOnce();
        assertEquals(1, mailsTo("held-verify@example.test"));
        assertTrue(mailTo("held-verify@example.test", "auth/verify").contains(link));
        assertEquals(List.of("FAILED:ACCOUNT_LOCKED", "SENT"), verifyOutbox(held));
        assertEquals("CHO_XAC_THUC", accounts.findById(held.getId()).orElseThrow().getTrangThai(), "pending until the link is used");
        mvc.perform(get("/api/v1/auth/verify").param("token", link)).andExpect(status().isOk());
        assertEquals("DA_KICH_HOAT", accounts.findById(held.getId()).orElseThrow().getTrangThai());
        assertEquals(List.of(), verifyOutbox(held), "using the token removes its outbox rows");

        // Locked and unlocked before the worker ran: the queued row is sent once, nothing is added.
        Account paused = registered("paused-verify@example.test");
        accountService.updateAccountStatus(paused.getId(), "BI_KHOA", session(admin));
        accountService.updateAccountStatus(paused.getId(), "DA_KICH_HOAT", session(admin));
        assertEquals(List.of("PENDING"), verifyOutbox(paused));
        dispatcher.dispatchOnce();
        assertEquals(List.of("SENT"), verifyOutbox(paused));
        assertEquals(1, mailsTo("paused-verify@example.test"));

        // Token expired at unlock: the account stays pending, nothing is queued or sent (OC-01.03 is backlog).
        Account expired = registered("expired-verify@example.test");
        String expiredLink = verificationToken(expired);
        accountService.updateAccountStatus(expired.getId(), "BI_KHOA", session(admin));
        dispatcher.dispatchOnce();
        jdbc.update("UPDATE hotel.verification_token SET ngay_het_han=? WHERE account_id=?",
                LocalDateTime.now().minusMinutes(1), expired.getId());
        accountService.updateAccountStatus(expired.getId(), "DA_KICH_HOAT", session(admin));
        assertEquals("CHO_XAC_THUC", accounts.findById(expired.getId()).orElseThrow().getTrangThai());
        assertEquals(expiredLink, verificationToken(expired));
        assertEquals(List.of("FAILED:ACCOUNT_LOCKED"), verifyOutbox(expired));
        dispatcher.dispatchOnce();
        assertEquals(0, mailsTo("expired-verify@example.test"));

        // Already sent before the lock: the unlock does not send it again.
        Account sent = registered("sent-verify@example.test");
        dispatcher.dispatchOnce();
        assertEquals(List.of("SENT"), verifyOutbox(sent));
        accountService.updateAccountStatus(sent.getId(), "BI_KHOA", session(admin));
        accountService.updateAccountStatus(sent.getId(), "DA_KICH_HOAT", session(admin));
        dispatcher.dispatchOnce();
        assertEquals(List.of("SENT"), verifyOutbox(sent));
        assertEquals(1, mailsTo("sent-verify@example.test"));
        assertEquals("CHO_XAC_THUC", accounts.findById(sent.getId()).orElseThrow().getTrangThai());
    }

    private UUID verifyRow(Account account) {
        return jdbc.queryForObject("SELECT id FROM hotel.account_email_outbox WHERE account_id=? AND purpose='VERIFY_EMAIL'",
                UUID.class, account.getId());
    }

    private void afterRender(java.util.function.BiConsumer<String, Object> hook) {
        ReflectionTestUtils.setField(dispatcher, "afterRender", hook);
    }

    @Test
    void unlockRacingTheWorkerNeverLosesTheVerificationEmail() throws Exception {
        Account admin = account("race-unlock-admin@example.test", "ROLE_ADMIN", "DA_KICH_HOAT");
        java.util.function.BiConsumer<String, Object> none = (table, key) -> {};
        try {
            // 1. The unlock commits after the worker rendered "locked" and before it stored the result:
            //    the late FAILED is not stored, the worker renders again under the same claim and sends.
            Account first = registered("race-unlock-1@example.test");
            accountService.updateAccountStatus(first.getId(), "BI_KHOA", session(admin));
            UUID firstRow = verifyRow(first);
            boolean[] unlocked = {false};
            afterRender((table, key) -> {
                if (firstRow.equals(key) && !unlocked[0]) {
                    unlocked[0] = true;
                    accountService.updateAccountStatus(first.getId(), "DA_KICH_HOAT", session(admin));
                }
            });
            dispatcher.dispatchOnce();
            afterRender(none);
            assertTrue(unlocked[0]);
            assertEquals(List.of("SENT"), verifyOutbox(first));
            assertEquals(1, mailsTo("race-unlock-1@example.test"));
            assertTrue(mailTo("race-unlock-1@example.test", "auth/verify").contains(verificationToken(first)));
            assertEquals("CHO_XAC_THUC", accounts.findById(first.getId()).orElseThrow().getTrangThai());

            // 2. The worker's result waits for the account row held by an unlock in progress, then sees it.
            Account second = registered("race-unlock-2@example.test");
            accountService.updateAccountStatus(second.getId(), "BI_KHOA", session(admin));
            UUID secondRow = verifyRow(second);
            var rendered = new CountDownLatch(1);
            var proceed = new CountDownLatch(1);
            var inUnlock = new CountDownLatch(1);
            var commit = new CountDownLatch(1);
            afterRender((table, key) -> {
                if (secondRow.equals(key) && rendered.getCount() > 0) {
                    rendered.countDown();
                    try {
                        assertTrue(proceed.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            });
            var pool = Executors.newFixedThreadPool(2);
            try {
                var worker = pool.submit(() -> dispatcher.dispatchOnce());
                assertTrue(rendered.await(10, TimeUnit.SECONDS));
                var unlock = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                        .execute(status -> {
                            accountService.updateAccountStatus(second.getId(), "DA_KICH_HOAT", session(admin));
                            inUnlock.countDown();
                            try {
                                assertTrue(commit.await(10, TimeUnit.SECONDS));
                            } catch (InterruptedException ex) {
                                throw new IllegalStateException(ex);
                            }
                            return null;
                        }));
                assertTrue(inUnlock.await(10, TimeUnit.SECONDS));
                proceed.countDown();
                waitUntil("the worker did not wait for the account row", """
                        SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                        """);
                commit.countDown();
                unlock.get(10, TimeUnit.SECONDS);
                worker.get(10, TimeUnit.SECONDS);
            } finally {
                proceed.countDown();
                commit.countDown();
                pool.shutdownNow();
                afterRender(none);
            }
            assertEquals(List.of("SENT"), verifyOutbox(second));
            assertEquals(1, mailsTo("race-unlock-2@example.test"));
            assertEquals("CHO_XAC_THUC", accounts.findById(second.getId()).orElseThrow().getTrangThai());

            // 3. Reverse order: the worker stores FAILED while the unlock waits; the unlock then sees it and
            //    queues the e-mail again with the same token.
            Account third = registered("race-unlock-3@example.test");
            String link = verificationToken(third);
            accountService.updateAccountStatus(third.getId(), "BI_KHOA", session(admin));
            whileAdminLockIsHeld(() -> accountService.updateAccountStatus(third.getId(), "DA_KICH_HOAT", session(admin)),
                    () -> {
                        dispatcher.dispatchOnce();
                        assertEquals(List.of("FAILED:ACCOUNT_LOCKED"), verifyOutbox(third));
                    });
            assertEquals(List.of("FAILED:ACCOUNT_LOCKED", "PENDING"), verifyOutbox(third));
            dispatcher.dispatchOnce();
            assertEquals(List.of("FAILED:ACCOUNT_LOCKED", "SENT"), verifyOutbox(third));
            assertEquals(1, mailsTo("race-unlock-3@example.test"));
            assertTrue(mailTo("race-unlock-3@example.test", "auth/verify").contains(link));
            assertEquals("CHO_XAC_THUC", accounts.findById(third.getId()).orElseThrow().getTrangThai());
            assertEquals(link, verificationToken(third));
        } finally {
            afterRender(none);
        }
    }

    // ---- A stored hold stays replayable after the stay-length limit is lowered ----
    @Test
    void holdReplayIsKeptWhenTheStayLimitIsLoweredAndOnlyNewHoldsAreLimited() throws Exception {
        long type = stayFixture("Lowered limit", 2)[1];
        registerAndVerify("lowered-limit@example.test");
        String token = loginBearer("lowered-limit@example.test");
        LocalDate start = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(2);
        String body = "{\"maLoaiPhong\":\"hotel:%d\",\"soLuongPhong\":1,\"ngayNhan\":\"%s\",\"ngayTra\":\"%s\",\"soKhach\":1}";
        java.util.function.IntFunction<String> nights = n -> body.formatted(type, start, start.plusDays(n));
        java.util.function.BiFunction<String, String, org.springframework.test.web.servlet.ResultActions> hold = (key, content) -> {
            try {
                return mvc.perform(post("/api/v1/bookings/hold").header("Authorization", token).header("Idempotency-Key", key)
                        .contentType("application/json").content(content));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        };
        String reservations = """
                SELECT count(*) FROM hotel.reservations r JOIN hotel.reservation_details d ON d.reservation_id=r.id
                WHERE d.room_type_id=?
                """;
        String holdRequests = """
                SELECT count(*) FROM hotel.booking_hold_requests h
                JOIN hotel.reservation_details d ON d.reservation_id=h.reservation_id WHERE d.room_type_id=?
                """;
        var policy = applicationContext.getBean(com.Hotel.Module_DatPhong.service.StayLengthPolicy.class);
        Object original = ReflectionTestUtils.getField(policy, "maxNights");
        try {
            // Created under the old limit (90 nights).
            String key = UUID.randomUUID().toString();
            String code = hold.apply(key, nights.apply(60)).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertEquals(1, jdbc.queryForObject(reservations, Integer.class, type));
            assertEquals(1, jdbc.queryForObject(holdRequests, Integer.class, type));

            ReflectionTestUtils.setField(policy, "maxNights", 30);
            // Same customer, key and payload: the stored code, although 60 nights now exceeds the limit.
            hold.apply(key, nights.apply(60)).andExpect(status().isCreated()).andExpect(content().string(code));
            // Same key, other payload: 409 whether the other payload fits the new limit or not.
            hold.apply(key, nights.apply(10)).andExpect(status().isConflict())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Idempotency-Key")));
            hold.apply(key, nights.apply(61)).andExpect(status().isConflict())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Idempotency-Key")));
            assertEquals(1, jdbc.queryForObject(reservations, Integer.class, type));
            assertEquals(1, jdbc.queryForObject(holdRequests, Integer.class, type));

            // A new hold is limited before pricing, inventory and any write; its key is not stored.
            String fresh = UUID.randomUUID().toString();
            hold.apply(fresh, nights.apply(31)).andExpect(status().isBadRequest())
                    .andExpect(content().string(org.hamcrest.Matchers.startsWith("STAY_TOO_LONG")));
            assertEquals(1, jdbc.queryForObject(reservations, Integer.class, type));
            assertEquals(1, jdbc.queryForObject(holdRequests, Integer.class, type));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM hotel.booking_hold_requests WHERE request_id=?::uuid",
                    Integer.class, fresh));
            String second = hold.apply(fresh, nights.apply(30)).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertNotEquals(code, second);
            assertEquals(2, jdbc.queryForObject(reservations, Integer.class, type));
            assertEquals(2, jdbc.queryForObject(holdRequests, Integer.class, type));
            assertEquals(60, jdbc.queryForObject("SELECT check_out - check_in FROM hotel.reservations WHERE code=?",
                    Integer.class, code));
        } finally {
            ReflectionTestUtils.setField(policy, "maxNights", original);
        }
    }
}
