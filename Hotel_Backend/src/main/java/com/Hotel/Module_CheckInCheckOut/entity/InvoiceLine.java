package com.Hotel.Module_CheckInCheckOut.entity;

import java.math.BigDecimal;

public record InvoiceLine(String type, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {}
