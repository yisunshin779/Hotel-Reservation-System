package com.Hotel.Module_Payment.repository;

import com.Hotel.Module_Payment.entity.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
    private final JdbcTemplate jdbc;
    public PaymentRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static Instant instant(ResultSet rs,String field) throws SQLException {
        var value=rs.getTimestamp(field); return value==null?null:value.toInstant();
    }
    public Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(); }
    public long resolveReservationId(String reference) {
        try {
            return Long.parseLong(reference);
        } catch (NumberFormatException ex) {
            UUID code;
            try {
                code = UUID.fromString(reference);
            } catch (IllegalArgumentException invalid) {
                throw new PaymentException(400, "INVALID_REQUEST", "Reservation reference is invalid");
            }
            return jdbc.query("SELECT id FROM hotel.reservations WHERE code = ?",
                    (rs, row) -> rs.getLong("id"), code.toString()).stream().findFirst()
                    .orElseThrow(() -> new PaymentException(404, "RESERVATION_NOT_FOUND", "Reservation not found"));
        }
    }
    public PaymentReservation reservation(long id,boolean lock) {
        var rows=jdbc.query("""
            SELECT r.*,c.user_id FROM hotel.reservations r
            JOIN hotel.customers c ON c.id=r.customer_id AND c.deleted_at IS NULL
            JOIN hotel.branches b ON b.id=r.branch_id AND b.active AND b.deleted_at IS NULL
            WHERE r.id=?
            """+(lock?" FOR UPDATE OF r":""),(rs,n)->new PaymentReservation(rs.getLong("id"),rs.getString("code"),
                rs.getLong("branch_id"),rs.getObject("user_id",Long.class),rs.getString("status"),
                instant(rs,"hold_expires_at"),rs.getBigDecimal("quoted_total")),id);
        if(rows.isEmpty()) throw new PaymentException(404,"RESERVATION_NOT_FOUND","Reservation not found");
        return rows.get(0);
    }
    private final RowMapper<PaymentIntent> mapper=(rs,n)->new PaymentIntent(rs.getObject("id",UUID.class),
        rs.getLong("payment_id"),rs.getLong("reservation_id"),PaymentPlan.valueOf(rs.getString("payment_plan")),
        rs.getString("state"),rs.getString("method"),rs.getString("status"),rs.getBigDecimal("amount"),
        rs.getString("obligation_key"),instant(rs,"paid_at"));
    private static final String INTENTS="""
        SELECT i.*,p.method,p.status,p.amount,p.obligation_key,p.paid_at
        FROM hotel.payment_intents i JOIN hotel.payments p ON p.id=i.payment_id
        """;
    // Payment IDs are allocated after the reservation lock: transaction start times can arrive out of order.
    public Optional<PaymentIntent> latest(long id) {
        return jdbc.query(INTENTS+" WHERE i.reservation_id=? ORDER BY i.payment_id DESC LIMIT 1",mapper,id).stream().findFirst();
    }
    public PaymentIntent intent(long reservation,UUID id) {
        return jdbc.query(INTENTS+" WHERE i.reservation_id=? AND i.id=?",mapper,reservation,id).stream().findFirst()
            .orElseThrow(()->new PaymentException(404,"PAYMENT_NOT_FOUND","Payment selection not found"));
    }
    public PaymentIntent create(long id,PaymentMethod method,PaymentPlan plan,BigDecimal amount) {
        UUID intentId=UUID.randomUUID();
        Long paymentId=jdbc.queryForObject("""
            INSERT INTO hotel.payments(reservation_id,amount,method,status,obligation_key)
            VALUES(?,?,?::hotel.payment_method,?::hotel.payment_status,?) RETURNING id
            """,Long.class,id,amount,method==PaymentMethod.QR?"MOCK_QR":"CASH",method==PaymentMethod.QR?"PENDING":"UNPAID","UC08:"+intentId);
        jdbc.update("INSERT INTO hotel.payment_intents(id,reservation_id,payment_id,payment_plan,state) VALUES(?,?,?,?,'ACTIVE')",
            intentId,id,paymentId,plan.name());
        return intent(id,intentId);
    }
    public void cancel(PaymentIntent intent) {
        jdbc.update("UPDATE hotel.payment_intents SET state='CANCELLED' WHERE id=?",intent.id());
        jdbc.update("UPDATE hotel.payments SET status='FAILED' WHERE id=? AND status='PENDING'",intent.paymentId());
    }
    public void cancelHold(long id) {
        jdbc.query("SELECT hotel.cancel_reservation(?, 'CUSTOMER_CANCELLED')", rs -> {}, id);
    }
    public void complete(PaymentIntent intent) { jdbc.update("UPDATE hotel.payment_intents SET state='COMPLETED' WHERE id=?",intent.id()); }
    public void confirm(long id,boolean cash) {
        // The existing stay validator uses a non-null hold to identify advance-payment bookings.
        jdbc.update("UPDATE hotel.reservations SET status='CONFIRMED',hold_expires_at=CASE WHEN ? THEN NULL ELSE hold_expires_at END,updated_at=clock_timestamp(),version=version+1 WHERE id=?",cash,id);
        // Due now in PostgreSQL time (V12 column): the dispatcher compares queue times with clock_timestamp().
        jdbc.update("INSERT INTO hotel.booking_confirmation_requests(reservation_id,next_attempt_at) VALUES(?,clock_timestamp()) ON CONFLICT DO NOTHING",id);
    }
    public BigDecimal paid(long id) { return jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM hotel.payments WHERE reservation_id=? AND status='PAID'",BigDecimal.class,id); }
    public BigDecimal refunded(long id) { return jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM hotel.refunds WHERE reservation_id=? AND status='DONE'",BigDecimal.class,id); }
    public String notification(long id) {
        return jdbc.queryForList("SELECT status FROM hotel.booking_confirmation_requests WHERE reservation_id=?",String.class,id).stream().findFirst().orElse("NONE");
    }
}
