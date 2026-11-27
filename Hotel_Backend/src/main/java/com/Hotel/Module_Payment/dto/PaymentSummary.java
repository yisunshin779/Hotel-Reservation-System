package com.Hotel.Module_Payment.dto;
import com.Hotel.Module_Payment.entity.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record PaymentSummary(long reservationId, String code, String reservationStatus,
        Instant holdExpiresAt, Instant serverTime, boolean expired, BigDecimal totalAmount,
        BigDecimal depositAmount, BigDecimal paidAmount, BigDecimal remainingAmount,
        IntentView intent, String confirmationStatus) {
    public record IntentView(UUID id, long paymentId, PaymentMethod method, PaymentPlan plan,
            String state, String paymentStatus, BigDecimal amount, String obligationKey, String qrContent) {}
}
