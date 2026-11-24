package com.Hotel.Module_DatPhong.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Longest stay accepted by canonical search and hold ({@code hotel.booking.max-nights},
 * HOTEL_BOOKING_MAX_NIGHTS). The repository specifies no business limit, so 90 nights is a technical
 * default. Both entry points call {@link #require} before any inventory query, pricing or hold write.
 */
@Component
public class StayLengthPolicy {
    /** Stable prefix of the 400 response body, like the BOOKING_PROFILE_* codes. */
    public static final String CODE = "STAY_TOO_LONG";

    private final int maxNights;

    public StayLengthPolicy(@Value("${hotel.booking.max-nights:90}") int maxNights) {
        if (maxNights < 1) {
            throw new IllegalArgumentException("hotel.booking.max-nights must be at least 1");
        }
        this.maxNights = maxNights;
    }

    public int maxNights() {
        return maxNights;
    }

    /** Callers have already checked that both dates are present and check-in is before check-out. */
    public void require(LocalDate checkIn, LocalDate checkOut) {
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights > maxNights) {
            throw new IllegalArgumentException(CODE + ": a stay can last at most " + maxNights
                    + " nights (requested " + nights + ")");
        }
    }
}
