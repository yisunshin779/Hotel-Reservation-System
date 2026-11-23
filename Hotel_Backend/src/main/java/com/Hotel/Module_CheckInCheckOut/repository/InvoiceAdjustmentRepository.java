package com.Hotel.Module_CheckInCheckOut.repository;

import com.Hotel.Module_CheckInCheckOut.entity.InvoiceAdjustment;
import com.Hotel.Module_CheckInCheckOut.entity.InvoiceAdjustmentLine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InvoiceAdjustmentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public InvoiceAdjustmentRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
    }
    // Global request-key lock is acquired before the reservation lock on every write.
    public void lockKey(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,1903))", rs -> {
            while (rs.next()) { /* acquire transaction-scoped lock */ }
        }, key);
    }
    public Optional<InvoiceAdjustment> byKey(String key) {
        return jdbc.query("SELECT * FROM hotel.invoice_adjustments WHERE idempotency_key=?",
                this::map, key).stream().findFirst();
    }
    public List<InvoiceAdjustment> list(long invoiceId) {
        return jdbc.query("SELECT * FROM hotel.invoice_adjustments WHERE invoice_id=? ORDER BY id",
                this::map, invoiceId);
    }
    public InvoiceAdjustment insert(long invoiceId, String number, String reason, String key,
                                    long actorId, Instant now, List<InvoiceAdjustmentLine> lines) {
        // The trigger derives and checks the signed total and cumulative adjusted total.
        return jdbc.queryForObject("""
                INSERT INTO hotel.invoice_adjustments(invoice_id,adjustment_number,reason,idempotency_key,
                    created_by,issued_at,lines) VALUES(?,?,?,?,?,?,?::jsonb) RETURNING *
                """, this::map, invoiceId, number, reason, key, actorId, Timestamp.from(now), encode(lines));
    }
    private String encode(List<InvoiceAdjustmentLine> lines) {
        try { return json.writeValueAsString(lines); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode adjustment", e); }
    }
    private InvoiceAdjustment map(ResultSet rs, int row) throws SQLException {
        try {
            var lines = List.of(json.readValue(rs.getString("lines"), InvoiceAdjustmentLine[].class));
            return new InvoiceAdjustment(rs.getLong("id"), rs.getLong("invoice_id"), rs.getLong("reservation_id"),
                    rs.getString("adjustment_number"), rs.getString("reason"), rs.getBigDecimal("total"),
                    rs.getBigDecimal("adjusted_total"), rs.getLong("created_by"), rs.getTimestamp("issued_at").toInstant(), lines);
        } catch (JsonProcessingException e) { throw new SQLException("Invalid adjustment snapshot", e); }
    }
}
