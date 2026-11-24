package com.Hotel.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import com.Hotel.Module_CheckInCheckOut.repository.JdbcStayRepository;
import com.Hotel.Module_Payment.repository.PaymentRepository;
import com.Hotel.Module_TaiKhoan.service.AccountEmailOutbox;
import com.Hotel.Module_TaiKhoan.service.EmailService;
import com.Hotel.config.SchemaCapabilities;

/**
 * Dispatcher behaviour with a fake mail sender on PostgreSQL. Queue times (due time, lease, backoff, sent_at,
 * queue age) are PostgreSQL time, so the workers below run with deliberately wrong application clocks and the
 * fixtures move timestamps relative to {@code clock_timestamp()}; no test sleeps to let time pass.
 */
class NotificationDispatcherTest {
    private static EmbeddedPostgres pg;
    private static JdbcTemplate jdbc;
    private static final long LEASE_SECONDS = 60;

    private JavaMailSender mail;

    @BeforeAll
    static void schema() throws Exception {
        pg = EmbeddedPostgres.builder().setPort(0).start();
        jdbc = new JdbcTemplate(pg.getPostgresDatabase());
        for (String file : List.of("01_schema.sql", "02_operations.sql", "05_checkin_checkout.sql",
                "06_stay_contracts.sql", "07_payment.sql", "08_invoice_adjustments.sql")) {
            jdbc.execute(Files.readString(Path.of("../Hotel_Database/sql", file)));
        }
        // V12 adds the outbox worker columns; V9 and V13 add hotel.account and the Account outbox.
        for (String file : List.of("V9__legacy_jpa_tables.sql", "V12__payment_expiry_and_outbox.sql",
                "V13__account_sessions_audit_outbox.sql")) {
            try (var sql = NotificationDispatcherTest.class.getResourceAsStream("/db/migration/" + file)) {
                jdbc.execute(new String(sql.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @AfterAll
    static void stop() throws Exception {
        pg.close();
    }

    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE hotel.branches,hotel.roles,hotel.customers,hotel.promotions,hotel.account RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO hotel.branches(name,address,city) VALUES('One','One','One')");
        jdbc.update("INSERT INTO hotel.roles(code,name) VALUES('GUEST','Guest')");
        jdbc.update("INSERT INTO hotel.room_types(branch_id,name,capacity,base_price) VALUES(1,'Standard',2,100)");
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO hotel.users(email,full_name,password_hash,role_id) VALUES(?,?,'!',1)",
                    "guest" + i + "@test", "Guest " + i);
            jdbc.update("INSERT INTO hotel.customers(user_id,full_name) VALUES(?,?)", (long) i, "Guest " + i);
            jdbc.update("INSERT INTO hotel.rooms(branch_id,room_type_id,room_number) VALUES(1,1,?)", "10" + i);
        }
        mail = mock(JavaMailSender.class);
    }

    private NotificationDispatcher dispatcher(Clock appClock, int maxAttempts, int batchSize) {
        return new NotificationDispatcher(jdbc, new EmailService(mail), new SchemaCapabilities(jdbc), appClock,
                LEASE_SECONDS, maxAttempts, 48, batchSize, 1000, 1000, 1000);
    }

    private NotificationDispatcher dispatcher(int maxAttempts, int batchSize) {
        return dispatcher(Clock.systemUTC(), maxAttempts, batchSize);
    }

    /** Runs {@code hook} with (table, key) after each claimed message is rendered, before its result is stored. */
    private static NotificationDispatcher afterRender(NotificationDispatcher worker, BiConsumer<String, Object> hook) {
        ReflectionTestUtils.setField(worker, "afterRender", hook);
        return worker;
    }

    private static Clock skewed(Duration offset) {
        return Clock.offset(Clock.systemUTC(), offset);
    }

    private Instant dbNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    /** Reservation with a pending confirmation request; returns its code. */
    private String confirmation(long customer) {
        String code = "N-" + customer + "-" + System.nanoTime();
        long id = jdbc.queryForObject("""
                SELECT hotel.make_reservation(?,1,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                    (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+1,'[{"room_type_id":1,"quantity":1}]'::jsonb,?)
                """, Long.class, customer, code);
        jdbc.update("INSERT INTO hotel.booking_confirmation_requests(reservation_id) VALUES(?)", id);
        return code;
    }

    private static final String BY_CODE = " WHERE reservation_id=(SELECT id FROM hotel.reservations WHERE code=?)";

    private Map<String, Object> row(String code) {
        return jdbc.queryForMap("""
                SELECT status, attempts, last_error, next_attempt_at, locked_until, sent_at
                FROM hotel.booking_confirmation_requests""" + BY_CODE, code);
    }

    /** The claim's lease runs out (database time), e.g. after a stalled worker. */
    private void expireLease(String code) {
        jdbc.update("UPDATE hotel.booking_confirmation_requests SET locked_until=clock_timestamp()-interval '1 second'" + BY_CODE, code);
    }

    /** The retry backoff has elapsed (database time). */
    private void makeDue(String code) {
        jdbc.update("UPDATE hotel.booking_confirmation_requests SET next_attempt_at=clock_timestamp()-interval '1 second'" + BY_CODE, code);
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    private static void assertWithin(Instant value, Instant from, Instant to, String what) {
        assertFalse(value.isBefore(from) || value.isAfter(to), what + " = " + value + " not in [" + from + ", " + to + "]");
    }

    // ---- Retry, batch and lease rules ----

    @Test
    void smtpFailureIsRetriedWithDatabaseBackoffThenFailsAtMaxAttempts() {
        String code = confirmation(1);
        doThrow(new MailSendException("smtp down")).when(mail).send(any(SimpleMailMessage.class));
        var worker = dispatcher(3, 20);

        Instant before = dbNow();
        assertEquals(1, worker.dispatchOnce());
        Instant after = dbNow();
        var first = row(code);
        assertEquals("PENDING", first.get("status"));
        assertEquals(1, first.get("attempts"));
        assertEquals("SEND_ERROR", first.get("last_error"));
        assertNull(first.get("locked_until"));
        assertWithin(instant(first.get("next_attempt_at")), before.plusSeconds(60), after.plusSeconds(60), "first backoff");

        assertEquals(0, worker.dispatchOnce(), "not due before the backoff");
        makeDue(code);
        before = dbNow();
        assertEquals(1, worker.dispatchOnce());
        after = dbNow();
        assertEquals(2, row(code).get("attempts"));
        assertWithin(instant(row(code).get("next_attempt_at")), before.plusSeconds(120), after.plusSeconds(120), "second backoff");

        makeDue(code);
        assertEquals(1, worker.dispatchOnce());
        assertEquals("FAILED", row(code).get("status"));
        assertEquals("SEND_ERROR", row(code).get("last_error"));
        assertEquals(3, row(code).get("attempts"));
        makeDue(code);
        assertEquals(0, worker.dispatchOnce());
        verify(mail, times(3)).send(any(SimpleMailMessage.class));
    }

    @Test
    void renderErrorsCountAgainstMaxAttemptsLikeSmtpErrors() {
        String code = confirmation(1);
        var worker = dispatcher(2, 1);
        jdbc.execute("ALTER TABLE hotel.branches RENAME COLUMN name TO name_hidden");
        try {
            assertEquals(1, worker.dispatchOnce());
            assertEquals("PENDING", row(code).get("status"));
            assertEquals("RENDER_ERROR", row(code).get("last_error"));
            makeDue(code);
            assertEquals(1, worker.dispatchOnce());
            assertEquals("FAILED", row(code).get("status"));
            assertEquals("RENDER_ERROR", row(code).get("last_error"));
            assertEquals(2, row(code).get("attempts"));
            makeDue(code);
            assertEquals(0, worker.dispatchOnce());
        } finally {
            jdbc.execute("ALTER TABLE hotel.branches RENAME COLUMN name_hidden TO name");
        }
        verifyNoInteractions(mail);
    }

    @Test
    void batchClaimsOneMessageAtATimeUnderALiveLeaseAndNeverTwice() {
        List<String> codes = List.of(confirmation(1), confirmation(2), confirmation(3), confirmation(4));
        var worker = dispatcher(5, 20);
        var otherWorker = dispatcher(skewed(Duration.ofHours(1)), 5, 1);
        List<String> sent = new ArrayList<>();
        boolean[] otherRan = {false};
        doAnswer(call -> {
            SimpleMailMessage message = call.getArgument(0);
            String code = codes.stream().filter(c -> message.getSubject().contains(c)).findFirst().orElseThrow();
            sent.add(code);
            assertTrue(instant(row(code).get("locked_until")).isAfter(dbNow()), "lease expired before sending " + code);
            if (!otherRan[0]) {
                otherRan[0] = true;
                // Rows behind the first one are not claimed yet: each lease starts right before its own message.
                assertEquals(3, codes.stream().filter(c -> (int) row(c).get("attempts") == 0).count());
                // A second worker polls while the first one is inside SMTP for its first message.
                assertEquals(1, otherWorker.dispatchOnce());
            }
            return null;
        }).when(mail).send(any(SimpleMailMessage.class));

        assertEquals(3, worker.dispatchOnce(), "the second worker took one message");
        assertEquals(4, sent.size(), sent.toString());
        assertEquals(4, sent.stream().distinct().count(), "a message was sent twice: " + sent);
        for (String code : codes) {
            assertEquals("SENT", row(code).get("status"), code);
            assertEquals(1, row(code).get("attempts"), code);
        }
    }

    @Test
    void messageWhoseLeaseExpiredBeforeSendingIsNotSentByThatWorker() {
        String code = confirmation(1);
        boolean[] stalled = {false};
        var worker = afterRender(dispatcher(5, 1), (table, key) -> {
            if (!stalled[0]) {
                stalled[0] = true;
                expireLease(code); // the worker stalls between rendering and SMTP longer than its lease
            }
        });
        assertEquals(0, worker.dispatchOnce());
        verifyNoInteractions(mail);
        var untouched = row(code);
        assertEquals("PENDING", untouched.get("status"));
        assertEquals(1, untouched.get("attempts"));
        assertNull(untouched.get("last_error"));

        assertEquals(1, worker.dispatchOnce());
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
        assertEquals("SENT", row(code).get("status"));
        assertEquals(2, row(code).get("attempts"));
    }

    @Test
    void leaseMustOutlastTheSmtpTimeouts() {
        var error = assertThrows(IllegalArgumentException.class, () -> new NotificationDispatcher(jdbc,
                new EmailService(mail), new SchemaCapabilities(jdbc), Clock.systemUTC(), 30, 5, 48, 20, 10_000, 10_000, 10_000));
        assertTrue(error.getMessage().contains("lease-seconds"));
        assertThrows(IllegalArgumentException.class, () -> new NotificationDispatcher(jdbc,
                new EmailService(mail), new SchemaCapabilities(jdbc), Clock.systemUTC(), 300, 5, 48, 20, 0, 10_000, 10_000));
        assertDoesNotThrow(() -> dispatcher(5, 20));
    }

    // ---- Queue timestamps come from PostgreSQL, whatever the application clocks say ----

    @Test
    void dispatchersWithSkewedApplicationClocksShareTheDatabaseLease() {
        String code = confirmation(1);
        var behind = dispatcher(skewed(Duration.ofMinutes(-10)), 5, 1);
        var ahead = dispatcher(skewed(Duration.ofHours(1)), 5, 1);
        int[] stolen = {-1};
        doAnswer(call -> {
            // While the first worker is inside SMTP its lease is live in database time; the other worker,
            // whose clock is an hour ahead, must not take the row.
            assertTrue(instant(row(code).get("locked_until")).isAfter(dbNow()));
            stolen[0] = ahead.dispatchOnce();
            return null;
        }).when(mail).send(any(SimpleMailMessage.class));

        Instant before = dbNow();
        assertEquals(1, behind.dispatchOnce(), "a due row is claimed although this worker's clock is 10 minutes behind");
        Instant after = dbNow();
        assertEquals(0, stolen[0]);
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
        var sent = row(code);
        assertEquals("SENT", sent.get("status"));
        assertEquals(1, sent.get("attempts"));
        assertWithin(instant(sent.get("sent_at")), before, after, "sent_at");
    }

    @Test
    void expiredDatabaseLeaseIsReclaimedAndTheOldWorkersLateResultIsFenced() {
        String code = confirmation(1);
        var first = dispatcher(skewed(Duration.ofMinutes(-30)), 5, 1);
        var second = dispatcher(skewed(Duration.ofMinutes(30)), 5, 1);
        int[] calls = {0};
        doAnswer(call -> {
            if (++calls[0] == 1) {
                // The first worker overran its lease (database time); the second one claims attempt 2 and fails.
                expireLease(code);
                assertEquals(1, second.dispatchOnce());
                assertEquals(2, row(code).get("attempts"));
                return null; // the first worker's SMTP call then succeeds, late
            }
            throw new MailSendException("smtp down");
        }).when(mail).send(any(SimpleMailMessage.class));

        assertEquals(0, first.dispatchOnce(), "the late result of attempt 1 is not stored");
        var state = row(code);
        assertEquals("PENDING", state.get("status"));
        assertEquals(2, state.get("attempts"));
        assertEquals("SEND_ERROR", state.get("last_error"));
        assertNull(state.get("locked_until"));
        assertNull(state.get("sent_at"));
        verify(mail, times(2)).send(any(SimpleMailMessage.class)); // at-least-once: SMTP accepted attempt 1
    }

    @Test
    void retryBackoffAndQueueAgeUseTheDatabaseClock() {
        String code = confirmation(1);
        doThrow(new MailSendException("smtp down")).when(mail).send(any(SimpleMailMessage.class));
        Instant before = dbNow();
        assertEquals(1, dispatcher(skewed(Duration.ofDays(-1)), 5, 1).dispatchOnce());
        Instant after = dbNow();
        assertWithin(instant(row(code).get("next_attempt_at")), before.plusSeconds(60), after.plusSeconds(60), "backoff");
        assertEquals(0, dispatcher(skewed(Duration.ofDays(1)), 5, 1).dispatchOnce(), "not due before the backoff in database time");

        // Older than max-age (48 h) in database time: dropped even by a worker whose clock is days behind.
        reset(mail);
        String old = confirmation(2);
        jdbc.update("UPDATE hotel.booking_confirmation_requests SET created_at=clock_timestamp()-interval '49 hours'" + BY_CODE, old);
        assertEquals(1, dispatcher(skewed(Duration.ofDays(-3)), 5, 1).dispatchOnce());
        assertEquals("FAILED", row(old).get("status"));
        assertEquals("EXPIRED_BEFORE_DISPATCH", row(old).get("last_error"));
        // A fresh row is sent even by a worker whose clock is days ahead.
        String fresh = confirmation(3);
        assertEquals(1, dispatcher(skewed(Duration.ofDays(3)), 5, 1).dispatchOnce());
        assertEquals("SENT", row(fresh).get("status"));
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void producersStampTheDueTimeWithTheDatabaseClock() {
        Instant before = dbNow();
        long reservation = jdbc.queryForObject("""
                SELECT hotel.make_reservation(1,1,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                    (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+1,'[{"room_type_id":1,"quantity":1}]'::jsonb,'P-1')
                """, Long.class);
        new PaymentRepository(jdbc).confirm(reservation, true);
        long invoice = jdbc.queryForObject("""
                INSERT INTO hotel.invoices(reservation_id,invoice_number,room_amount,service_amount,total)
                VALUES(?,'INV-P-1',100,0,100) RETURNING id
                """, Long.class, reservation);
        new JdbcStayRepository(jdbc).requestInvoiceDelivery(invoice);
        UUID account = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO hotel.account(id,ho_ten,email,auth_provider,vai_tro,trang_thai,thoi_gian_tao)
                VALUES(?,'Producer','producer@test','LOCAL','ROLE_USER','DA_KICH_HOAT',localtimestamp)
                """, account);
        new AccountEmailOutbox(jdbc).enqueue(account, AccountEmailOutbox.Purpose.PASSWORD_CHANGED, null);
        Instant after = dbNow();
        for (String sql : List.of(
                "SELECT next_attempt_at FROM hotel.booking_confirmation_requests WHERE reservation_id=" + reservation,
                "SELECT next_attempt_at FROM hotel.invoice_delivery_requests WHERE invoice_id=" + invoice,
                "SELECT requested_at FROM hotel.invoice_delivery_requests WHERE invoice_id=" + invoice,
                "SELECT next_attempt_at FROM hotel.account_email_outbox WHERE account_id='" + account + "'",
                "SELECT created_at FROM hotel.account_email_outbox WHERE account_id='" + account + "'")) {
            assertWithin(jdbc.queryForObject(sql, Timestamp.class).toInstant(), before, after, sql);
        }
    }

    // ---- max-attempts bounds every claim, including one whose worker disappeared ----

    /** Thrown from SMTP to model a worker that stops (process killed) after claiming its row. */
    static final class VanishedWorker extends Error {
        VanishedWorker() { super("worker stopped"); }
    }

    @Test
    void workerThatVanishesOnTheFinalAttemptLeavesTheRowFailedAndUnclaimed() {
        String code = confirmation(1);
        doThrow(new MailSendException("smtp down")).doThrow(new VanishedWorker()).doNothing()
                .when(mail).send(any(SimpleMailMessage.class));
        var crashing = dispatcher(2, 1);
        var survivor = dispatcher(skewed(Duration.ofHours(1)), 2, 1);

        assertEquals(1, crashing.dispatchOnce());
        makeDue(code);
        assertThrows(VanishedWorker.class, crashing::dispatchOnce);
        assertEquals("PENDING", row(code).get("status"));
        assertEquals(2, row(code).get("attempts"));

        assertEquals(0, survivor.dispatchOnce(), "the lease of the final attempt is still live");
        assertEquals("PENDING", row(code).get("status"));
        expireLease(code);
        assertEquals(1, survivor.dispatchOnce());
        var failed = row(code);
        assertEquals("FAILED", failed.get("status"));
        assertEquals("ATTEMPTS_EXHAUSTED", failed.get("last_error"));
        assertEquals(2, failed.get("attempts"));
        assertNull(failed.get("locked_until"));

        makeDue(code);
        assertEquals(0, survivor.dispatchOnce());
        assertEquals(2, row(code).get("attempts"), "a FAILED row is never claimed again");
        verify(mail, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void finalAttemptWhoseLeaseIsLostBeforeSendingEndsFailed() {
        String code = confirmation(1);
        var worker = afterRender(dispatcher(1, 1), (table, key) -> expireLease(code));
        assertEquals(0, worker.dispatchOnce());
        assertEquals("PENDING", row(code).get("status"));
        assertEquals(1, worker.dispatchOnce());
        assertEquals("FAILED", row(code).get("status"));
        assertEquals("ATTEMPTS_EXHAUSTED", row(code).get("last_error"));
        assertEquals(1, row(code).get("attempts"));
        verifyNoInteractions(mail);
    }

    @Test
    void twoWorkersOnAnExhaustedRowFailItOnceWithoutSending() throws Exception {
        String code = confirmation(1);
        jdbc.update("""
                UPDATE hotel.booking_confirmation_requests SET attempts=3, last_error='SEND_ERROR',
                       next_attempt_at=clock_timestamp()-interval '1 minute', locked_until=clock_timestamp()-interval '1 second'
                """ + BY_CODE, code);
        var pool = Executors.newFixedThreadPool(2);
        try (var holder = pg.getPostgresDatabase().getConnection()) {
            // Both workers reach the row while another transaction holds it, then race once it is released.
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement(
                    "SELECT 1 FROM hotel.booking_confirmation_requests" + BY_CODE + " FOR UPDATE")) {
                lock.setString(1, code);
                lock.executeQuery().close();
            }
            var first = pool.submit(() -> dispatcher(3, 5).dispatchOnce());
            var second = pool.submit(() -> dispatcher(skewed(Duration.ofHours(2)), 3, 5).dispatchOnce());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                    """, Integer.class) < 2) {
                assertTrue(System.nanoTime() < deadline, "both workers should wait for the exhausted row");
                Thread.sleep(10);
            }
            holder.commit();
            int handled = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);
            assertEquals(1, handled, "exactly one worker finalizes the row");
        } finally {
            pool.shutdownNow();
        }
        var failed = row(code);
        assertEquals("FAILED", failed.get("status"));
        assertEquals("ATTEMPTS_EXHAUSTED", failed.get("last_error"));
        assertEquals(3, failed.get("attempts"));
        verifyNoInteractions(mail);
    }

    @Test
    void finalAttemptThatSendsWithinItsLeaseCompletesNormally() {
        String code = confirmation(1);
        var worker = dispatcher(2, 1);
        var other = dispatcher(skewed(Duration.ofHours(1)), 2, 1);
        int[] calls = {0};
        doAnswer(call -> {
            if (++calls[0] == 1) {
                throw new MailSendException("smtp down");
            }
            // The final attempt is in progress under a live lease: nobody fails or claims it meanwhile.
            assertEquals(0, other.dispatchOnce());
            assertEquals("PENDING", row(code).get("status"));
            return null;
        }).when(mail).send(any(SimpleMailMessage.class));
        assertEquals(1, worker.dispatchOnce());
        makeDue(code);
        assertEquals(1, worker.dispatchOnce());
        var sent = row(code);
        assertEquals("SENT", sent.get("status"));
        assertEquals(2, sent.get("attempts"));
        assertNull(sent.get("last_error"));
        verify(mail, times(2)).send(any(SimpleMailMessage.class));
    }
}
