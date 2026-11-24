package com.Hotel.Module_DatPhong.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.Hotel.Module_DatPhong.dto.BookingRequestDTO;
import com.Hotel.Module_DatPhong.dto.SearchRequestDTO;
import com.Hotel.Module_DatPhong.dto.RoomAvailabilityDTO;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CanonicalBookingService {
    private static final String ROOM_PREFIX = "hotel:";
    private final JdbcTemplate jdbc;
    private final StayLengthPolicy stayLength;

    public CanonicalBookingService(JdbcTemplate jdbc, StayLengthPolicy stayLength) {
        this.jdbc = jdbc;
        this.stayLength = stayLength;
    }

    /**
     * OC-05.01. A type is returned when every night has at least the rooms needed and those rooms fit
     * the guests. Without {@code soLuongPhong} the minimum room count that fits the guests is used.
     * {@code tongGiaKyO} uses hotel.nightly_rate for each night, the same rate make_reservation quotes.
     */
    public List<RoomAvailabilityDTO> search(SearchRequestDTO request) {
        long guests = (long) request.soNguoiLon() + request.soTreEm();
        if (request.soTreEm() < 0) {
            throw new IllegalArgumentException("Guest count is invalid");
        }
        stayLength.require(request.ngayNhan(), request.ngayTra());
        return jdbc.query("""
                SELECT rt.id, rt.name, rt.base_price, rt.capacity, stock.rooms, stock.nights, stock.per_room, stock.prices, q.wanted
                FROM hotel.room_types rt
                JOIN hotel.branches b ON b.id = rt.branch_id AND b.active AND b.deleted_at IS NULL
                CROSS JOIN LATERAL (
                    SELECT min(hotel.available_count(rt.id, d::date)) AS rooms,
                           count(*)::integer AS nights,
                           sum(hotel.nightly_rate(rt.id, d::date)) AS per_room,
                           array_agg(hotel.nightly_rate(rt.id, d::date) ORDER BY d) AS prices
                    FROM generate_series(cast(? AS date)::timestamp, cast(? AS date) - 1, interval '1 day') d
                ) stock
                CROSS JOIN LATERAL (
                    SELECT coalesce(cast(? AS integer), ceil(cast(? AS numeric) / rt.capacity)::integer) AS wanted
                ) q
                WHERE rt.active AND rt.deleted_at IS NULL AND q.wanted >= 1
                    AND stock.rooms >= q.wanted AND q.wanted::bigint * rt.capacity >= ?
                ORDER BY rt.id
                """, (rs, row) -> new RoomAvailabilityDTO(
                        ROOM_PREFIX + rs.getLong("id"),
                        rs.getString("name"),
                        rs.getInt("rooms"),
                        rs.getBigDecimal("base_price"),
                        rs.getInt("capacity"),
                        rs.getInt("wanted"),
                        rs.getInt("nights"),
                        rs.getBigDecimal("per_room").multiply(BigDecimal.valueOf(rs.getInt("wanted"))),
                        nightPrices(request.ngayNhan(), (Object[]) rs.getArray("prices").getArray())),
                request.ngayNhan(), request.ngayTra(), request.soLuongPhong(), guests, guests);
    }

    /**
     * OC-07.03 hold. Order: the key and the payload structure are validated; the customer row is locked; a
     * stored request with the same key is replayed (same payload: the stored code; other payload: 409);
     * only a new hold is then checked against the stay-length limit, before pricing, inventory and any write.
     * A hold created under an earlier, higher limit therefore stays replayable after the limit is lowered.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String hold(BookingRequestDTO request, String accountEmail, UUID requestId) {
        if (requestId == null) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        int guests = request.soKhach() == null ? 1 : request.soKhach();
        if (request.ngayNhan() == null || request.ngayTra() == null
                || !request.ngayNhan().isBefore(request.ngayTra())
                || request.soLuongPhong() <= 0 || guests <= 0) {
            throw new IllegalArgumentException("Dates, room quantity or guest count are invalid");
        }
        long type = canonicalRoomType(request.maLoaiPhong());
        long customer = jdbc.query("""
                SELECT c.id FROM hotel.customers c
                JOIN hotel.users u ON u.id = c.user_id
                JOIN hotel.account_user_links link ON link.user_id = u.id
                JOIN hotel.account a ON a.id = link.account_id
                WHERE a.email = ? AND a.trang_thai = 'DA_KICH_HOAT'
                    AND u.active AND u.deleted_at IS NULL AND c.deleted_at IS NULL
                    AND (u.locked_until IS NULL OR u.locked_until <= now())
                FOR NO KEY UPDATE OF c
                """, (rs, row) -> rs.getLong("id"), accountEmail).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(profileProblem(accountEmail)));

        var replay = jdbc.query("""
                SELECT r.code, r.check_in, r.check_out, r.guest_count, d.room_type_id, d.quantity
                FROM hotel.booking_hold_requests h
                JOIN hotel.reservations r ON r.id = h.reservation_id
                JOIN hotel.reservation_details d ON d.reservation_id = r.id
                WHERE h.customer_id = ? AND h.request_id = ?
                """, (rs, row) -> {
            if (rs.getLong("room_type_id") != type || rs.getInt("quantity") != request.soLuongPhong()
                    || rs.getInt("guest_count") != guests
                    || !rs.getDate("check_in").toLocalDate().equals(request.ngayNhan())
                    || !rs.getDate("check_out").toLocalDate().equals(request.ngayTra())) {
                throw new IllegalStateException("Idempotency-Key has already been used for another request");
            }
            return rs.getString("code");
        }, customer, requestId);
        if (!replay.isEmpty()) {
            return replay.get(0);
        }
        // New hold only: before pricing, the inventory check in make_reservation and the hold-request row.
        stayLength.require(request.ngayNhan(), request.ngayTra());

        long branch = jdbc.query("""
                SELECT rt.branch_id FROM hotel.room_types rt
                JOIN hotel.branches b ON b.id = rt.branch_id AND b.active AND b.deleted_at IS NULL
                WHERE rt.id = ? AND rt.active AND rt.deleted_at IS NULL AND rt.capacity::bigint * ? >= ?
                """, (rs, row) -> rs.getLong("branch_id"), type, request.soLuongPhong(), guests)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Room type is unavailable or capacity is too low"));

        String code = UUID.randomUUID().toString();
        long reservation;
        try {
            reservation = jdbc.queryForObject("""
                    SELECT hotel.make_reservation(?, ?, ?, ?,
                        jsonb_build_array(jsonb_build_object('room_type_id', ?::bigint, 'quantity', ?::integer)), ?)
                    """, Long.class, customer, branch, request.ngayNhan(), request.ngayTra(),
                    type, request.soLuongPhong(), code);
        } catch (DataAccessException ex) {
            if (ex.getMostSpecificCause() instanceof SQLException sql && "P0001".equals(sql.getSQLState())) {
                throw new IllegalStateException("The reservation could not be held with the selected dates or inventory", ex);
            }
            throw ex;
        }
        jdbc.update("UPDATE hotel.reservations SET guest_count = ? WHERE id = ?", guests, reservation);
        jdbc.update("""
                INSERT INTO hotel.booking_hold_requests(customer_id, request_id, reservation_id)
                VALUES (?, ?, ?)
                """, customer, requestId, reservation);
        return code;
    }

    private static List<RoomAvailabilityDTO.NightPrice> nightPrices(java.time.LocalDate from, Object[] prices) {
        var nights = new java.util.ArrayList<RoomAvailabilityDTO.NightPrice>(prices.length);
        for (int i = 0; i < prices.length; i++) {
            nights.add(new RoomAvailabilityDTO.NightPrice(from.plusDays(i), (BigDecimal) prices[i]));
        }
        return nights;
    }

    /** Error text starts with a stable code the client can act on. */
    private String profileProblem(String accountEmail) {
        Boolean pending = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM hotel.account_link_reviews r JOIN hotel.account a ON a.id = r.account_id
                               WHERE a.email = ? AND r.resolved_at IS NULL)
                """, Boolean.class, accountEmail);
        return Boolean.TRUE.equals(pending)
                ? "BOOKING_PROFILE_PENDING_REVIEW: the booking profile is waiting for support to confirm it"
                : "BOOKING_PROFILE_REQUIRED: a verified hotel customer link is required";
    }

    private long canonicalRoomType(String code) {
        try {
            if (code != null && code.startsWith(ROOM_PREFIX)) {
                long id = Long.parseLong(code.substring(ROOM_PREFIX.length()));
                if (id > 0) {
                    return id;
                }
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Use a room type from the current search results", ex);
        }
        throw new IllegalArgumentException("Use a room type from the current search results");
    }
}
