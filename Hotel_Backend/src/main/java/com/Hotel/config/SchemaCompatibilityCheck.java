package com.Hotel.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails start-up when the enabled features need schema objects that are missing. Runs after every
 * singleton, including the Flyway initializer, so it sees the schema Flyway produced. It reads the
 * catalog instead of trusting HOTEL_FLYWAY_ENABLED or flyway_schema_history, because an operator may
 * have migrated by CLI, skipped a version, or re-run an older SQL script over a migrated function.
 *
 * <p>The schema mode comes from configuration ({@code hotel.schema.mode}, HOTEL_SCHEMA_MODE), never from
 * the tables that happen to exist:
 * <ul>
 * <li>{@code operational} (default): every table Payment and Checkout use must exist ({@link #OPERATIONAL_TABLES}).</li>
 * <li>{@code report-only} (set by the {@code report-demo} profile): a database built for reports only
 * (01_schema.sql plus report data) may lack the Payment, stay-contract and outbox tables. Objects that are
 * present are still checked, and canonical booking (which leads into Payment) is refused.</li>
 * </ul>
 */
@Component
public class SchemaCompatibilityCheck implements SmartInitializingSingleton {
    public static final String OPERATIONAL = "operational";
    public static final String REPORT_ONLY = "report-only";

    /** Columns NotificationDispatcher needs to claim a lease, retry and fence its final update (V12). */
    static final List<String> WORKER_COLUMNS =
            List.of("attempts", "next_attempt_at", "locked_until", "last_error", "sent_at");
    private static final String V12 = " (apply Flyway V12__payment_expiry_and_outbox.sql)";
    private static final String BASELINE = "Flyway V8__hotel_operations_baseline.sql (or Hotel_Database/sql/";

    /**
     * Tables read or written by Payment (PaymentRepository) and Checkout (JdbcStayRepository checkout, billing,
     * refund and invoice delivery), with the delivered script that creates each one. Flyway V8 contains all of
     * them; on a database migrated by CLI the operator applies the script named in the message.
     */
    static final Map<String, String> OPERATIONAL_TABLES = operationalTables();

    private static Map<String, String> operationalTables() {
        Map<String, String> tables = new LinkedHashMap<>();
        for (String table : List.of("reservations", "reservation_details", "room_assignments", "payments", "refunds",
                "invoices", "invoice_lines", "extra_charges")) {
            tables.put(table, "01_schema.sql");
        }
        for (String table : List.of("assignment_nightly_rates", "stay_inventory_movements", "invoice_delivery_requests")) {
            tables.put(table, "06_stay_contracts.sql");
        }
        for (String table : List.of("payment_intents", "booking_confirmation_requests")) {
            tables.put(table, "07_payment.sql");
        }
        return java.util.Collections.unmodifiableMap(tables);
    }

    private final SchemaCapabilities schema;
    private final String mode;
    private final boolean canonical;
    private final boolean basicAuth;

    public SchemaCompatibilityCheck(SchemaCapabilities schema,
                                    @Value("${hotel.schema.mode:" + OPERATIONAL + "}") String mode,
                                    @Value("${hotel.booking.canonical-enabled:false}") boolean canonical,
                                    @Value("${hotel.security.basic-auth-enabled:true}") boolean basicAuth) {
        this.schema = schema;
        this.mode = mode;
        this.canonical = canonical;
        this.basicAuth = basicAuth;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = problems(schema, mode, canonical, basicAuth);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Backend configuration is not compatible with the database schema: "
                    + String.join("; ", problems));
        }
    }

    public static List<String> problems(SchemaCapabilities schema, String mode, boolean canonical, boolean basicAuth) {
        List<String> problems = new ArrayList<>();
        if (!OPERATIONAL.equals(mode) && !REPORT_ONLY.equals(mode)) {
            problems.add("hotel.schema.mode must be '" + OPERATIONAL + "' or '" + REPORT_ONLY + "' (was '" + mode + "')");
            return problems;
        }
        if (OPERATIONAL.equals(mode)) {
            OPERATIONAL_TABLES.forEach((table, script) -> {
                if (!schema.hasTable(table)) {
                    problems.add("hotel." + table + " is missing: Payment and Checkout need it (apply " + BASELINE + script
                            + "), then Flyway V12__payment_expiry_and_outbox.sql)");
                }
            });
        } else if (canonical) {
            problems.add("hotel.schema.mode=report-only cannot be combined with HOTEL_CANONICAL_BOOKING_ENABLED=true:"
                    + " canonical holds continue in Payment, which report-only databases do not provide");
        }
        paymentAndOutbox(schema, problems);
        if (canonical) {
            for (String table : List.of("account", "account_user_links", "booking_hold_requests")) {
                if (!schema.hasTable(table)) {
                    problems.add("HOTEL_CANONICAL_BOOKING_ENABLED=true needs hotel." + table
                            + " (apply Flyway V11, after V9)");
                }
            }
            if (!schema.hasTable("account_link_reviews")) {
                problems.add("HOTEL_CANONICAL_BOOKING_ENABLED=true needs hotel.account_link_reviews (apply Flyway V13)");
            }
        }
        if (schema.hasTable("account")) {
            if (!schema.hasColumn("account", "token_version")) {
                problems.add("hotel.account.token_version is missing (apply Flyway V13)");
            }
            for (String table : List.of("account_audit_logs", "account_email_outbox")) {
                if (!schema.hasTable(table)) {
                    problems.add("hotel." + table + " is missing (apply Flyway V13)");
                }
            }
        }
        if (!basicAuth && !canonical) {
            problems.add("HOTEL_BASIC_AUTH_ENABLED=false needs HOTEL_CANONICAL_BOOKING_ENABLED=true:"
                    + " business APIs would have no authentication mechanism");
        }
        return problems;
    }

    /**
     * #1 and #14. With Payment installed (07_payment.sql), cancellation and hold expiry must release ACTIVE
     * payment selections; every delivery outbox needs the worker columns. Each object is checked directly,
     * so a schema that has V11 and V13 but skipped V12 is still rejected. A missing table is reported above
     * (operational mode) and is not reported a second time as missing columns.
     */
    private static void paymentAndOutbox(SchemaCapabilities schema, List<String> problems) {
        if (schema.hasTable("payment_intents")) {
            if (!schema.hasFunction("release_payment_selections(bigint)")) {
                problems.add("function hotel.release_payment_selections(bigint) is missing, so a cancelled or expired"
                        + " hold keeps its ACTIVE payment selection" + V12);
            } else if (!schema.functionCalls("cancel_reservation(bigint,text)", "release_payment_selections")) {
                problems.add("function hotel.cancel_reservation(bigint,text) does not call"
                        + " hotel.release_payment_selections (pre-V12 body, e.g. after re-running 02_operations.sql)" + V12);
            }
            if (!schema.functionCalls("expire_holds()", "cancel_reservation")) {
                problems.add("function hotel.expire_holds() is missing or does not cancel through"
                        + " hotel.cancel_reservation (apply Hotel_Database/sql/02_operations.sql, then Flyway V12)");
            }
        }
        for (String table : List.of("booking_confirmation_requests", "invoice_delivery_requests")) {
            if (!schema.hasTable(table)) {
                continue;
            }
            List<String> missing = WORKER_COLUMNS.stream().filter(column -> !schema.hasColumn(table, column)).toList();
            if (!missing.isEmpty()) {
                problems.add("hotel." + table + " lacks outbox worker columns " + String.join(", ", missing) + V12);
            }
        }
    }
}
