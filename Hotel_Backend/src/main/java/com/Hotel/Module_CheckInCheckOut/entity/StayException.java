package com.Hotel.Module_CheckInCheckOut.entity;

public final class StayException extends RuntimeException {
    private final int status;
    private final String code;
    public StayException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    public static StayException conflict(String code, String message) {
        return new StayException(409, code, message);
    }
}
