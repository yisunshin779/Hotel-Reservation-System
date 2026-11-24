package com.Hotel.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.Hotel.Module_TaiKhoan.service.AuthService;
import com.Hotel.Module_TaiKhoan.service.EmailService;
import com.Hotel.config.SchemaCapabilities;

/**
 * Sends queued e-mails from three transactional outboxes:
 * hotel.account_email_outbox (UC01/UC03), hotel.booking_confirmation_requests (UC08) and
 * hotel.invoice_delivery_requests (UC19).
 *
 * <p><b>Time.</b> Every queue timestamp is PostgreSQL time ({@code clock_timestamp()}): the due time written
 * by the producers, the due check, the claim lease and its renewal, the retry backoff, {@code sent_at} and the
 * queue age. Several backend instances therefore agree on a lease whatever their own clocks say. The
 * application {@link Clock} is used only for verification and reset link deadlines, which
 * {@code verification_token} stores in application time and the Auth API checks with the same rule.
 *
 * <p><b>Claims.</b> Rows are claimed one at a time, right before they are processed, in a committed statement
 * ({@code FOR UPDATE SKIP LOCKED}) that increments {@code attempts}; the attempt number identifies the claim
 * and only ever grows. Immediately before SMTP the lease is renewed only if this claim still owns the row and
 * its lease has not expired; otherwise the message is not sent. SMTP runs outside any database transaction
 * with finite timeouts, and the lease must be longer than those timeouts together. Every result is stored
 * only by the claim that produced it ({@code attempts} unchanged, still PENDING), so a worker that lost its
 * lease cannot overwrite a newer attempt or a terminal state.
 *
 * <p><b>max-attempts</b> is the number of claims a row may receive. A claim ends in SENT, a retry, FAILED, or
 * not at all (lease lost before SMTP, or the worker stopped); each one counts. A row is never claimed again
 * once it has used every attempt: a failed final attempt ends FAILED with its error, and a final attempt
 * whose lease ran out without a result is set to FAILED/{@value #ATTEMPTS_EXHAUSTED} by the next worker.
 * A final attempt that still holds its lease finishes normally.
 *
 * <p>Delivery is at-least-once: if SMTP accepts a message and the result is not stored (process stopped,
 * lease overrun), the row can be claimed again and the message sent twice.
 */
