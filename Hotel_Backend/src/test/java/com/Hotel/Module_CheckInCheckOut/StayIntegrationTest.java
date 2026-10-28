package com.Hotel.Module_CheckInCheckOut;

import com.Hotel.HotelSystem;
import com.Hotel.Module_CheckInCheckOut.service.*;
import com.Hotel.Module_CheckInCheckOut.entity.*;
import com.Hotel.Module_CheckInCheckOut.repository.StayRepository;
import com.Hotel.Module_CheckInCheckOut.dto.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Intentionally gated: this suite rebuilds ONLY a dedicated disposable database. */
@EnabledIfEnvironmentVariable(named="HOTEL_TEST_DB_URL",matches="jdbc:postgresql://[^/]+/hotel_lifecycle_test")
@SpringBootTest(classes=HotelSystem.class,properties={
    "spring.datasource.url=${HOTEL_TEST_DB_URL}","spring.datasource.username=${HOTEL_TEST_DB_USER:hotel_test}",
    "spring.datasource.password=","spring.datasource.hikari.maximum-pool-size=8","debug=false","logging.level.root=WARN"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StayIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CheckInService checkin;
    @Autowired CheckOutService checkout;
    @Autowired InvoiceService invoices;
    @Autowired RefundService refunds;
    @Autowired RoomChangeService changes;
    @Autowired StayRepository repo;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactionManager;
    private final Receptionist actor=new Receptionist(1,1,"RECEPTIONIST");
    private BigDecimal n(String v) { return new BigDecimal(v); }
    private CheckInResultDTO checkIn(long id, List<Long> rooms, String identity, Receptionist staff) {
        return checkin.confirm(id,rooms,null,new StayGuest("Occupant",identity,null),staff);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM hotel."+table,Long.class); }
    @BeforeAll void schema() throws Exception {
        assertTrue(System.getenv("HOTEL_TEST_DB_URL").endsWith("/hotel_lifecycle_test"));
        jdbc.execute("DROP SCHEMA IF EXISTS hotel CASCADE");
        for (String file: List.of("01_schema.sql","02_operations.sql","05_checkin_checkout.sql","06_stay_contracts.sql"))
            jdbc.execute(Files.readString(Path.of("../Hotel_Database/sql",file)));
    }
    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE hotel.branches,hotel.roles,hotel.customers,hotel.promotions RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO hotel.branches(name,address,city) VALUES('Test','Test','Test'),('Other','Other','Other')");
        jdbc.update("INSERT INTO hotel.roles(code,name) VALUES('RECEPTIONIST','Lễ tân'),('GUEST','Khách')");
        jdbc.update("INSERT INTO hotel.users(email,full_name,password_hash,role_id,branch_id) VALUES('staff@example.test','Staff','unused',1,1)");
        jdbc.update("INSERT INTO hotel.customers(full_name) VALUES('Guest')");
        jdbc.update("INSERT INTO hotel.room_types(branch_id,name,capacity,base_price) VALUES(1,'Standard',2,999),(1,'Deluxe',3,1999),(2,'Other',2,999)");
        jdbc.update("INSERT INTO hotel.rooms(branch_id,room_type_id,room_number) VALUES(1,1,'101'),(1,1,'102'),(1,1,'103'),(1,2,'201'),(2,3,'101')");
    }
    private long booking(int quantity) {
        long id=jdbc.queryForObject("""
            INSERT INTO hotel.reservations(code,branch_id,customer_id,check_in,check_out,status,room_subtotal,quoted_total)
            VALUES(?,1,1,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,
              (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+2,'CONFIRMED',?,?) RETURNING id
            """,Long.class,"B-"+UUID.randomUUID(),quantity*200,quantity*200);
        long detail=jdbc.queryForObject("INSERT INTO hotel.reservation_details(reservation_id,room_type_id,quantity,nightly_average,subtotal) VALUES(?,1,?,100,?) RETURNING id",Long.class,id,quantity,quantity*200);
        jdbc.update("""
            INSERT INTO hotel.reservation_nights(reservation_detail_id,room_type_id,stay_date,quantity,nightly_price)
            SELECT ?,1,check_in+d,?,100 FROM hotel.reservations CROSS JOIN generate_series(0,1) d WHERE id=?
            """,detail,quantity,id);
        return id;
    }
    @Test void completeStayPreservesSnapshotsSettlesExistingPaymentAndFreezesInvoice() throws Exception {
        long id=booking(2);
        var checked=checkIn(id,List.of(1L,2L),"AB123456",actor);
        assertEquals("CHECKED_IN",checked.reservationStatus());
        assertEquals("OCCUPIED",checked.rooms().get(0).occupancy());
        jdbc.update("UPDATE hotel.room_types SET base_price=99999 WHERE id=1");
        jdbc.update("UPDATE hotel.reservations SET discount_amount=20,quoted_total=380 WHERE id=?",id);
        jdbc.update("INSERT INTO hotel.services(branch_id,category,name,current_price) VALUES(1,'Minibar','Water',25)");
        jdbc.update("INSERT INTO hotel.service_usages(reservation_id,service_id,quantity,unit_price) VALUES(?,1,1,25)",id);
        jdbc.update("UPDATE hotel.services SET current_price=999 WHERE id=1");
        jdbc.update("INSERT INTO hotel.extra_charges(reservation_id,description,amount) VALUES(?,'Extra',10)",id);
        var preview=checkout.preview(id,actor);
        assertEquals(0,n("415").compareTo(preview.grossAmount()));
        assertEquals(0,count("invoices"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM hotel.room_assignments WHERE released_at IS NULL",Integer.class));
        assertThrows(StayException.class,() -> checkout.confirm(id,actor));
        jdbc.update("INSERT INTO hotel.payments(reservation_id,amount,method,status,obligation_key) VALUES(?,415,'CASH','UNPAID','SHARED-UC15-KEY')",id);
        checkout.payment(id,n("415"),"CASH","SHARED-UC15-KEY",actor);
        checkout.payment(id,n("415.00"),"CASH","SHARED-UC15-KEY",actor);
        assertEquals(1,count("payments"));
        assertThrows(StayException.class,() -> checkout.payment(id,n("414"),"CASH","SHARED-UC15-KEY",actor));
        var result=checkout.confirm(id,actor);
        assertEquals(result.invoiceId(),checkout.confirm(id,actor).invoiceId());
        assertEquals("DIRTY",result.cleaningStatus());
        assertEquals(1,count("invoices"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM hotel.room_assignments WHERE released_at IS NULL",Integer.class));
        assertEquals(2,count("room_assignments"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM hotel.rooms WHERE id IN(1,2) AND occupancy='VACANT' AND cleaning='DIRTY'",Integer.class));
        var invoice=invoices.get(id,actor);
        assertEquals(0,invoice.total().compareTo(invoice.lines().stream().map(InvoiceLine::amount).reduce(BigDecimal.ZERO,BigDecimal::add)));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("UPDATE hotel.invoices SET total=1 WHERE id=?",result.invoiceId()));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("DELETE FROM hotel.invoice_lines WHERE invoice_id=?",result.invoiceId()));
        assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("INSERT INTO hotel.service_usages(reservation_id,service_id,quantity,unit_price) VALUES(?,1,1,25)",id));
        mvc.perform(get("/api/reservations/"+id+"/checkout/invoice/export").principal(() -> "staff@example.test"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=invoice-"+invoice.id()+".html"));
    }
    @Test void rejectsWrongDateStateIdentityAndPaymentWithoutSideEffects() {
        long id=booking(1);
        assertThrows(StayException.class,() -> checkIn(id,List.of(1L),"",actor));
        jdbc.update("UPDATE hotel.reservations SET check_in=check_in+1 WHERE id=?",id);
        assertThrows(StayException.class,() -> checkIn(id,List.of(1L),"AB123456",actor));
        jdbc.update("UPDATE hotel.reservations SET check_in=check_in-1,hold_expires_at=now()+interval '1 hour' WHERE id=?",id);
        assertThrows(StayException.class,() -> checkIn(id,List.of(1L),"AB123456",actor));
        jdbc.update("INSERT INTO hotel.payments(reservation_id,amount,method,status,obligation_key) VALUES(?,60,'MOCK_QR','PAID','DEPOSIT-KEY')",id);
        assertDoesNotThrow(() -> checkin.available(id,actor));
        jdbc.update("UPDATE hotel.reservations SET status='CANCELLED' WHERE id=?",id);
        assertThrows(StayException.class,() -> checkIn(id,List.of(1L),"AB123456",actor));
        assertEquals(0,count("room_assignments"));
        assertNull(jdbc.queryForObject("SELECT identity_document FROM hotel.customers WHERE id=1",String.class));
        assertThrows(StayException.class,() -> checkin.available(999,actor));
    }
    @Test void roomRulesAndMultiRoomFailuresAreAtomic() {
        long id=booking(2);
        for(String update : List.of("cleaning='DIRTY'","cleaning='CLEANING'","active=false","deleted_at=now()","occupancy='OCCUPIED'")) {
            jdbc.update("UPDATE hotel.rooms SET "+update+" WHERE id=2");
            assertThrows(StayException.class,() -> checkIn(id,List.of(1L,2L),"AB123456",actor));
            assertEquals(0,count("room_assignments"));
            jdbc.update("UPDATE hotel.rooms SET cleaning='READY',active=true,deleted_at=NULL,occupancy='VACANT' WHERE id=2");
        }
        assertThrows(StayException.class,() -> checkIn(id,List.of(1L,4L),"AB123456",actor));
        assertThrows(StayException.class,() -> checkIn(id,List.of(1L,5L),"AB123456",actor));
        assertThrows(StayException.class,() -> checkIn(id,List.of(1L,1L),"AB123456",actor));
        assertThrows(StayException.class,() -> checkIn(id,List.of(1L),"AB123456",actor));
        jdbc.update("INSERT INTO hotel.room_maintenance(room_id,starts_on,ends_on,reason) SELECT 2,check_in,check_out,'Repair' FROM hotel.reservations WHERE id=?",id);
        assertTrue(checkin.available(id,actor).stream().noneMatch(r -> r.id()==2));
        assertThrows(StayException.class,() -> checkIn(id,List.of(1L,2L),"AB123456",actor));
        assertEquals(0,count("room_assignments"));
    }
    @Test void refundsBlockCheckoutUntilSettledAndOverpaymentRollsBack() {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        assertThrows(StayException.class,() -> checkout.payment(id,n("201"),"CASH","OVERPAY-KEY",actor));
        assertEquals(0,count("payments"));
        jdbc.update("INSERT INTO hotel.payments(reservation_id,amount,method,status,obligation_key) VALUES(?,250,'CASH','PAID','OLD-UC08-KEY')",id);
        assertEquals(0,n("50").compareTo(checkout.preview(id,actor).refundAmount()));
        assertThrows(StayException.class,() -> checkout.confirm(id,actor));
        jdbc.update("INSERT INTO hotel.refunds(payment_id,reservation_id,amount,status,idempotency_key) SELECT id,?,50,'DONE','REFUND-KEY' FROM hotel.payments WHERE reservation_id=?",id,id);
        assertDoesNotThrow(() -> checkout.confirm(id,actor));
    }
    @Test void rejectsCrossBranchAndNonReceptionistAndRequiresTrustedPrincipal() throws Exception {
        long id=booking(1);
        assertThrows(StayException.class,() -> checkin.available(id,new Receptionist(1,2,"RECEPTIONIST")));
        assertThrows(StayException.class,() -> checkout.preview(id,new Receptionist(1,1,"GUEST")));
        mvc.perform(get("/api/reservations/"+id+"/checkin/available-rooms")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reservations/"+id+"/checkin/available-rooms").header("X-User-Id","1").header("X-Role","RECEPTIONIST"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reservations/"+id+"/checkin/available-rooms").principal(() -> "staff@example.test"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/reservations/"+id+"/checkin").principal(() -> "staff@example.test")
            .contentType("application/json").content("{\"roomIds\":[1],\"guest\":{\"fullName\":\"Occupant\",\"identificationInfo\":\"AB123456\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reservationStatus").value("CHECKED_IN"));
        assertThrows(StayException.class,() -> checkIn(id,List.of(1L),"AB123456",actor));
    }
    @Test void concurrentCheckinsCannotAssignTheSameRoom() throws Exception {
        long first=booking(1),second=booking(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            Future<?> a=pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                var result=checkIn(first,List.of(1L),"AB123456",actor);
                locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Timeout"); }
                catch(InterruptedException e) { throw new RuntimeException(e); }
                return result;
            }));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            Future<?> b=pool.submit(() -> checkIn(second,List.of(1L),"AB654321",actor));
            assertThrows(TimeoutException.class,() -> b.get(300,TimeUnit.MILLISECONDS));
            release.countDown(); a.get(10,TimeUnit.SECONDS);
            assertInstanceOf(StayException.class,assertThrows(ExecutionException.class,() -> b.get(10,TimeUnit.SECONDS)).getCause());
            assertEquals(1,count("room_assignments"));
            assertEquals("CONFIRMED",jdbc.queryForObject("SELECT status::text FROM hotel.reservations WHERE id=?",String.class,second));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void concurrentCheckoutAndServiceWriteCannotProduceAnUnbilledService() throws Exception {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        checkout.payment(id,n("200"),"CASH","CHECKOUT-KEY",actor);
        jdbc.update("INSERT INTO hotel.services(branch_id,category,name,current_price) VALUES(1,'Test','Test',25)");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch closed=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            Future<?> a=pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                checkout.confirm(id,actor); closed.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Timeout"); }
                catch(InterruptedException e) { throw new RuntimeException(e); }
                return null;
            }));
            assertTrue(closed.await(10,TimeUnit.SECONDS));
            Future<?> b=pool.submit(() -> jdbc.update("INSERT INTO hotel.service_usages(reservation_id,service_id,quantity,unit_price) VALUES(?,1,1,25)",id));
            assertThrows(TimeoutException.class,() -> b.get(300,TimeUnit.MILLISECONDS));
            release.countDown(); a.get(10,TimeUnit.SECONDS);
            assertThrows(ExecutionException.class,() -> b.get(10,TimeUnit.SECONDS));
            assertEquals(0,count("service_usages"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void checkoutRollsBackInvoiceAndRoomChangesOnDatabaseFailure() {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        checkout.payment(id,n("200"),"CASH","ROLLBACK-PAYMENT",actor);
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION hotel.fail_release_test() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF NEW.occupancy='VACANT' THEN RAISE EXCEPTION 'injected release failure'; END IF; RETURN NEW; END $$;
            CREATE TRIGGER fail_release_test BEFORE UPDATE ON hotel.rooms FOR EACH ROW EXECUTE FUNCTION hotel.fail_release_test();
            """);
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,() -> checkout.confirm(id,actor));
            assertEquals(0,count("invoices")); assertEquals(0,count("invoice_lines"));
            assertEquals("CHECKED_IN",jdbc.queryForObject("SELECT status::text FROM hotel.reservations WHERE id=?",String.class,id));
            assertEquals("OCCUPIED",jdbc.queryForObject("SELECT occupancy::text FROM hotel.rooms WHERE id=1",String.class));
            assertNull(jdbc.queryForObject("SELECT invoice_id FROM hotel.payments WHERE reservation_id=?",Long.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM hotel.room_assignments WHERE released_at IS NULL",Integer.class));
        } finally { jdbc.execute("DROP TRIGGER fail_release_test ON hotel.rooms; DROP FUNCTION hotel.fail_release_test()"); }
    }
    @Test void checkinRollsBackIdentityAssignmentsAndRoomsOnDatabaseFailure() {
        long id=booking(2);
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION hotel.fail_checkin_test() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF NEW.status='CHECKED_IN' THEN RAISE EXCEPTION 'injected status failure'; END IF; RETURN NEW; END $$;
            CREATE TRIGGER fail_checkin_test BEFORE UPDATE ON hotel.reservations FOR EACH ROW EXECUTE FUNCTION hotel.fail_checkin_test();
            """);
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,() -> checkIn(id,List.of(1L,2L),"AB123456",actor));
            assertEquals(0,count("room_assignments"));
            assertNull(jdbc.queryForObject("SELECT identity_document FROM hotel.customers WHERE id=1",String.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM hotel.rooms WHERE occupancy='OCCUPIED'",Integer.class));
            assertEquals("CONFIRMED",jdbc.queryForObject("SELECT status::text FROM hotel.reservations WHERE id=?",String.class,id));
        } finally { jdbc.execute("DROP TRIGGER fail_checkin_test ON hotel.reservations; DROP FUNCTION hotel.fail_checkin_test()"); }
    }
    @Test void legacyCheckoutFunctionStillWorksWithInvoiceGuards() {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        checkout.payment(id,n("200"),"CASH","LEGACY-PAYMENT",actor);
        assertNotNull(jdbc.queryForObject("SELECT hotel.checkout_close(?, 'LEGACY-INVOICE',0)",Long.class,id));
        assertEquals("CLOSED",invoices.get(id,actor).status());
    }
    @Test void closesExistingDraftWithoutCreatingASecondInvoice() {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        checkout.payment(id,n("200"),"CASH","DRAFT-PAYMENT",actor);
        long draft=jdbc.queryForObject("INSERT INTO hotel.invoices(reservation_id,invoice_number,room_amount,service_amount,total) VALUES(?,'DRAFT-1',0,0,0) RETURNING id",Long.class,id);
        assertEquals(draft,checkout.confirm(id,actor).invoiceId());
        assertEquals("DRAFT-1",invoices.get(id,actor).number());
        assertEquals(1,count("invoices"));
    }
    @Test void migrationUpgradesInstalledLegacyRoutineAndCanBeAppliedTwice() throws Exception {
        String definition=jdbc.queryForObject("SELECT pg_get_functiondef('hotel.checkout_close(bigint,text,numeric)'::regprocedure)",String.class);
        String old=definition.replace("f.total,'DRAFT',NULL)","f.total,'CLOSED',now())")
            .replace("UPDATE hotel.invoices SET status='CLOSED',closed_at=now() WHERE id=invoice_id;","");
        assertNotEquals(definition,old);
        jdbc.execute(old);
        String migration=Files.readString(Path.of("../Hotel_Database/sql/05_checkin_checkout.sql"));
        jdbc.execute(migration); jdbc.execute(migration);
        legacyCheckoutFunctionStillWorksWithInvoiceGuards();
    }
    @Test void concurrentPaymentRetriesCreateOnlyOnePayment() throws Exception {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        Callable<Bill> task=() -> {
            assertTrue(start.await(10,TimeUnit.SECONDS));
            return checkout.payment(id,n("200"),"CASH","SAME-OBLIGATION",actor);
        };
        try {
            Future<Bill> first=pool.submit(task),second=pool.submit(task); start.countDown();
            assertEquals(0,first.get(10,TimeUnit.SECONDS).remainingAmount().signum());
            assertEquals(0,second.get(10,TimeUnit.SECONDS).remainingAmount().signum());
            assertEquals(1,count("payments"));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    private long paid(long id, String amount, String method, String key) {
        return jdbc.queryForObject("INSERT INTO hotel.payments(reservation_id,amount,method,status,obligation_key) VALUES(?,?,?::hotel.payment_method,'PAID',?) RETURNING id",
            Long.class,id,n(amount),method,key);
    }
    @Test void identityStepBelongsToOccupantAndDoesNotCheckInOrOverwriteBooker() throws Exception {
        long id=booking(1);
        assertThrows(StayException.class,()->checkin.confirm(id,List.of(1L),null,null,actor));
        mvc.perform(post("/api/reservations/"+id+"/checkin/identity").principal(()->"staff@example.test")
            .contentType("application/json").content("{\"fullName\":\"Actual Occupant\",\"identificationInfo\":\"AB123456\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].roomId").exists());
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT status::text FROM hotel.reservations WHERE id=?",String.class,id));
        assertEquals(0,count("room_assignments"));
        assertEquals("Actual Occupant",jdbc.queryForObject("SELECT full_name FROM hotel.stay_guests WHERE reservation_id=?",String.class,id));
        assertNull(jdbc.queryForObject("SELECT identity_document FROM hotel.customers WHERE id=1",String.class));
        mvc.perform(post("/api/reservations/"+id+"/checkin").principal(()->"staff@example.test")
            .contentType("application/json").content("{\"roomIds\":[1]}"))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("AB123456"))));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM hotel.audit_logs WHERE after_data::text LIKE '%AB123456%'",Integer.class));
    }
    @Test void onlyExplicitBookerLinkUpdatesCustomerIdentity() {
        long id=booking(1);
        assertThrows(StayException.class,()->checkin.verifyGuest(id,new StayGuest("Guest","AB123456",99L),actor));
        assertEquals(0,count("stay_guests"));
        checkin.verifyGuest(id,new StayGuest("Guest","AB123456",1L),actor);
        assertEquals("AB123456",jdbc.queryForObject("SELECT identity_document FROM hotel.customers WHERE id=1",String.class));
    }
    @Test void cashRefundUsesPendingThenConfirmationAndReplaysAfterCheckout() throws Exception {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        long payment=paid(id,"250","CASH","CASH-OLD-KEY");
        var f=refunds.request(id,payment,n("50"),"CASH","CASH-REFUND-KEY","Trả dư",actor);
        assertEquals("PENDING",f.status()); assertNull(f.completedAt());
        assertThrows(StayException.class,()->checkout.confirm(id,actor));
        assertThrows(StayException.class,()->refunds.confirmCash(id,f.id(),false,actor));
        assertThrows(StayException.class,()->refunds.request(id,payment,n("1"),"CASH","SECOND-REFUND","Trả dư",actor));
        mvc.perform(post("/api/reservations/"+id+"/checkout/refunds/"+f.id()+"/confirm-cash")
            .principal(()->"staff@example.test").contentType("application/json").content("{\"cashReturned\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        checkout.confirm(id,actor);
        assertEquals("DONE",refunds.request(id,payment,n("50.00"),"CASH","CASH-REFUND-KEY","Trả dư",actor).status());
        assertEquals("DONE",refunds.confirmCash(id,f.id(),true,actor).status());
        assertEquals(1,count("refunds"));
        assertEquals(1,count("invoice_delivery_requests"));
        assertEquals(1L,jdbc.queryForObject("SELECT recorded_by FROM hotel.refunds WHERE id=?",Long.class,f.id()));
    }
    @Test void refundRejectsWrongPaymentMethodKeyAndOverRefundWhileMockQrCompletesImmediately() {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        long other=booking(1),foreignPayment=paid(other,"300","CASH","OTHER-PAYMENT");
        long payment=paid(id,"250","MOCK_QR","QR-OLD-PAYMENT");
        assertThrows(StayException.class,()->refunds.request(id,foreignPayment,n("50"),"CASH","WRONG-PAYMENT","Reason",actor));
        assertThrows(StayException.class,()->refunds.request(id,payment,n("50"),"CASH","WRONG-METHOD","Reason",actor));
        assertThrows(StayException.class,()->refunds.request(id,payment,n("51"),"MOCK_QR","TOO-MUCH-REFUND","Reason",actor));
        var f=refunds.request(id,payment,n("50"),"MOCK_QR","QR-REFUND-KEY","Reason",actor);
        assertEquals("DONE",f.status()); assertNotNull(f.completedAt());
        assertThrows(StayException.class,()->refunds.request(id,payment,n("49"),"MOCK_QR","QR-REFUND-KEY","Reason",actor));
        assertThrows(StayException.class,()->refunds.confirmCash(id,f.id(),true,actor));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "INSERT INTO hotel.refunds(payment_id,reservation_id,amount,status,idempotency_key) VALUES(?,?,1,'DONE','BAD-FK-REFUND')",foreignPayment,id));
        assertDoesNotThrow(()->checkout.confirm(id,actor));
    }
    @Test void refundLimitIsEnforcedPerPaymentAndPendingRefundBlocksEvenAZeroBalance() {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        long small=paid(id,"10","CASH","SMALL-PAYMENT");
        paid(id,"250","CASH","LARGE-PAYMENT");
        assertThrows(StayException.class,()->refunds.request(id,small,n("11"),"CASH","TOO-MUCH-PER-PAYMENT","Reason",actor));
        refunds.request(id,small,n("10"),"CASH","SMALL-REFUND-KEY","Reason",actor);
        jdbc.update("INSERT INTO hotel.extra_charges(reservation_id,description,amount) VALUES(?,'New service',60)",id);
        assertEquals(0,checkout.preview(id,actor).remainingAmount().signum());
        assertEquals("PENDING_REFUND",assertThrows(StayException.class,()->checkout.confirm(id,actor)).code());
    }
    @Test void roomChangePreservesHistoryAndOnlyChangesRemainingRoomInventory() {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        var original=repo.reservation(id,false).actualCheckIn();
        var options=changes.options(id,1,RoomChangeReason.GUEST_REQUEST,actor);
        var deluxe=options.stream().filter(o->o.room().roomId()==4).findFirst().orElseThrow();
        assertEquals(0,n("3798").compareTo(deluxe.adjustment()));
        var result=changes.change(id,1,4,RoomChangeReason.GUEST_REQUEST,deluxe.adjustment(),actor);
        assertEquals(0,n("3798").compareTo(result.adjustment()));
        assertEquals("DIRTY",jdbc.queryForObject("SELECT cleaning::text FROM hotel.rooms WHERE id=1",String.class));
        assertEquals("VACANT",jdbc.queryForObject("SELECT occupancy::text FROM hotel.rooms WHERE id=1",String.class));
        assertEquals("OCCUPIED",jdbc.queryForObject("SELECT occupancy::text FROM hotel.rooms WHERE id=4",String.class));
        assertEquals(2,count("room_assignments"));
        assertEquals(List.of(4L),repo.assignedRoomIds(id));
        assertEquals(original,repo.reservation(id,false).actualCheckIn());
        assertEquals(0,n("200").compareTo(repo.reservation(id,false).roomAmount()));
        assertEquals(0,n("3998").compareTo(checkout.preview(id,actor).grossAmount()));
        var day=repo.reservation(id,false).checkIn();
        assertEquals(0,jdbc.queryForObject("SELECT hotel.available_count(2,?)",Integer.class,day));
        assertEquals(3,jdbc.queryForObject("SELECT hotel.available_count(1,?)",Integer.class,day));
        assertThrows(StayException.class,()->changes.change(id,1,2,RoomChangeReason.GUEST_REQUEST,n("0"),actor));
        checkout.payment(id,n("3998"),"CASH","CHANGED-ROOM-PAYMENT",actor);
        checkout.confirm(id,actor);
        assertEquals(0,repo.assignedRoomIds(id).size());
    }
    @Test void hotelIssueWaivesUpgradeAndDowngradeCreditsChargedRemainingNights() {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        assertEquals(0,changes.change(id,1,4,RoomChangeReason.ROOM_ISSUE,n("0"),actor).adjustment().signum());
        assertEquals(0,n("200").compareTo(checkout.preview(id,actor).grossAmount()));
        // A second move of the same type never reprices a prior free upgrade.
        assertEquals(0,changes.change(id,4,2,RoomChangeReason.ROOM_ISSUE,n("0"),actor).adjustment().signum());
    }
    @Test void pricedUpgradeThenDowngradeUsesAssignmentSnapshotNotChangedCataloguePrice() {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        changes.change(id,1,4,RoomChangeReason.UPGRADE,n("3798"),actor);
        jdbc.update("UPDATE hotel.room_types SET base_price=9999 WHERE id=2");
        // Standard's current price is 999; Deluxe's charged snapshot is 1999, not 9999.
        changes.change(id,4,2,RoomChangeReason.ROOM_ISSUE,n("-2000"),actor);
        assertEquals(0,n("1998").compareTo(checkout.preview(id,actor).grossAmount()));
    }
    @Test void roomChangeRejectsStaleQuoteInsufficientCapacityMaintenanceAndReservedInventory() {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        assertThrows(StayException.class,()->changes.change(id,1,4,RoomChangeReason.UPGRADE,n("1"),actor));
        jdbc.update("UPDATE hotel.reservations SET guest_count=4 WHERE id=?",id);
        assertThrows(StayException.class,()->changes.change(id,1,4,RoomChangeReason.UPGRADE,n("3798"),actor));
        jdbc.update("UPDATE hotel.reservations SET guest_count=1 WHERE id=?",id);
        jdbc.update("INSERT INTO hotel.room_maintenance(room_id,starts_on,ends_on,reason) SELECT 4,check_in,check_out,'Repair' FROM hotel.reservations WHERE id=?",id);
        assertTrue(changes.options(id,1,RoomChangeReason.UPGRADE,actor).stream().noneMatch(x->x.room().roomId()==4));
        assertThrows(StayException.class,()->changes.change(id,1,4,RoomChangeReason.UPGRADE,n("3798"),actor));
        jdbc.update("DELETE FROM hotel.room_maintenance");
        long other=booking(1);
        jdbc.update("UPDATE hotel.reservation_details SET room_type_id=2 WHERE reservation_id=?",other);
        jdbc.update("UPDATE hotel.reservation_nights SET room_type_id=2 WHERE reservation_detail_id IN(SELECT id FROM hotel.reservation_details WHERE reservation_id=?)",other);
        assertThrows(StayException.class,()->changes.change(id,1,4,RoomChangeReason.UPGRADE,n("3798"),actor));
        assertEquals(List.of(1L),repo.assignedRoomIds(id)); assertEquals(0,count("extra_charges"));
    }
    @Test void configuredFreeUpgradeAtCheckinKeepsBookedPriceAndMovesInventory() {
        long id=booking(1);
        jdbc.update("UPDATE hotel.room_types SET upgrade_rank=id WHERE id IN(1,2)");
        jdbc.update("UPDATE hotel.rooms SET cleaning='DIRTY' WHERE room_type_id=1");
        long detail=repo.details(id).get(0).id();
        checkin.confirm(id,null,List.of(new RoomSelectionDTO(detail,4)),new StayGuest("Occupant","AB123456",null),actor);
        assertEquals(List.of(4L),repo.assignedRoomIds(id));
        assertEquals(0,n("200").compareTo(checkout.preview(id,actor).grossAmount()));
        assertEquals(0,jdbc.queryForObject("SELECT hotel.available_count(2,check_in) FROM hotel.reservations WHERE id=?",Integer.class,id));
    }
    @Test void taxIsFrozenAtCheckinAndInvoiceLinesUseNightlyPricesAndTax() {
        long id=booking(1);
        jdbc.update("UPDATE hotel.branches SET tax_rate=0.08 WHERE id=1");
        checkIn(id,List.of(1L),"AB123456",actor);
        jdbc.update("UPDATE hotel.branches SET tax_rate=0.10 WHERE id=1");
        var bill=checkout.preview(id,actor);
        assertEquals(0,n("16").compareTo(bill.taxAmount()));
        assertEquals(0,n("216").compareTo(bill.grossAmount()));
        assertEquals(2,bill.lines().stream().filter(x->x.type().equals("ROOM")).count());
        checkout.payment(id,n("216"),"CASH","TAX-PAYMENT-KEY",actor);
        jdbc.update("INSERT INTO hotel.invoices(reservation_id,invoice_number,room_amount,service_amount,tax_amount,total) VALUES(?,'DRAFT-TAX',200,0,99,299)",id);
        checkout.confirm(id,actor);
        assertEquals(0,n("16").compareTo(jdbc.queryForObject("SELECT tax_amount FROM hotel.invoices WHERE reservation_id=?",BigDecimal.class,id)));
        var invoice=invoices.get(id,actor);
        assertEquals(0,invoice.total().compareTo(invoice.lines().stream().map(InvoiceLine::amount).reduce(BigDecimal.ZERO,BigDecimal::add)));
    }
    @Test void lateCheckoutPolicyUsesScheduledDateTimeAndEarlyDepartureKeepsAllNights() {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        jdbc.update("UPDATE hotel.branches SET late_checkout_fee=50 WHERE id=1");
        var r=repo.reservation(id,false);
        var deadline=r.checkOut().atTime(12,0).atZone(java.time.ZoneId.of(r.timezone())).toInstant();
        var early=new BillingService(repo,java.time.Clock.fixed(deadline.minusSeconds(1),java.time.ZoneOffset.UTC));
        assertEquals(0,n("200").compareTo(early.calculate(r).grossAmount()));
        var late=new BillingService(repo,java.time.Clock.fixed(deadline.plusSeconds(1),java.time.ZoneOffset.UTC));
        assertEquals(0,n("250").compareTo(late.calculate(r).grossAmount()));
        assertEquals(0,count("extra_charges")); // Preview never writes the dynamic surcharge.
    }
    @Test void stayContractsMigrationCanBeAppliedTwiceWithoutRepricingAssignments() throws Exception {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        changes.change(id,1,4,RoomChangeReason.UPGRADE,n("3798"),actor);
        String sql=Files.readString(Path.of("../Hotel_Database/sql/06_stay_contracts.sql"));
        jdbc.execute(sql); jdbc.execute(sql);
        assertEquals(0,n("3998").compareTo(checkout.preview(id,actor).grossAmount()));
        assertEquals(4,count("assignment_nightly_rates"));
    }
    @Test void concurrentRefundRequestsCannotReserveTheSameOverpaymentTwice() throws Exception {
        long id=booking(1); checkIn(id,List.of(1L),"AB123456",actor);
        long payment=paid(id,"250","CASH","RACE-REFUND-PAYMENT");
        var pool=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            var tasks=new ArrayList<Future<Boolean>>();
            for(String key:List.of("RACE-REFUND-A","RACE-REFUND-B")) tasks.add(pool.submit(()->{
                assertTrue(start.await(10,TimeUnit.SECONDS));
                try { refunds.request(id,payment,n("50"),"CASH",key,"Trả dư",actor); return true; }
                catch(StayException e) { assertEquals("REFUND_EXCEEDS_BALANCE",e.code());return false; }
            }));
            start.countDown();
            int successful=0;
            for(var task:tasks) if(task.get(10,TimeUnit.SECONDS)) successful++;
            assertEquals(1,successful); assertEquals(1,count("refunds"));
        } finally { start.countDown();pool.shutdownNow(); }
    }
    @Test void concurrentRoomChangesCannotOccupyTheSameNewRoom() throws Exception {
        long first=booking(1),second=booking(1);
        checkIn(first,List.of(1L),"AB123456",actor);checkIn(second,List.of(2L),"CD123456",actor);
        var pool=Executors.newFixedThreadPool(2);
        var moved=new CountDownLatch(1); var release=new CountDownLatch(1);
        try {
            var a=pool.submit(()->new TransactionTemplate(transactionManager).execute(s->{
                changes.change(first,1,4,RoomChangeReason.ROOM_ISSUE,n("0"),actor);
                moved.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Timeout"); }
                catch(InterruptedException e) { throw new RuntimeException(e); }
                return null;
            }));
            assertTrue(moved.await(10,TimeUnit.SECONDS));
            var b=pool.submit(()->changes.change(second,2,4,RoomChangeReason.ROOM_ISSUE,n("0"),actor));
            assertThrows(TimeoutException.class,()->b.get(300,TimeUnit.MILLISECONDS));
            release.countDown();a.get(10,TimeUnit.SECONDS);
            assertInstanceOf(StayException.class,assertThrows(ExecutionException.class,()->b.get(10,TimeUnit.SECONDS)).getCause());
            assertEquals(List.of(4L),repo.assignedRoomIds(first));assertEquals(List.of(2L),repo.assignedRoomIds(second));
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test void failedRoomChangeRollsBackAssignmentsRatesInventoryAndCharges() {
        long id=booking(1);checkIn(id,List.of(1L),"AB123456",actor);
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION hotel.fail_change_test() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'injected adjustment failure'; END $$;
            CREATE TRIGGER fail_change_test BEFORE INSERT ON hotel.extra_charges FOR EACH ROW EXECUTE FUNCTION hotel.fail_change_test();
            """);
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,()->changes.change(id,1,4,RoomChangeReason.UPGRADE,n("3798"),actor));
            assertEquals(List.of(1L),repo.assignedRoomIds(id));
            assertEquals(1,count("room_assignments"));assertEquals(2,count("assignment_nightly_rates"));
            assertEquals(0,count("stay_inventory_movements"));assertEquals(0,count("extra_charges"));
            assertEquals("READY",jdbc.queryForObject("SELECT cleaning::text FROM hotel.rooms WHERE id=4",String.class));
        } finally {jdbc.execute("DROP TRIGGER fail_change_test ON hotel.extra_charges; DROP FUNCTION hotel.fail_change_test()");}
    }
    @Test void freeUpgradeCannotUseAReadyBookedTypeOrInsufficientTargetInventory() {
        long id=booking(1);long detail=repo.details(id).get(0).id();
        jdbc.update("UPDATE hotel.room_types SET upgrade_rank=id WHERE id IN(1,2)");
        assertThrows(StayException.class,()->checkin.confirm(id,null,List.of(new RoomSelectionDTO(detail,4)),new StayGuest("Occupant","AB123456",null),actor));
        assertEquals(0,count("room_assignments"));assertEquals(0,count("stay_guests"));
        long other=booking(1);
        jdbc.update("UPDATE hotel.reservation_details SET room_type_id=2 WHERE reservation_id=?",other);
        jdbc.update("UPDATE hotel.reservation_nights SET room_type_id=2 WHERE reservation_detail_id IN(SELECT id FROM hotel.reservation_details WHERE reservation_id=?)",other);
        jdbc.update("UPDATE hotel.rooms SET cleaning='DIRTY' WHERE room_type_id=1");
        assertThrows(StayException.class,()->checkin.confirm(id,null,List.of(new RoomSelectionDTO(detail,4)),new StayGuest("Occupant","AB123456",null),actor));
        assertEquals(0,count("room_assignments"));
    }
    @Test void roomFilterAndClosedFinancialGuardsStillApplyToNewEndpoints() throws Exception {
        long id=booking(1);
        jdbc.update("UPDATE hotel.rooms SET floor=3,view_direction='SEA' WHERE id=2");
        mvc.perform(get("/api/reservations/"+id+"/checkin/available-rooms?floor=3&viewDirection=SEA").principal(()->"staff@example.test"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].roomId").value(2));
        checkIn(id,List.of(1L),"AB123456",actor);
        assertThrows(StayException.class,()->changes.options(id,1,RoomChangeReason.UPGRADE,new Receptionist(1,2,"RECEPTIONIST")));
        mvc.perform(post("/api/reservations/"+id+"/checkout/refunds").contentType("application/json")
            .content("{\"paymentId\":1,\"amount\":1,\"method\":\"CASH\",\"idempotencyKey\":\"NOT-AUTHENTICATED\",\"reason\":\"Reason\"}"))
            .andExpect(status().isUnauthorized());
        paid(id,"200","CASH","EXACT-PAYMENT");checkout.confirm(id,actor);
        assertThrows(StayException.class,()->changes.options(id,1,RoomChangeReason.GUEST_REQUEST,actor));
        assertThrows(StayException.class,()->refunds.request(id,1,n("1"),"CASH","AFTER-CLOSE-KEY","Reason",actor));
    }
    @Test void roomChangeAfterFirstNightOnlyPricesRemainingNights() {
        long id=booking(1);
        jdbc.update("UPDATE hotel.reservations SET check_out=check_out+1,room_subtotal=300,quoted_total=300 WHERE id=?",id);
        jdbc.update("UPDATE hotel.reservation_details SET subtotal=300 WHERE reservation_id=?",id);
        jdbc.update("""
            INSERT INTO hotel.reservation_nights(reservation_detail_id,room_type_id,stay_date,quantity,nightly_price)
            SELECT d.id,1,r.check_out-1,1,100 FROM hotel.reservations r JOIN hotel.reservation_details d ON d.reservation_id=r.id WHERE r.id=?
            """,id);
        checkIn(id,List.of(1L),"AB123456",actor);
        var r=repo.reservation(id,false);
        var tomorrow=r.checkIn().plusDays(1).atTime(10,0).atZone(java.time.ZoneId.of(r.timezone())).toInstant();
        var clock=java.time.Clock.fixed(tomorrow,java.time.ZoneOffset.UTC);
        var later=new RoomChangeService(repo,clock,new BillingService(repo,clock));
        new TransactionTemplate(transactionManager).execute(s->later.change(id,1,4,RoomChangeReason.UPGRADE,n("3798"),actor));
        assertEquals(0,n("4098").compareTo(checkout.preview(id,actor).grossAmount()));
        assertEquals(2,repo.assignmentRates(repo.assignment(id,4).id(),r.checkIn(),r.checkOut()).size());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM hotel.stay_inventory_movements WHERE room_type_id=2 AND stay_date=?",Integer.class,r.checkIn().plusDays(1)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM hotel.stay_inventory_movements WHERE stay_date=?",Integer.class,r.checkIn()));
    }
    @Test void httpStayFlowPreservesRequestsAndResponseFieldsAfterPackageRefactor() throws Exception {
        long id=booking(1);
        String url="/api/reservations/"+id;
        mvc.perform(post(url+"/checkin/identity").principal(()->"staff@example.test").contentType("application/json")
            .content("{\"fullName\":\"Occupant\",\"identificationInfo\":\"AB123456\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].roomId").value(1))
            .andExpect(jsonPath("$[0].roomNumber").value("101"));
        mvc.perform(post(url+"/checkin").principal(()->"staff@example.test").contentType("application/json")
            .content("{\"roomIds\":[1]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reservationId").value(id))
            .andExpect(jsonPath("$.rooms[0].occupancy").value("OCCUPIED"))
            .andExpect(jsonPath("$.actualCheckInTime").exists());
        mvc.perform(get(url+"/room-change/available-rooms?oldRoomId=1&reason=ROOM_ISSUE").principal(()->"staff@example.test"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].room.roomId").exists())
            .andExpect(jsonPath("$[0].adjustment").exists());
        mvc.perform(post(url+"/room-change").principal(()->"staff@example.test").contentType("application/json")
            .content("{\"oldRoomId\":1,\"newRoomId\":4,\"reason\":\"ROOM_ISSUE\",\"expectedAdjustment\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.newRoomId").value(4));
        mvc.perform(get(url+"/checkout/preview").principal(()->"staff@example.test"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.roomAmount").value(200))
            .andExpect(jsonPath("$.remainingAmount").value(200))
            .andExpect(jsonPath("$.lines.length()").value(2)).andExpect(jsonPath("$.lines[0].type").value("ROOM"))
            .andExpect(jsonPath("$.taxAmount").value(0));
        mvc.perform(post(url+"/checkout/payment").principal(()->"staff@example.test").contentType("application/json")
            .content("{\"amount\":200,\"method\":\"CASH\",\"obligationKey\":\"short\"}"))
            .andExpect(status().isBadRequest());
        assertEquals(0,count("payments"));
        mvc.perform(post(url+"/checkout/payment").principal(()->"staff@example.test").contentType("application/json")
            .content("{\"amount\":200,\"method\":\"CASH\",\"obligationKey\":\"HTTP-COUNTER-PAYMENT\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.paidAmount").value(200))
            .andExpect(jsonPath("$.remainingAmount").value(0));
        mvc.perform(post(url+"/checkout").principal(()->"staff@example.test"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reservationStatus").value("CHECKED_OUT"))
            .andExpect(jsonPath("$.invoiceStatus").value("CLOSED")).andExpect(jsonPath("$.invoiceId").exists());
        mvc.perform(get(url+"/checkout/invoice").principal(()->"staff@example.test"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.number").exists())
            .andExpect(jsonPath("$.reservationId").value(id)).andExpect(jsonPath("$.total").value(200))
            .andExpect(jsonPath("$.lines[0].quantity").value(1))
            .andExpect(jsonPath("$.lines[0].unitPrice").value(100));
    }
}
