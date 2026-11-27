package com.Hotel.Module_Payment.entity;

public class PaymentException extends RuntimeException {
    private final int status;
    private final String code;
    public PaymentException(int status, String code, String message) {
        super(message); this.status=status; this.code=code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static PaymentException conflict(String code, String message) { return new PaymentException(409,code,message); }
}
