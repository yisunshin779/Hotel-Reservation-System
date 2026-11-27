package com.Hotel.Module_CheckInCheckOut.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record Reservation(long id, long branchId, long customerId, String code, ReservationStatus status,
        LocalDate checkIn, LocalDate checkOut, String timezone, BigDecimal roomAmount,
        BigDecimal discount, BigDecimal quotedTotal, boolean prepaid,
        Instant actualCheckIn, Instant actualCheckOut, long version) {}
