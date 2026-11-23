package com.Hotel.Module_CheckInCheckOut.dto;

import com.Hotel.Module_CheckInCheckOut.entity.InvoiceAdjustmentLine;
import java.util.List;

public record InvoiceAdjustmentRequestDTO(String reason, String idempotencyKey,
                                          List<InvoiceAdjustmentLine> lines) {}
