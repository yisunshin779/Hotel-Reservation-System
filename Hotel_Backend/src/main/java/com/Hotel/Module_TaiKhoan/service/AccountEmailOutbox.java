package com.Hotel.Module_TaiKhoan.service;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Queues Account e-mails in the caller's transaction (hotel.account_email_outbox, V13).
 * A row stores identifiers only; the dispatcher resolves recipient, link and token when sending.
 * {@code next_attempt_at} and {@code created_at} take the column default {@code clock_timestamp()}:
 * the dispatcher compares them with PostgreSQL time as well.
 */
@Component
public class AccountEmailOutbox {
    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD, PASSWORD_CHANGED }

    private final JdbcTemplate jdbc;

    public AccountEmailOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(UUID accountId, Purpose purpose, UUID tokenId) {
        jdbc.update("""
                INSERT INTO hotel.account_email_outbox(id, account_id, purpose, token_id, next_attempt_at)
                VALUES (?, ?, ?, ?, clock_timestamp())
                """, UUID.randomUUID(), accountId, purpose.name(), tokenId);
    }
}
