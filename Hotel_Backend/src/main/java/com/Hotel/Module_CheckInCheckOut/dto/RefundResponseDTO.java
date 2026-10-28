package com.Hotel.Module_CheckInCheckOut.dto;

import com.Hotel.Module_CheckInCheckOut.entity.Refund;
import java.math.BigDecimal;
import java.time.Instant;

public record RefundResponseDTO(long id, long reservationId, long paymentId, BigDecimal amount,
                                String method, String status, String key, String reason, Instant completedAt) {
    public static RefundResponseDTO from(Refund refund) {
        return new RefundResponseDTO(refund.id(), refund.reservationId(), refund.paymentId(), refund.amount(),
                refund.method(), refund.status(), refund.key(), refund.reason(), refund.completedAt());
    }
}
