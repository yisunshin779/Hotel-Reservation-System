package com.Hotel.Module_CheckInCheckOut.dto;

import com.Hotel.Module_CheckInCheckOut.entity.Invoice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InvoiceResponseDTO(long id, long reservationId, String number, String status,
                                 Instant issuedAt, BigDecimal total, List<InvoiceLineDTO> lines) {
    public static InvoiceResponseDTO from(Invoice invoice) {
        return new InvoiceResponseDTO(invoice.id(), invoice.reservationId(), invoice.number(), invoice.status(),
                invoice.issuedAt(), invoice.total(), invoice.lines().stream().map(InvoiceLineDTO::from).toList());
    }
}
