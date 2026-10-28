package com.Hotel.Module_CheckInCheckOut.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Invoice(long id, long reservationId, String number, String status, Instant issuedAt,
                      BigDecimal total, List<InvoiceLine> lines) {}
