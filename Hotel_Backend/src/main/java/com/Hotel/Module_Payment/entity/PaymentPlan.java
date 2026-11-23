package com.Hotel.Module_Payment.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

public enum PaymentPlan {
    FULL, DEPOSIT;
    public BigDecimal amount(BigDecimal total) {
        return (this==FULL ? total : total.multiply(new BigDecimal("0.30"))).setScale(2,RoundingMode.HALF_UP);
    }
}