@Component
public class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    static final String ATTEMPTS_EXHAUSTED = "ATTEMPTS_EXHAUSTED";
    static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";

    /** Message ready to send, or a terminal reason not to send it. */
    sealed interface Outcome permits Send, Skip, SkipWhileLocked {}
    record Send(Runnable sender) implements Outcome {}
    record Skip(String reason) implements Outcome {}
    /** Verification e-mail of a BI_KHOA account: failed only if the account is still locked when stored. */
    record SkipWhileLocked(UUID account) implements Outcome {}

    private record Source(String table, String key, String createdColumn, Function<Object, Outcome> render) {}
    private record Claim(Object key, int attempts) {}

    private final JdbcTemplate jdbc;
    private final EmailService email;
    private final SchemaCapabilities schema;
    private final Clock clock;
    private final List<Source> sources;
    private final double leaseSeconds;
    private final int maxAttempts;
    private final double maxAgeSeconds;
    private final int batchSize;
    /** Test hook, called with (table, key) after a claimed message is rendered and before its result is stored. */
    private volatile BiConsumer<String, Object> afterRender = (table, key) -> {};

    public NotificationDispatcher(JdbcTemplate jdbc, EmailService email, SchemaCapabilities schema, Clock clock,
                                  @Value("${hotel.notifications.lease-seconds:300}") long leaseSeconds,
                                  @Value("${hotel.notifications.max-attempts:5}") int maxAttempts,
                                  @Value("${hotel.notifications.max-age-hours:48}") long maxAgeHours,
                                  @Value("${hotel.notifications.batch-size:20}") int batchSize,
                                  @Value("${spring.mail.properties.mail.smtp.connectiontimeout:10000}") long connectTimeoutMs,
                                  @Value("${spring.mail.properties.mail.smtp.timeout:10000}") long readTimeoutMs,
                                  @Value("${spring.mail.properties.mail.smtp.writetimeout:10000}") long writeTimeoutMs) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0 || writeTimeoutMs <= 0) {
            throw new IllegalArgumentException("SMTP connect, read and write timeouts must be finite and positive");
        }
        if (leaseSeconds * 1000 <= connectTimeoutMs + readTimeoutMs + writeTimeoutMs) {
            throw new IllegalArgumentException("hotel.notifications.lease-seconds (" + leaseSeconds
                    + ") must be longer than the SMTP connect, read and write timeouts together");
        }
        if (maxAttempts < 1 || batchSize < 1) {
            throw new IllegalArgumentException("Notification max-attempts and batch-size must be at least 1");
        }
        this.jdbc = jdbc;
        this.email = email;
        this.schema = schema;
        this.clock = clock;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
        this.maxAgeSeconds = Duration.ofHours(maxAgeHours).toSeconds();
        this.batchSize = batchSize;
        this.sources = List.of(
                new Source("account_email_outbox", "id", "created_at", this::renderAccount),
                new Source("booking_confirmation_requests", "reservation_id", "created_at", this::renderBooking),
                new Source("invoice_delivery_requests", "invoice_id", "requested_at", this::renderInvoice));
    }

    /** Processes up to batch-size due rows of every available outbox. Returns the number of rows updated. */
    public int dispatchOnce() {
        int handled = 0;
        for (Source source : sources) {
            if (!schema.hasTable(source.table()) || !schema.hasColumn(source.table(), "attempts")) {
                continue;
            }
            handled += exhaust(source);
            for (int i = 0; i < batchSize; i++) {
                Claim claim = claimNext(source);
                if (claim == null) {
                    break;
                }
                handled += process(source, claim) ? 1 : 0;
            }
        }
        return handled;
    }

    /**
     * Rows that used every attempt and hold no live lease (the final worker stopped or lost its lease) become
     * FAILED. Row locks serialize concurrent workers: the second one re-checks a FAILED row and skips it.
     */
    private int exhaust(Source s) {
        int failed = jdbc.update("""
                UPDATE hotel.%s SET status = 'FAILED', locked_until = NULL, last_error = ?
                WHERE status = 'PENDING' AND attempts >= ?
                    AND (locked_until IS NULL OR locked_until < clock_timestamp())
                """.formatted(s.table()), ATTEMPTS_EXHAUSTED, maxAttempts);
        if (failed > 0) {
            log.warn("{} {} message(s) used every attempt without a result and were marked FAILED", failed, s.table());
        }
        return failed;
    }

    private Claim claimNext(Source s) {
        return jdbc.query("""
                UPDATE hotel.%1$s o SET locked_until = clock_timestamp() + make_interval(secs => ?), attempts = o.attempts + 1
                WHERE o.%2$s = (
                    SELECT %2$s FROM hotel.%1$s
                    WHERE status = 'PENDING' AND attempts < ? AND next_attempt_at <= clock_timestamp()
                        AND (locked_until IS NULL OR locked_until < clock_timestamp())
                    ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING o.%2$s, o.attempts
                """.formatted(s.table(), s.key()),
                (rs, row) -> new Claim(rs.getObject(1), rs.getInt(2)), leaseSeconds, maxAttempts)
                .stream().findFirst().orElse(null);
    }

    /** Extends the lease when this claim still owns the row and its lease has not expired (database time). */
    private boolean renewLease(Source s, Claim claim) {
        return jdbc.update("""
                UPDATE hotel.%1$s SET locked_until = clock_timestamp() + make_interval(secs => ?)
                WHERE %2$s = ? AND attempts = ? AND status = 'PENDING' AND locked_until > clock_timestamp()
                """.formatted(s.table(), s.key()), leaseSeconds, claim.key(), claim.attempts()) > 0;
    }

    private boolean ownsLiveLease(Source s, Claim claim) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM hotel.%1$s
                               WHERE %2$s = ? AND attempts = ? AND status = 'PENDING' AND locked_until > clock_timestamp())
                """.formatted(s.table(), s.key()), Boolean.class, claim.key(), claim.attempts()));
    }

    private boolean process(Source s, Claim claim) {
        Outcome outcome;
        for (int round = 0; ; round++) {
            try {
                outcome = tooOld(s, claim.key()) ? new Skip("EXPIRED_BEFORE_DISPATCH") : s.render().apply(claim.key());
            } catch (RuntimeException ex) {
                log.warn("Could not prepare {} message: {}", s.table(), ex.getClass().getSimpleName());
                return retryOrFail(s, claim, "RENDER_ERROR");
            }
            afterRender.accept(s.table(), claim.key());
            if (outcome instanceof Skip skip) {
                return finish(s, claim, "FAILED", skip.reason());
            }
            if (outcome instanceof SkipWhileLocked locked) {
                if (failWhileLocked(claim, locked.account())) {
                    return true;
                }
                // The account was unlocked after rendering: render again under the same claim, once.
                if (round == 0 && ownsLiveLease(s, claim)) {
                    continue;
                }
                return false;
            }
            break;
        }
        if (!renewLease(s, claim)) {
            // Lease expired or another worker claimed the row: sending now could duplicate its message.
            log.warn("Lease of {} message lost before sending on attempt {}; left for the next claim",
                    s.table(), claim.attempts());
            return false;
        }
        try {
            ((Send) outcome).sender().run();
        } catch (RuntimeException ex) {
            // Exception class only: messages can contain addresses or server responses.
            log.warn("Sending {} message failed on attempt {}: {}", s.table(), claim.attempts(),
                    ex.getClass().getSimpleName());
            return retryOrFail(s, claim, "SEND_ERROR");
        }
        return finish(s, claim, "SENT", null);
    }

    /**
     * Stores FAILED/ACCOUNT_LOCKED only while the account is still BI_KHOA. The account row is share-locked
     * first, then the outbox row is updated: the same order (account, then outbox) as the unlock, which locks
     * the account, its token and its outbox rows. An unlock that commits first makes this update a no-op;
     * one that commits later sees the FAILED row and queues the e-mail again.
     */
    private boolean failWhileLocked(Claim claim, UUID account) {
        return jdbc.update("""
                WITH locked AS (SELECT 1 FROM hotel.account WHERE id = ? AND trang_thai = 'BI_KHOA' FOR SHARE)
                UPDATE hotel.account_email_outbox SET status = 'FAILED', locked_until = NULL, last_error = ?
                WHERE id = ? AND attempts = ? AND status = 'PENDING' AND EXISTS (SELECT 1 FROM locked)
                """, account, ACCOUNT_LOCKED, claim.key(), claim.attempts()) > 0;
    }

    private boolean tooOld(Source s, Object key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT %s + make_interval(secs => ?) < clock_timestamp() FROM hotel.%s WHERE %s = ?
                """.formatted(s.createdColumn(), s.table(), s.key()), Boolean.class, maxAgeSeconds, key));
    }

    private boolean retryOrFail(Source s, Claim claim, String reason) {
        if (claim.attempts() >= maxAttempts) {
            return finish(s, claim, "FAILED", reason);
        }
        long backoff = Math.min(3600, 60L << Math.min(claim.attempts() - 1, 6));
        int updated = jdbc.update("""
                UPDATE hotel.%1$s SET locked_until = NULL, last_error = ?,
                       next_attempt_at = clock_timestamp() + make_interval(secs => ?)
                WHERE %2$s = ? AND attempts = ? AND status = 'PENDING'
                """.formatted(s.table(), s.key()), reason, (double) backoff, claim.key(), claim.attempts());
        return updated > 0;
    }

    private boolean finish(Source s, Claim claim, String status, String reason) {
        int updated = jdbc.update("""
                UPDATE hotel.%1$s SET status = ?, locked_until = NULL, last_error = ?,
                       sent_at = CASE WHEN ? = 'SENT' THEN clock_timestamp() ELSE sent_at END
                WHERE %2$s = ? AND attempts = ? AND status = 'PENDING'
                """.formatted(s.table(), s.key()), status, reason, status, claim.key(), claim.attempts());
        return updated > 0;
    }

    private Outcome renderAccount(Object id) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT o.purpose, o.account_id, a.email, a.trang_thai, t.token, t.ngay_het_han, t.loai_token
                FROM hotel.account_email_outbox o
                JOIN hotel.account a ON a.id = o.account_id
                LEFT JOIN hotel.verification_token t ON t.id = o.token_id
                WHERE o.id = ?
                """, id);
        String to = (String) row.get("email");
        String status = (String) row.get("trang_thai");
        String token = (String) row.get("token");
        LocalDateTime deadline = row.get("ngay_het_han") instanceof Timestamp ts ? ts.toLocalDateTime() : null;
        return switch ((String) row.get("purpose")) {
            // A link the verification API would reject is not sent (OC-01.03 issues a new one). A link held by
            // an Admin lock is queued again by the unlock while its token is valid (AccountService).
            case "VERIFY_EMAIL" -> "BI_KHOA".equals(status) ? new SkipWhileLocked((UUID) row.get("account_id"))
                    : !"CHO_XAC_THUC".equals(status) ? new Skip("ACCOUNT_NOT_PENDING")
                    : token == null ? new Skip("TOKEN_UNAVAILABLE")
                    : expired(deadline) ? new Skip("TOKEN_EXPIRED")
                    : send(() -> email.sendVerificationEmail(to, token));
            case "RESET_PASSWORD" -> !"DA_KICH_HOAT".equals(status) ? new Skip("ACCOUNT_NOT_ACTIVE")
                    : token == null ? new Skip("TOKEN_UNAVAILABLE")
                    : expired(deadline) ? new Skip("TOKEN_EXPIRED")
                    : send(() -> email.sendResetPasswordEmail(to, token, minutesLeft(deadline)));
            case "PASSWORD_CHANGED" -> send(() -> email.sendPasswordChangedEmail(to));
            default -> new Skip("UNKNOWN_PURPOSE");
        };
    }

    private Outcome renderBooking(Object reservationId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT r.code, r.status::text AS status, r.check_in, r.check_out, r.quoted_total, b.name AS branch,
                       coalesce(nullif(c.email, ''), u.email) AS email
                FROM hotel.reservations r
                JOIN hotel.branches b ON b.id = r.branch_id
                JOIN hotel.customers c ON c.id = r.customer_id
                LEFT JOIN hotel.users u ON u.id = c.user_id
                WHERE r.id = ?
                """, reservationId);
        String to = (String) row.get("email");
        if (to == null || to.isBlank()) {
            return new Skip("NO_RECIPIENT");
        }
        if ("CANCELLED".equals(row.get("status"))) {
            return new Skip("RESERVATION_CANCELLED");
        }
        String subject = "Xác nhận đặt phòng " + row.get("code");
        String text = "Đơn đặt phòng " + row.get("code") + " tại " + row.get("branch") + " đã được xác nhận.\n"
                + "Nhận phòng: " + row.get("check_in") + "\nTrả phòng: " + row.get("check_out")
                + "\nTổng tiền: " + row.get("quoted_total");
        return send(() -> email.send(to, subject, text));
    }

    private Outcome renderInvoice(Object invoiceId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT i.invoice_number, i.total, i.status::text AS status, i.closed_at, r.code,
                       coalesce(nullif(c.email, ''), u.email) AS email
                FROM hotel.invoices i
                JOIN hotel.reservations r ON r.id = i.reservation_id
                JOIN hotel.customers c ON c.id = r.customer_id
                LEFT JOIN hotel.users u ON u.id = c.user_id
                WHERE i.id = ?
                """, invoiceId);
        String to = (String) row.get("email");
        if (to == null || to.isBlank()) {
            return new Skip("NO_RECIPIENT");
        }
        if ("DRAFT".equals(row.get("status"))) {
            return new Skip("INVOICE_NOT_CLOSED");
        }
        String subject = "Hóa đơn " + row.get("invoice_number");
        String text = "Hóa đơn " + row.get("invoice_number") + " của đơn " + row.get("code") + ".\n"
                + "Tổng thanh toán: " + row.get("total") + "\nThời điểm chốt: " + row.get("closed_at");
        return send(() -> email.send(to, subject, text));
    }

    private static Send send(Runnable sender) {
        return new Send(sender);
    }

    // verification_token stores server-local time, as written by AuthService.
    private LocalDateTime localNow() {
        return LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
    }

    /** Same rule as the verification and reset APIs ({@link AuthService#isExpired}). */
    private boolean expired(LocalDateTime deadline) {
        return AuthService.isExpired(deadline, localNow());
    }

    /** Validity left at send time, rounded up to whole minutes, so the e-mail states the real deadline. */
    private long minutesLeft(LocalDateTime deadline) {
        long seconds = Duration.between(localNow(), deadline).getSeconds();
        return Math.max(1, (seconds + 59) / 60);
    }
}
