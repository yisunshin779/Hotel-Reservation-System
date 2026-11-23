package com.Hotel.Module_CheckInCheckOut.entity;

import java.math.BigDecimal;

/** Signed changes to the original invoice; negative amounts reduce its total. */
public record InvoiceAdjustmentLine(String type, String description, BigDecimal amount) {}
