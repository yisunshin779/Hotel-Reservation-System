package com.Hotel.Module_Payment;

import com.Hotel.Module_Payment.dto.*;
import com.Hotel.Module_Payment.entity.*;
import com.Hotel.Module_Payment.service.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=none","hotel.payment.expiry-enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentIntegrationTest {
    private static final EmbeddedPostgres PG=start();
    private static final String HASH=new BCryptPasswordEncoder().encode("test-password");
    @MockBean org.redisson.api.RedissonClient redis;
    @MockBean com.Hotel.Module_DatPhong.service.BookingService booking;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired ReportIdentityService identities;
    @Autowired MockMvc mvc;
    @Autowired CheckInService checkin;
    @Autowired CheckOutService checkout;
    @Autowired PlatformTransactionManager manager;
    private static EmbeddedPostgres start() { try { return EmbeddedPostgres.builder().setPort(0).start(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres"); r.add("spring.datasource.password",()->"");
    }
    @BeforeAll void schema() throws Exception {
        for(String file:List.of("01_schema.sql","02_operations.sql","05_checkin_checkout.sql","06_stay_contracts.sql","07_payment.sql","07_payment.sql","08_invoice_adjustments.sql"))
            jdbc.execute(Files.readString(Path.of("../Hotel_Database/sql",file)));
    }
    @AfterAll static void close() throws Exception { PG.close(); }
    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE hotel.branches,hotel.roles,hotel.customers,hotel.promotions RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO hotel.branches(name,address,city) VALUES('One','One','One'),('Two','Two','Two')");
        jdbc.update("INSERT INTO hotel.roles(code,name) VALUES('GUEST','Guest'),('RECEPTIONIST','Staff')");
        jdbc.update("INSERT INTO hotel.users(email,full_name,password_hash,role_id,branch_id) VALUES('guest@test','Guest',?,1,NULL),('other@test','Other',?,1,NULL),('staff@test','Staff',?,2,1),('staff2@test','Staff2',?,2,2)",HASH,HASH,HASH,HASH);
        jdbc.update("INSERT INTO hotel.customers(user_id,full_name) VALUES(1,'Guest'),(2,'Other')");
        jdbc.update("INSERT INTO hotel.room_types(branch_id,name,capacity,base_price) VALUES(1,'Standard',2,100)");
        jdbc.update("INSERT INTO hotel.rooms(branch_id,room_type_id,room_number) VALUES(1,1,'101')");
    }
    private long reservation() {
        return jdbc.queryForObject("SELECT hotel.make_reservation(1,1,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+2,'[{\"room_type_id\":1,\"quantity\":1}]'::jsonb,?)",Long.class,"PAY-"+UUID.randomUUID());
    }
    private ReportIdentityService.Identity guest() { return identities.find("guest@test"); }
    private PaymentSummary select(long id,PaymentMethod method,PaymentPlan plan) { return payments.select(id,new SelectPaymentRequest(method,plan),guest()); }
    private String url(long id) { return "/api/reservations/"+id+"/payments"; }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM hotel."+table,Long.class); }
    private String state(long id) { return jdbc.queryForObject("SELECT status::text FROM hotel.reservations WHERE id=?",String.class,id); }
    private void decimal(String expected,BigDecimal actual) { assertEquals(0,new BigDecimal(expected).compareTo(actual)); }

    @Test void qrHasTwoStepsAndRetriesProduceOnePaymentAndOneNotification() throws Exception {
        long id=reservation();
        mvc.perform(post(url(id)).with(httpBasic("guest@test","test-password")).contentType("application/json").content("{\"method\":\"QR\",\"plan\":\"DEPOSIT\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reservationStatus").value("PENDING_PAYMENT"))
            .andExpect(jsonPath("$.intent.paymentStatus").value("PENDING")).andExpect(jsonPath("$.intent.amount").value(60));
        var pending=payments.options(id,guest());
        decimal("0",pending.paidAmount()); assertEquals(0,count("booking_confirmation_requests"));
        assertTrue(pending.intent().qrContent().startsWith("MOCK|"));
        assertEquals(pending.intent().id(),select(id,PaymentMethod.QR,PaymentPlan.DEPOSIT).intent().id());
        var result=payments.confirmQr(id,pending.intent().id(),guest());
        decimal("60",result.paidAmount()); decimal("140",result.remainingAmount());
        assertEquals("CONFIRMED",result.reservationStatus()); assertNotNull(result.holdExpiresAt());
        assertEquals("PENDING",result.confirmationStatus());
        var again=payments.confirmQr(id,pending.intent().id(),guest());
        assertEquals(result.intent().id(),again.intent().id()); assertEquals(1,count("payments")); assertEquals(1,count("booking_confirmation_requests"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM hotel.audit_logs WHERE action='MOCK_QR_CONFIRMED'",Integer.class));
    }
    @Test void cashConfirmsWithoutMarkingPaidThenUpdatesTheSameObligation() throws Exception {
        long id=reservation(); var chosen=select(id,PaymentMethod.CASH,PaymentPlan.FULL);
        assertEquals("CONFIRMED",chosen.reservationStatus()); assertNull(chosen.holdExpiresAt());
        assertEquals("UNPAID",chosen.intent().paymentStatus()); decimal("0",chosen.paidAmount());
        assertEquals(chosen.intent().id(),select(id,PaymentMethod.CASH,PaymentPlan.FULL).intent().id());
        String path=url(id)+"/"+chosen.intent().id()+"/collect-cash";
        mvc.perform(post(path).with(httpBasic("staff@test","test-password")).contentType("application/json").content("{\"cashReceived\":false}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(httpBasic("guest@test","test-password")).contentType("application/json").content("{\"cashReceived\":true}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(path).with(httpBasic("staff2@test","test-password")).contentType("application/json").content("{\"cashReceived\":true}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(path).with(httpBasic("staff@test","test-password")).contentType("application/json").content("{\"cashReceived\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.paidAmount").value(200));
        payments.collectCash(id,chosen.intent().id(),new CashCollectionRequest(true),identities.find("staff@test"));
        assertEquals(1,count("payments"));
        assertEquals(3,jdbc.queryForObject("SELECT recorded_by FROM hotel.payments",Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT paid_at FROM hotel.payments",java.sql.Timestamp.class));
    }
    @Test void cancellingQrLeavesReservationPendingAndOldQrCannotBeReplayed() {
        long id=reservation(); var first=select(id,PaymentMethod.QR,PaymentPlan.FULL);
        payments.cancelQr(id,first.intent().id(),guest());
        assertEquals("PENDING_PAYMENT",state(id)); decimal("0",payments.options(id,guest()).paidAmount());
        var second=select(id,PaymentMethod.QR,PaymentPlan.DEPOSIT);
        assertNotEquals(first.intent().id(),second.intent().id());
        assertThrows(PaymentException.class,()->payments.confirmQr(id,first.intent().id(),guest()));
        payments.confirmQr(id,second.intent().id(),guest());
        decimal("60",payments.options(id,guest()).paidAmount());
    }
    @Test void latestSelectionDoesNotDependOnTransactionStartTimeOrClockOrdering() {
        long id=reservation(); var old=select(id,PaymentMethod.QR,PaymentPlan.FULL);
        jdbc.update("UPDATE hotel.payment_intents SET created_at=clock_timestamp()+interval '1 day' WHERE id=?",old.intent().id());
        var cash=select(id,PaymentMethod.CASH,PaymentPlan.DEPOSIT);
        assertEquals(cash.intent().id(),payments.options(id,guest()).intent().id());
        assertEquals("UNPAID",payments.options(id,guest()).intent().paymentStatus());
    }
    @Test void changingToCashInvalidatesTheOldQr() {
        long id=reservation(); var qr=select(id,PaymentMethod.QR,PaymentPlan.FULL);
        select(id,PaymentMethod.CASH,PaymentPlan.FULL);
        assertThrows(PaymentException.class,()->payments.confirmQr(id,qr.intent().id(),guest()));
        decimal("0",payments.options(id,guest()).paidAmount());
    }
    @Test void expiredHoldRejectsConfirmationAndExistingJobReleasesInventory() {
        long id=reservation(); var qr=select(id,PaymentMethod.QR,PaymentPlan.FULL);
        jdbc.update("UPDATE hotel.reservations SET hold_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id);
        assertTrue(payments.options(id,guest()).expired());
        assertEquals("HOLD_EXPIRED",assertThrows(PaymentException.class,()->payments.confirmQr(id,qr.intent().id(),guest())).code());
        new PaymentHoldExpiryJob(jdbc).expire(); new PaymentHoldExpiryJob(jdbc).expire();
        assertEquals("CANCELLED",state(id)); decimal("0",payments.options(id,guest()).paidAmount());
        assertEquals(1,jdbc.queryForObject("SELECT hotel.available_count(1,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)",Integer.class));
    }
    @Test void noCallerCanSupplyAmountOrUseAnotherCustomersReservation() throws Exception {
        long id=reservation();
        mvc.perform(get(url(id))).andExpect(status().isUnauthorized());
        mvc.perform(get(url(id)).header("X-User-Id","1")).andExpect(status().isUnauthorized());
        mvc.perform(get(url(id)).with(httpBasic("other@test","test-password"))).andExpect(status().isForbidden());
        mvc.perform(post(url(id)).with(httpBasic("other@test","test-password")).contentType("application/json").content("{\"method\":\"QR\",\"plan\":\"FULL\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(url(id)).with(httpBasic("guest@test","test-password")).contentType("application/json").content("{\"method\":\"CARD\",\"plan\":\"FULL\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(url(id)).with(httpBasic("guest@test","test-password")).contentType("application/json").content("{\"method\":\"QR\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(url(id)).with(httpBasic("guest@test","test-password")).contentType("application/json").content("{\"method\":\"QR\",\"plan\":\"FULL\",\"amount\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.intent.amount").value(200));
        assertEquals(1,count("payments"));
    }
    @Test void qrMustHaveAnActiveSelectionAndSnapshotPrice() {
        long id=reservation();
        assertThrows(PaymentException.class,()->payments.confirmQr(id,UUID.randomUUID(),guest()));
        var qr=select(id,PaymentMethod.QR,PaymentPlan.FULL);
        jdbc.update("UPDATE hotel.reservations SET quoted_total=250 WHERE id=?",id);
        assertEquals("PRICE_CHANGED",assertThrows(PaymentException.class,()->payments.confirmQr(id,qr.intent().id(),guest())).code());
        decimal("0",payments.options(id,guest()).paidAmount());
    }
    @Test void cashObligationIsSharedWithCheckoutWithoutCreatingAnotherPayment() {
        long id=reservation(); var cash=select(id,PaymentMethod.CASH,PaymentPlan.FULL);
        var staff=new Receptionist(3,1,"RECEPTIONIST");
        checkin.confirm(id,List.of(1L),null,new StayGuest("Occupant","AB123456",null),staff);
        checkout.payment(id,new BigDecimal("200"),"CASH",cash.intent().obligationKey(),staff);
        payments.collectCash(id,cash.intent().id(),new CashCollectionRequest(true),identities.find("staff@test"));
        assertEquals(1,count("payments"));
        checkout.confirm(id,staff);
        var replay=payments.collectCash(id,cash.intent().id(),new CashCollectionRequest(true),identities.find("staff@test"));
        assertEquals("CHECKED_OUT",replay.reservationStatus()); assertEquals(1,count("payments"));
    }
    @Test void cashCollectedDuringStayCanBeReplayedByCheckout() {
        long id=reservation(); var cash=select(id,PaymentMethod.CASH,PaymentPlan.FULL);
        var staff=new Receptionist(3,1,"RECEPTIONIST");
        checkin.confirm(id,List.of(1L),null,new StayGuest("Occupant","AB123456",null),staff);
        payments.collectCash(id,cash.intent().id(),new CashCollectionRequest(true),identities.find("staff@test"));
        checkout.payment(id,new BigDecimal("200"),"CASH",cash.intent().obligationKey(),staff);
        assertEquals(1,count("payments")); decimal("200",checkout.preview(id,staff).paidAmount());
    }
    @Test void parallelQrConfirmationIsIdempotent() throws Exception {
        long id=reservation(); var qr=select(id,PaymentMethod.QR,PaymentPlan.FULL); var actor=guest();
        var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            Callable<PaymentSummary> task=()->{start.await(); return payments.confirmQr(id,qr.intent().id(),actor);};
            var a=pool.submit(task); var b=pool.submit(task); start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS).intent().paymentId(),b.get(10,TimeUnit.SECONDS).intent().paymentId());
            assertEquals(1,count("payments")); assertEquals(1,count("booking_confirmation_requests"));
        } finally {pool.shutdownNow();}
    }
    @Test void expiryIsRecheckedAfterWaitingForTheReservationLock() throws Exception {
        long id=reservation(); var qr=select(id,PaymentMethod.QR,PaymentPlan.FULL); var actor=guest();
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var attempting=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var holder=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                jdbc.queryForList("SELECT id FROM hotel.reservations WHERE id=? FOR UPDATE",id);
                locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
                catch(InterruptedException e) {throw new RuntimeException(e);}
                jdbc.update("UPDATE hotel.reservations SET hold_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id);
                return null;
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var confirmation=pool.submit(()->{attempting.countDown(); return assertThrows(PaymentException.class,()->payments.confirmQr(id,qr.intent().id(),actor)).code();});
            assertTrue(attempting.await(5,TimeUnit.SECONDS)); release.countDown(); holder.get(10,TimeUnit.SECONDS);
            assertEquals("HOLD_EXPIRED",confirmation.get(10,TimeUnit.SECONDS)); decimal("0",payments.options(id,guest()).paidAmount());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void failureToQueueConfirmationRollsBackThePaymentAndReservation() {
        long id=reservation(); var qr=select(id,PaymentMethod.QR,PaymentPlan.FULL);
        jdbc.execute("ALTER TABLE hotel.booking_confirmation_requests ADD CONSTRAINT reject_test_request CHECK(reservation_id<0)");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,()->payments.confirmQr(id,qr.intent().id(),guest()));
            assertEquals("PENDING_PAYMENT",state(id));
            assertEquals("PENDING",payments.options(id,guest()).intent().paymentStatus());
            decimal("0",payments.options(id,guest()).paidAmount());
        } finally {jdbc.execute("ALTER TABLE hotel.booking_confirmation_requests DROP CONSTRAINT reject_test_request");}
    }
    @Test void cashierCannotCollectForCancelledOrClosedReservation() {
        long id=reservation(); var cash=select(id,PaymentMethod.CASH,PaymentPlan.FULL);
        jdbc.update("UPDATE hotel.reservations SET status='CANCELLED' WHERE id=?",id);
        assertThrows(PaymentException.class,()->payments.collectCash(id,cash.intent().id(),new CashCollectionRequest(true),identities.find("staff@test")));
        decimal("0",payments.options(id,guest()).paidAmount());
    }
    @Test void depositUsesExactDecimalRounding() {
        decimal("30.01",PaymentPlan.DEPOSIT.amount(new BigDecimal("100.02")));
        decimal("100.02",PaymentPlan.FULL.amount(new BigDecimal("100.02")));
    }
}
