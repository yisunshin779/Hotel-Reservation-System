package com.Hotel.Module_CheckInCheckOut.entity;

import java.math.BigDecimal;
import java.time.Instant;

public record Refund(long id, long reservationId, long paymentId, BigDecimal amount,
                     String method, String status, String key, String reason, Instant completedAt) {}
