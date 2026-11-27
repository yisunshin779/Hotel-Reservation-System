package com.Hotel.Module_CheckInCheckOut;

import com.Hotel.Module_CheckInCheckOut.dto.InvoiceAdjustmentRequestDTO;
import com.Hotel.Module_CheckInCheckOut.entity.*;
import com.Hotel.Module_CheckInCheckOut.service.*;
import com.Hotel.Module_Report.security.ReportIdentityService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=none", "hotel.payment.expiry-enabled=false", "logging.level.root=WARN"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvoiceAdjustmentIntegrationTest {
    private static final EmbeddedPostgres PG = start();
    private static final String HASH = new BCryptPasswordEncoder().encode("test-password");
    @MockBean org.redisson.api.RedissonClient redis;
    @MockBean com.Hotel.Module_DatPhong.service.BookingService bookingService;
    @Autowired JdbcTemplate jdbc;
    @Autowired InvoiceAdjustmentService adjustments;
    @Autowired ReportIdentityService identities;
    @Autowired CheckInService checkin;
    @Autowired CheckOutService checkout;
    @Autowired MockMvc mvc;
    private static EmbeddedPostgres start() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
    }
    @BeforeAll void schema() throws Exception {
        for (String file : List.of("01_schema.sql", "02_operations.sql", "05_checkin_checkout.sql",
                "06_stay_contracts.sql", "08_invoice_adjustments.sql", "08_invoice_adjustments.sql"))
            jdbc.execute(Files.readString(Path.of("../Hotel_Database/sql", file)));
    }
    @AfterAll static void close() throws Exception { PG.close(); }
    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE hotel.branches,hotel.roles,hotel.customers,hotel.promotions RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO hotel.branches(name,address,city) VALUES('One','One','One')");
        jdbc.update("INSERT INTO hotel.roles(code,name) VALUES('ADMIN','Admin'),('RECEPTIONIST','Staff'),('MANAGER','Manager'),('GUEST','Guest')");
        jdbc.update("""
            INSERT INTO hotel.users(email,full_name,password_hash,role_id,branch_id)
            VALUES('admin@test','Admin',?,1,NULL),('staff@test','Staff',?,2,1),
                  ('manager@test','Manager',?,3,1),('guest@test','Guest',?,4,NULL)
            """, HASH, HASH, HASH, HASH);
        jdbc.update("INSERT INTO hotel.customers(full_name) VALUES('Guest')");
        jdbc.update("INSERT INTO hotel.room_types(branch_id,name,capacity,base_price) VALUES(1,'Standard',2,100)");
        jdbc.update("INSERT INTO hotel.rooms(branch_id,room_type_id,room_number) VALUES(1,1,'101')");
    }
    private ReportIdentityService.Identity admin() { return identities.find("admin@test"); }
    private InvoiceAdjustmentRequestDTO request(String key, String amount) {
        return new InvoiceAdjustmentRequestDTO("Sửa khoản phụ phí ghi nhầm", key,
                List.of(new InvoiceAdjustmentLine("EXTRA", "Điều chỉnh phụ phí", new BigDecimal(amount))));
    }
    private long booking() {
        long id = jdbc.queryForObject("""
            INSERT INTO hotel.reservations(code,branch_id,customer_id,check_in,check_out,status,room_subtotal,quoted_total)
            VALUES(?,1,1,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
                (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+2,'CONFIRMED',200,200) RETURNING id
            """, Long.class, "A-" + UUID.randomUUID());
        long detail = jdbc.queryForObject("INSERT INTO hotel.reservation_details(reservation_id,room_type_id,quantity,nightly_average,subtotal) VALUES(?,1,1,100,200) RETURNING id", Long.class, id);
        jdbc.update("""
            INSERT INTO hotel.reservation_nights(reservation_detail_id,room_type_id,stay_date,quantity,nightly_price)
            SELECT ?,1,check_in+d,1,100 FROM hotel.reservations CROSS JOIN generate_series(0,1) d WHERE id=?
            """, detail, id);
        return id;
    }
    private long closedStay() {
        long id = booking();
        var actor = new Receptionist(2,1,"RECEPTIONIST");
        checkin.confirm(id, List.of(1L), null, new StayGuest("Guest", "AB123456", null), actor);
        checkout.payment(id, new BigDecimal("200"), "CASH", "CHECKOUT-" + id, actor);
        checkout.confirm(id, actor);
        // A later booking may use this room; do not reopen the completed reservation.
        jdbc.update("UPDATE hotel.rooms SET cleaning='READY' WHERE id=1");
        return id;
    }
    private String url(long id) { return "/api/reservations/" + id + "/checkout/invoice/adjustments"; }
    private long count() { return jdbc.queryForObject("SELECT count(*) FROM hotel.invoice_adjustments", Long.class); }
    private void decimal(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }
    private String snapshot(String table) {
        return jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM hotel." + table + " t", String.class);
    }
    @Test void issuesSignedAdjustmentsAndPreservesTheOriginalClosedStay() throws Exception {
        long id = closedStay();
        var before = new LinkedHashMap<String,String>();
        for (String table : List.of("invoices", "invoice_lines", "payments", "refunds", "reservations", "room_assignments", "rooms"))
            before.put(table, snapshot(table));
        var first = adjustments.create(id, request("ADJUSTMENT-ONE", "-25"), admin());
        decimal("-25", first.total()); decimal("175", first.adjustedTotal());
        assertEquals(1, first.createdBy()); assertTrue(first.number().startsWith("ADJ-"));
        var second = adjustments.create(id, request("ADJUSTMENT-TWO", "10"), admin());
        decimal("185", second.adjustedTotal());
        assertEquals(first.invoiceId(), second.invoiceId());
        for (var entry : before.entrySet()) assertEquals(entry.getValue(), snapshot(entry.getKey()), entry.getKey());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM hotel.audit_logs WHERE action='INVOICE_ADJUSTMENT' AND actor_id=1", Integer.class));
        mvc.perform(get(url(id)).with(httpBasic("admin@test", "test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].reason").value("Sửa khoản phụ phí ghi nhầm"))
            .andExpect(jsonPath("$[1].adjustedTotal").value(185));
        mvc.perform(post("/api/reservations/"+id+"/checkout").with(httpBasic("staff@test", "test-password")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalAmount").value(200));
    }
    @Test void onlyActiveUnlockedAdminCanIssueAndRead() throws Exception {
        long id = closedStay();
        String body = "{\"reason\":\"Correction\",\"idempotencyKey\":\"API-ADJUST-KEY\",\"lines\":[{\"type\":\"EXTRA\",\"description\":\"Correction\",\"amount\":-10}]}";
        mvc.perform(post(url(id)).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(url(id)).header("X-Role", "ADMIN").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        for (String user : List.of("staff@test", "manager@test", "guest@test")) {
            mvc.perform(post(url(id)).with(httpBasic(user, "test-password")).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
            mvc.perform(get(url(id)).with(httpBasic(user, "test-password"))).andExpect(status().isForbidden());
            assertThrows(StayException.class, () -> adjustments.create(id, request("SERVICE-DENIED", "-10"), identities.find(user)));
        }
        jdbc.update("UPDATE hotel.users SET locked_until=now()+interval '1 hour' WHERE id=1");
        mvc.perform(post(url(id)).with(httpBasic("admin@test", "test-password")).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE hotel.users SET locked_until=NULL,active=false WHERE id=1");
        mvc.perform(post(url(id)).with(httpBasic("admin@test", "test-password")).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        assertEquals(0, count());
        jdbc.update("UPDATE hotel.users SET active=true WHERE id=1");
        mvc.perform(post(url(id)).with(httpBasic("admin@test", "test-password")).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(-10)).andExpect(jsonPath("$.createdBy").value(1));
        // Admin access to adjustments does not grant receptionist operations.
        mvc.perform(post("/api/reservations/"+id+"/checkout").with(httpBasic("admin@test", "test-password"))).andExpect(status().isForbidden());
    }
    @Test void requiresClosedInvoiceAndValidReasonLinesAndAmounts() throws Exception {
        long open = booking();
        assertEquals("INVOICE_NOT_CLOSED", assertThrows(StayException.class,
                () -> adjustments.create(open, request("OPEN-INVOICE-KEY", "10"), admin())).code());
        long id = closedStay();
        for (String body : List.of("{}", "null", "{broken",
                "{\"reason\":\"  \",\"idempotencyKey\":\"INVALID-KEY\",\"lines\":[{\"type\":\"EXTRA\",\"description\":\"x\",\"amount\":1}]}",
                "{\"reason\":\"x\",\"idempotencyKey\":\"INVALID-KEY\",\"lines\":[]}"))
            mvc.perform(post(url(id)).with(httpBasic("admin@test", "test-password")).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        for (String value : List.of("0", "1.001", "1000000000000", "1E+14"))
            assertThrows(StayException.class, () -> adjustments.create(id, request("INVALID-AMOUNT", value), admin()));
        assertThrows(StayException.class, () -> adjustments.create(id,
                new InvoiceAdjustmentRequestDTO("Reason", "INVALID-TYPE", List.of(new InvoiceAdjustmentLine("OTHER", "x", BigDecimal.ONE))), admin()));
        assertThrows(DataAccessException.class, () -> adjustments.create(id, request("NEGATIVE-TOTAL", "-201"), admin()));
        assertEquals(0, count());
    }
    @Test void requestReplayIsIdempotentAndConflictingReuseIsRejected() {
        long id = closedStay();
        var original = adjustments.create(id, request("REPLAY-ADJUSTMENT", "-20"), admin());
        assertEquals(original, adjustments.create(id, request("REPLAY-ADJUSTMENT", "-20.00"), admin()));
        assertEquals("ADJUSTMENT_KEY_CONFLICT", assertThrows(StayException.class,
            () -> adjustments.create(id, request("REPLAY-ADJUSTMENT", "-21"), admin())).code());
        var differentReason = new InvoiceAdjustmentRequestDTO("Different reason", "REPLAY-ADJUSTMENT", original.lines());
        assertThrows(StayException.class, () -> adjustments.create(id, differentReason, admin()));
        long other = closedStay();
        assertThrows(StayException.class, () -> adjustments.create(other, request("REPLAY-ADJUSTMENT", "-20"), admin()));
        assertEquals(1, count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM hotel.audit_logs WHERE action='INVOICE_ADJUSTMENT'", Integer.class));
    }
    @Test void concurrentDuplicateRequestsCreateOneDocument() throws Exception {
        long id = closedStay();
        var admin = admin();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<InvoiceAdjustment> action = () -> { start.await(); return adjustments.create(id, request("CONCURRENT-SAME", "-150"), admin); };
            var a = pool.submit(action); var b = pool.submit(action); start.countDown();
            assertEquals(a.get(15, TimeUnit.SECONDS).id(), b.get(15, TimeUnit.SECONDS).id());
            assertEquals(1, count());
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentReductionsCannotMakeTheCumulativeTotalNegative() throws Exception {
        long id = closedStay();
        var admin = admin();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (String key : List.of("CONCURRENT-FIRST", "CONCURRENT-SECOND")) futures.add(pool.submit(() -> {
                start.await();
                try { adjustments.create(id, request(key, "-150"), admin); return true; }
                catch (DataAccessException expected) { return false; }
            }));
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes); assertEquals(1, count());
            decimal("50", adjustments.list(id, admin).get(0).adjustedTotal());
        } finally { pool.shutdownNow(); }
    }
    @Test void documentsAreImmutableAndDatabaseAlsoEnforcesAdminAndClosedInvoice() {
        long id = closedStay();
        var adjustment = adjustments.create(id, request("IMMUTABLE-ADJUST", "10"), admin());
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE hotel.invoice_adjustments SET reason='Changed' WHERE id=?", adjustment.id()));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM hotel.invoice_adjustments WHERE id=?", adjustment.id()));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE hotel.invoices SET total=210 WHERE id=?", adjustment.invoiceId()));
        String insert = "INSERT INTO hotel.invoice_adjustments(invoice_id,adjustment_number,idempotency_key,reason,created_by,issued_at,lines) VALUES(?,?,?,'Reason',?,now(),'[{\"type\":\"EXTRA\",\"description\":\"Correction\",\"amount\":1}]'::jsonb)";
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, adjustment.invoiceId(), "ADJ-FORGED", "FORGED-ADMIN-KEY", 2));
        long open = booking();
        long draft = jdbc.queryForObject("INSERT INTO hotel.invoices(reservation_id,invoice_number,room_amount,service_amount,total) VALUES(?,'DRAFT-ONE',200,0,200) RETURNING id", Long.class, open);
        assertThrows(DataAccessException.class, () -> jdbc.update(insert, draft, "ADJ-DRAFT", "DRAFT-ADJUST-KEY", 1));
        assertEquals(1, count());
    }
    @Test void auditFailureRollsBackTheEntireAdjustment() {
        long id = closedStay();
        jdbc.execute("""
            CREATE FUNCTION hotel.reject_adjustment_audit() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF NEW.action='INVOICE_ADJUSTMENT' THEN RAISE EXCEPTION 'injected audit failure'; END IF; RETURN NEW; END $$;
            CREATE TRIGGER reject_adjustment_audit BEFORE INSERT ON hotel.audit_logs
              FOR EACH ROW EXECUTE FUNCTION hotel.reject_adjustment_audit();
            """);
        try {
            assertThrows(DataAccessException.class, () -> adjustments.create(id, request("ROLLBACK-ADJUST", "-10"), admin()));
            assertEquals(0, count());
        } finally {
            jdbc.execute("DROP TRIGGER reject_adjustment_audit ON hotel.audit_logs; DROP FUNCTION hotel.reject_adjustment_audit()");
        }
    }
}
