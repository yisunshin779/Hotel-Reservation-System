package com.Hotel.Module_CheckInCheckOut.dto;

import com.Hotel.Module_CheckInCheckOut.entity.InvoiceLine;
import java.math.BigDecimal;

public record InvoiceLineDTO(String type, String description, BigDecimal quantity,
                             BigDecimal unitPrice, BigDecimal amount) {
    public static InvoiceLineDTO from(InvoiceLine line) {
        return new InvoiceLineDTO(line.type(), line.description(), line.quantity(), line.unitPrice(), line.amount());
    }
}
