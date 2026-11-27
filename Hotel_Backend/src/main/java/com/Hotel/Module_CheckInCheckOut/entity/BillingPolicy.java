package com.Hotel.Module_CheckInCheckOut.entity;

import java.math.BigDecimal;
import java.time.LocalTime;

public record BillingPolicy(BigDecimal taxRate, BigDecimal lateFee, LocalTime checkoutTime) {}
