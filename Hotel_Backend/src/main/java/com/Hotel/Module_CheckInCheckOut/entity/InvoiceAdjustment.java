package com.Hotel.Module_CheckInCheckOut.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InvoiceAdjustment(long id, long invoiceId, long reservationId, String number,
        String reason, BigDecimal total, BigDecimal adjustedTotal, long createdBy, Instant issuedAt,
        List<InvoiceAdjustmentLine> lines) {}
