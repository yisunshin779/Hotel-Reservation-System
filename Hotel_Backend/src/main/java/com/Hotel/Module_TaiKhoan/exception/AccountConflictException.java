package com.Hotel.Module_TaiKhoan.exception;

/** A valid request that conflicts with the current account state. Mapped to HTTP 409. */
public class AccountConflictException extends RuntimeException {
    private final String code;

    public AccountConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
