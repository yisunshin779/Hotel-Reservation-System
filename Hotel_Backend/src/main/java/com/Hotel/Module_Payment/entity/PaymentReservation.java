package com.Hotel.Module_Payment.entity;
import java.math.BigDecimal;
import java.time.Instant;
public record PaymentReservation(long id, String code, long branchId, Long ownerUserId,
        String status, Instant holdExpiresAt, BigDecimal total) {}
