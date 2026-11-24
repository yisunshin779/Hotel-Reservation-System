package com.Hotel.Module_TaiKhoan.exception;

/** The caller is not (or no longer) allowed to perform the change. Mapped to HTTP 403 with a stable code. */
public class AccountAccessDeniedException extends RuntimeException {
    private final String code;

    public AccountAccessDeniedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
