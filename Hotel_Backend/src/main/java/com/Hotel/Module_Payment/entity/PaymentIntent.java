package com.Hotel.Module_Payment.entity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record PaymentIntent(UUID id, long paymentId, long reservationId, PaymentPlan plan,
        String state, String method, String paymentStatus, BigDecimal amount, String obligationKey, Instant paidAt) {}
