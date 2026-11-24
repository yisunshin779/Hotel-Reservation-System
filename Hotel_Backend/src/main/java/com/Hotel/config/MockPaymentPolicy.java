package com.Hotel.config;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single switch for simulated payment methods (MOCK_QR, MOCK_CARD) on every write path: UC08 QR
 * selection, QR confirmation and cancellation, checkout payments and checkout refunds.
 *
 * <p>{@code hotel.payment.mock-methods-enabled} defaults to {@code true} in development, tests and
 * report-demo, and to {@code false} in the {@code production} profile (HOTEL_PAYMENT_MOCK_ENABLED).
 * When disabled, a simulated write is refused with {@link #DISABLED_CODE} before any payment, refund,
 * reservation status, audit or outbox row is written. Cash flows and reads of historical simulated
 * payments are not affected.
 */
@Component
public class MockPaymentPolicy {
    public static final String DISABLED_CODE = "MOCK_PAYMENT_DISABLED";
    public static final String DISABLED_MESSAGE =
            "Simulated payment methods are disabled in this environment; use cash or a real payment provider";
    private static final Set<String> MOCK_METHODS = Set.of("MOCK_QR", "MOCK_CARD");

    private final boolean enabled;

    public MockPaymentPolicy(@Value("${hotel.payment.mock-methods-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public static boolean isMock(String method) {
        return method != null && MOCK_METHODS.contains(method);
    }

    /** True when {@code method} may be written: a non-simulated method, or simulation is enabled. */
    public boolean allows(String method) {
        return enabled || !isMock(method);
    }
}
