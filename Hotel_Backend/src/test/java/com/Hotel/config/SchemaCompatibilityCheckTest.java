package com.Hotel.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.sql.DataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * #7: start-up refuses feature flags whose schema objects are missing, by reading the catalog.
 * V12 cases: Payment and the delivery outboxes need V12 even when V11 and V13 are present and Flyway is off.
 * 24/11: the schema mode comes from configuration (hotel.schema.mode); an operational database must have every
 * table Payment and Checkout use, a report-only one (report-demo profile) may lack them.
 */
class SchemaCompatibilityCheckTest {
    private static EmbeddedPostgres pg;

    @BeforeAll
    static void start() throws Exception {
        pg = EmbeddedPostgres.builder().setPort(0).start();
        var admin = new JdbcTemplate(pg.getPostgresDatabase());
        for (String db : List.of("full_schema", "v10_schema", "v13_without_v11", "v13_without_v12", "v12_reverted",
                "report_only")) {
            admin.execute("CREATE DATABASE " + db);
        }
        Flyway.configure().dataSource(source("full_schema")).schemas("hotel").load().migrate();
        operational("v10_schema");
        // V12 does not depend on hotel.account; applying it here isolates the missing V13.
        migration("v10_schema", "V9__legacy_jpa_tables.sql", "V10__google_subject.sql", "V12__payment_expiry_and_outbox.sql");
        operational("v13_without_v11");
        migration("v13_without_v11", "V9__legacy_jpa_tables.sql", "V10__google_subject.sql",
                "V12__payment_expiry_and_outbox.sql", "V13__account_sessions_audit_outbox.sql");
        // Every V11 and V13 marker is present, V12 was skipped (no Flyway history is consulted).
        operational("v13_without_v12");
        migration("v13_without_v12", "V9__legacy_jpa_tables.sql", "V10__google_subject.sql",
                "V11__booking_identity_bridge.sql", "V13__account_sessions_audit_outbox.sql");
        // Fully migrated, then an operator re-runs the pre-V12 cancel_reservation body from 02_operations.sql.
        Flyway.configure().dataSource(source("v12_reverted")).schemas("hotel").load().migrate();
        String operations = Files.readString(Path.of("../Hotel_Database/sql/02_operations.sql"));
        int start = operations.indexOf("CREATE OR REPLACE FUNCTION cancel_reservation");
        int end = operations.indexOf("END $$;", start) + "END $$;".length();
        new JdbcTemplate(source("v12_reverted")).execute("SET search_path TO hotel; " + operations.substring(start, end));
        new JdbcTemplate(source("report_only")).execute(Files.readString(Path.of("../Hotel_Database/sql/01_schema.sql")));
        // Fully migrated, then one table Payment or Checkout uses is missing altogether.
        for (String table : OPERATIONAL_TABLES) {
            admin.execute("CREATE DATABASE missing_" + table + " TEMPLATE full_schema");
            new JdbcTemplate(source("missing_" + table)).execute("DROP TABLE hotel." + table + " CASCADE");
        }
    }

    /** The tables named in the finding; each one alone must stop an operational start-up. */
    private static final List<String> OPERATIONAL_TABLES =
            List.of("payment_intents", "booking_confirmation_requests", "invoice_delivery_requests");

