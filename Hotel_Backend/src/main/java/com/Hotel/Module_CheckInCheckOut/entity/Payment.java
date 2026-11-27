package com.Hotel.Module_CheckInCheckOut.entity;

import java.math.BigDecimal;

public record Payment(long id, long reservationId, BigDecimal amount, String method, String status) {}
