package com.Hotel.Module_CheckInCheckOut.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CheckOutResultDTO(long reservationId, String reservationStatus, long invoiceId, String invoiceStatus,
                     BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal refundedAmount,
                     Instant actualCheckOutTime, String roomStatus, String cleaningStatus) {}
