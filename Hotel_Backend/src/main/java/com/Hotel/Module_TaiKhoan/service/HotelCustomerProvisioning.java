package com.Hotel.Module_TaiKhoan.service;

import java.util.Locale;

import com.Hotel.Module_TaiKhoan.entity.Account;
import com.Hotel.Module_Report.security.ReportIdentityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Creates the booking profile (hotel.users GUEST + customers + link) for a verified guest account.
 * An e-mail that already belongs to hotel.users is never linked automatically: the account is
 * recorded in hotel.account_link_reviews for an explicit, verified link.
 */
@Service
public class HotelCustomerProvisioning {
    public enum Result {
        /** Canonical booking is off, or the account is not a guest account. */
        NOT_REQUIRED,
        CREATED,
        ALREADY_LINKED,
        /** Verified, but booking needs an explicit link to an existing hotel.users row. */
        PENDING_REVIEW;

        public String bookingProfile() {
            return switch (this) {
                case CREATED, ALREADY_LINKED -> "READY";
                case PENDING_REVIEW -> "PENDING_REVIEW";
                case NOT_REQUIRED -> "NOT_REQUIRED";
            };
        }
    }

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public HotelCustomerProvisioning(JdbcTemplate jdbc,
                                     @Value("${hotel.booking.canonical-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    // Called from the transaction that verifies or creates the local account.
    public Result provision(Account account) {
        if (!enabled || !"ROLE_USER".equals(account.getVaiTro())) {
            return Result.NOT_REQUIRED;
        }
        Integer linked = jdbc.queryForObject(
                "SELECT count(*) FROM hotel.account_user_links WHERE account_id = ?", Integer.class, account.getId());
        if (linked != null && linked > 0) {
            return Result.ALREADY_LINKED;
        }
        String email = account.getEmail().toLowerCase(Locale.ROOT);
        int created = jdbc.update("""
                WITH new_user AS (
                    INSERT INTO hotel.users(email, full_name, phone, password_hash, role_id, email_verified_at)
                    SELECT ?, ?, ?, ?, r.id, now()
                    FROM hotel.roles r
                    WHERE r.code = 'GUEST'
                        AND NOT EXISTS (SELECT 1 FROM hotel.users u WHERE lower(u.email) = ?)
                    ON CONFLICT (email) DO NOTHING
                    RETURNING id, email, full_name, phone
                ), new_customer AS (
                    INSERT INTO hotel.customers(user_id, full_name, email, phone)
                    SELECT id, full_name, email, phone FROM new_user
                )
                INSERT INTO hotel.account_user_links(account_id, user_id)
                SELECT ?, id FROM new_user
                """, email, account.getHoTen(), account.getSoDienThoai(), ReportIdentityService.JWT_ONLY,
                email, account.getId());
        if (created > 0) {
            return Result.CREATED;
        }
        Boolean emailTaken = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM hotel.users WHERE lower(email) = ?)", Boolean.class, email);
        jdbc.update("""
                INSERT INTO hotel.account_link_reviews(account_id, reason)
                VALUES (?, ?) ON CONFLICT (account_id) DO NOTHING
                """, account.getId(), Boolean.TRUE.equals(emailTaken)
                        ? "EMAIL_EXISTS_IN_HOTEL_USERS" : "GUEST_PROFILE_NOT_CREATED");
        return Result.PENDING_REVIEW;
    }
}