    private static ApplicationContextRunner checkOn(String db) {
        return new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(source(db)))
                .withBean(SchemaCapabilities.class)
                .withBean(SchemaCompatibilityCheck.class)
                .withPropertyValues("spring.flyway.enabled=false");
    }

    private static String rootMessage(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append('\n');
        }
        return text.toString();
    }

    @Test
    void operationalStartupFailsWhenAPaymentOrCheckoutTableIsMissing() {
        for (String table : OPERATIONAL_TABLES) {
            for (String mode : List.of("hotel.booking.canonical-enabled=false", "hotel.booking.canonical-enabled=true")) {
                checkOn("missing_" + table).withPropertyValues(mode).run(context -> {
                    assertNotNull(context.getStartupFailure(), table + " " + mode);
                    String message = rootMessage(context.getStartupFailure());
                    assertTrue(message.contains("hotel." + table + " is missing"), message);
                    assertTrue(message.contains(table.equals("invoice_delivery_requests") ? "06_stay_contracts.sql" : "07_payment.sql"),
                            message);
                });
            }
        }
        // A report-only database (01_schema.sql) is not an operational database.
        checkOn("report_only").run(context -> {
            assertNotNull(context.getStartupFailure());
            String message = rootMessage(context.getStartupFailure());
            for (String table : OPERATIONAL_TABLES) {
                assertTrue(message.contains("hotel." + table + " is missing"), message);
            }
        });
    }

    @Test
    void reportOnlyModeIsExplicitAndStillChecksWhatIsPresent() {
        checkOn("report_only").withPropertyValues("hotel.schema.mode=report-only")
                .run(context -> assertNull(context.getStartupFailure()));
        // Report-only does not hide a half-applied Payment schema, and does not combine with canonical booking.
        checkOn("v13_without_v12").withPropertyValues("hotel.schema.mode=report-only").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context.getStartupFailure()).contains("V12__payment_expiry_and_outbox.sql"));
        });
        checkOn("full_schema").withPropertyValues("hotel.schema.mode=report-only", "hotel.booking.canonical-enabled=true")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootMessage(context.getStartupFailure()).contains("hotel.schema.mode=report-only"));
                });
        checkOn("full_schema").withPropertyValues("hotel.schema.mode=reports")
                .run(context -> assertNotNull(context.getStartupFailure()));
        checkOn("full_schema").run(context -> assertNull(context.getStartupFailure()));
    }

    /** The whole backend, with Flyway off (database migrated by CLI), refuses to start without the table. */
    @Test
    void applicationStartupFailsOnAnOperationalDatabaseWithoutTheOutboxTable() {
        String db = "missing_booking_confirmation_requests";
        var failure = assertThrows(Throwable.class, () -> {
            // Command-line arguments: they override application.yaml, as HOTEL_* variables do in a deployment.
            try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(com.Hotel.HotelSystem.class)
                    .run("--spring.datasource.url=" + pg.getJdbcUrl("postgres", db),
                            "--spring.datasource.username=postgres", "--spring.datasource.password=",
                            "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=none", "--server.port=0",
                            "--hotel.notifications.dispatch-enabled=false", "--hotel.payment.expiry-enabled=false",
                            "--management.health.mail.enabled=false", "--logging.level.root=WARN")) {
                assertNotNull(context); // started: the check did not run
            }
        });
        String message = rootMessage(failure);
        assertTrue(message.contains("hotel.booking_confirmation_requests is missing") && message.contains("07_payment.sql"),
                message);
        // Only the schema check stopped it: the same start-up succeeds on the complete database.
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(com.Hotel.HotelSystem.class)
                .run("--spring.datasource.url=" + pg.getJdbcUrl("postgres", "full_schema"),
                        "--spring.datasource.username=postgres", "--spring.datasource.password=",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=none", "--server.port=0",
                        "--hotel.notifications.dispatch-enabled=false", "--hotel.payment.expiry-enabled=false",
                        "--management.health.mail.enabled=false", "--logging.level.root=WARN")) {
            assertTrue(context.isRunning());
        }
    }

    @AfterAll
    static void stop() throws Exception {
        pg.close();
    }

    private static DataSource source(String db) {
        return new DriverManagerDataSource(pg.getJdbcUrl("postgres", db), "postgres", "");
    }

    private static void operational(String db) throws Exception {
        var jdbc = new JdbcTemplate(source(db));
        for (String file : List.of("01_schema.sql", "02_operations.sql", "05_checkin_checkout.sql",
                "06_stay_contracts.sql", "07_payment.sql", "08_invoice_adjustments.sql")) {
            jdbc.execute(Files.readString(Path.of("../Hotel_Database/sql", file)));
        }
    }

    private static void migration(String db, String... files) throws Exception {
        var jdbc = new JdbcTemplate(source(db));
        for (String file : files) {
            try (var in = SchemaCompatibilityCheckTest.class.getResourceAsStream("/db/migration/" + file)) {
                jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static List<String> problems(String db, boolean canonical, boolean basic) {
        return problems(db, SchemaCompatibilityCheck.OPERATIONAL, canonical, basic);
    }

    private static List<String> problems(String db, String mode, boolean canonical, boolean basic) {
        return SchemaCompatibilityCheck.problems(new SchemaCapabilities(new JdbcTemplate(source(db))), mode, canonical, basic);
    }

    @Test
    void fullyMigratedSchemaSupportsEveryMode() {
        assertEquals(List.of(), problems("full_schema", true, true));
        assertEquals(List.of(), problems("full_schema", true, false));
        assertEquals(List.of(), problems("full_schema", false, true));
    }

    @Test
    void canonicalWithoutV11IsRejectedWithTheMigrationToApply() {
        var missing = problems("v13_without_v11", true, true);
        assertTrue(missing.stream().anyMatch(p -> p.contains("account_user_links") && p.contains("V11")), missing.toString());
        assertTrue(missing.stream().anyMatch(p -> p.contains("booking_hold_requests") && p.contains("V11")), missing.toString());
        // Legacy mode does not need the V11 bridge.
        assertEquals(List.of(), problems("v13_without_v11", false, true));
    }

    @Test
    void accountTablesWithoutV13AreRejectedInEveryMode() {
        var legacy = problems("v10_schema", false, true);
        assertTrue(legacy.stream().allMatch(p -> p.contains("V13")), legacy.toString());
        assertTrue(legacy.stream().anyMatch(p -> p.contains("token_version")), legacy.toString());
        var canonical = problems("v10_schema", true, true);
        assertTrue(canonical.stream().anyMatch(p -> p.contains("V11")), canonical.toString());
    }

    @Test
    void reportOnlyDatabaseStartsOnlyInReportOnlyMode() {
        assertEquals(List.of(), problems("report_only", SchemaCompatibilityCheck.REPORT_ONLY, false, true));
        var operational = problems("report_only", false, true);
        assertEquals(SchemaCompatibilityCheck.OPERATIONAL_TABLES.size() - 8, operational.size(), operational.toString());
        assertTrue(operational.stream().allMatch(p -> p.contains(" is missing: Payment and Checkout need it")), operational.toString());
        // No duplicate "lacks outbox worker columns" for a table that is missing altogether.
        assertTrue(operational.stream().noneMatch(p -> p.contains("lacks outbox worker columns")), operational.toString());
    }

    @Test
    void missingTableAndMissingV12AreReportedSeparately() {
        for (String table : OPERATIONAL_TABLES) {
            var missing = problems("missing_" + table, false, true);
            assertEquals(1, missing.size(), missing.toString());
            assertTrue(missing.get(0).startsWith("hotel." + table + " is missing"), missing.get(0));
            assertTrue(missing.get(0).contains("V8__hotel_operations_baseline.sql"), missing.get(0));
        }
        // All tables present, V12 skipped: only V12 objects are reported, in both modes.
        for (String mode : List.of(SchemaCompatibilityCheck.OPERATIONAL, SchemaCompatibilityCheck.REPORT_ONLY)) {
            var v12 = problems("v13_without_v12", mode, false, true);
            assertEquals(3, v12.size(), v12.toString());
            assertTrue(v12.stream().allMatch(p -> p.contains("V12__payment_expiry_and_outbox.sql")), v12.toString());
        }
    }

    @Test
    void paymentSchemaWithoutV12IsRejectedEvenWhenV11AndV13ArePresent() {
        for (boolean canonical : List.of(true, false)) {
            var missing = problems("v13_without_v12", canonical, true);
            assertFalse(missing.isEmpty());
            // Only V12 objects are reported; the V11 and V13 markers are satisfied.
            assertTrue(missing.stream().allMatch(p -> p.contains("V12")), missing.toString());
            assertTrue(missing.stream().anyMatch(p -> p.contains("hotel.release_payment_selections(bigint)")), missing.toString());
            for (String table : List.of("booking_confirmation_requests", "invoice_delivery_requests")) {
                assertTrue(missing.stream().anyMatch(p -> p.contains("hotel." + table)
                        && p.contains("attempts, next_attempt_at, locked_until, last_error, sent_at")), missing.toString());
            }
        }
    }

    @Test
    void cancellationBodyWithoutTheReleaseStepIsRejected() {
        var missing = problems("v12_reverted", false, true);
        assertEquals(1, missing.size(), missing.toString());
        assertTrue(missing.get(0).contains("hotel.cancel_reservation(bigint,text)") && missing.get(0).contains("V12"), missing.get(0));
    }

    @Test
    void schemaWithoutV12StopsStartupWithFlywayDisabled() {
        new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(source("v13_without_v12")))
                .withBean(SchemaCapabilities.class)
                .withBean(SchemaCompatibilityCheck.class)
                .withPropertyValues("spring.flyway.enabled=false", "hotel.booking.canonical-enabled=false")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String message = context.getStartupFailure().getMessage();
                    assertTrue(message.contains("release_payment_selections") && message.contains("V12__payment_expiry_and_outbox.sql"),
                            message);
                });
        new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(source("report_only")))
                .withBean(SchemaCapabilities.class)
                .withBean(SchemaCompatibilityCheck.class)
                .withPropertyValues("hotel.schema.mode=report-only")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void basicCannotBeDisabledWithoutLinkedBearerAuthentication() {
        var config = problems("full_schema", false, false);
        assertEquals(1, config.size());
        assertTrue(config.get(0).contains("HOTEL_BASIC_AUTH_ENABLED=false"));
    }

    @Test
    void incompatibleSchemaStopsTheApplicationContext() {
        new ApplicationContextRunner()
                .withBean(DataSource.class, () -> source("v13_without_v11"))
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(source("v13_without_v11")))
                .withBean(SchemaCapabilities.class)
                .withBean(SchemaCompatibilityCheck.class)
                .withPropertyValues("hotel.booking.canonical-enabled=true")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(context.getStartupFailure().getMessage().contains("apply Flyway V11"),
                            context.getStartupFailure().getMessage());
                });
        new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(source("v13_without_v11")))
                .withBean(SchemaCapabilities.class)
                .withBean(SchemaCompatibilityCheck.class)
                .withPropertyValues("hotel.booking.canonical-enabled=false")
                .run(context -> assertNull(context.getStartupFailure()));
    }
}
