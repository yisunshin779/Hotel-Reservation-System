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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=none","hotel.payment.expiry-enabled=false",
    "hotel.notifications.dispatch-enabled=false","management.health.mail.enabled=false","logging.level.root=WARN"})
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
    @Autowired com.Hotel.notification.NotificationDispatcher dispatcher;
    @MockBean org.springframework.mail.javamail.JavaMailSender mail;
    private static EmbeddedPostgres start() { try { return EmbeddedPostgres.builder().setPort(0).start(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres"); r.add("spring.datasource.password",()->"");
        // Built before the context starts: an operational start-up requires the Payment and Checkout tables.
        var jdbc=new JdbcTemplate(PG.getPostgresDatabase());
        for(String file:List.of("01_schema.sql","02_operations.sql","05_checkin_checkout.sql","06_stay_contracts.sql","07_payment.sql","07_payment.sql","08_invoice_adjustments.sql"))
            jdbc.execute(Files.readString(Path.of("../Hotel_Database/sql",file)));
        // V12 is applied on top of the delivered scripts, as Flyway does after baseline 8.
        try(var sql=PaymentIntegrationTest.class.getResourceAsStream("/db/migration/V12__payment_expiry_and_outbox.sql")) {
            jdbc.execute(new String(sql.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
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

    @Test void ownerCanCancelUnpaidHoldAndReleaseItsActivePaymentSelection() throws Exception {
        long id = reservation();
        var selected = select(id, PaymentMethod.QR, PaymentPlan.FULL);
        String cancelUrl = "/api/reservations/" + id + "/cancel";
        mvc.perform(post(cancelUrl).with(httpBasic("other@test", "test-password")))
                .andExpect(status().isForbidden());
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(post(cancelUrl).with(httpBasic("guest@test", "test-password")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.reservationStatus").value("CANCELLED"))
                    .andExpect(jsonPath("$.intent.state").value("CANCELLED"));
        }
        assertThrows(PaymentException.class, () -> payments.confirmQr(id, selected.intent().id(), guest()));
        assertEquals("CANCELLED", state(id));
    }

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

    // ---- #1 expiry releases the payment selection in the same transaction ----
    private String intentState(UUID intent) { return jdbc.queryForObject("SELECT state FROM hotel.payment_intents WHERE id=?",String.class,intent); }
    private String paymentStatus(UUID intent) {
        return jdbc.queryForObject("SELECT p.status::text FROM hotel.payments p JOIN hotel.payment_intents i ON i.payment_id=p.id WHERE i.id=?",String.class,intent);
    }
    private void addRooms(int count) {
        for(int i=0;i<count;i++) jdbc.update("INSERT INTO hotel.rooms(branch_id,room_type_id,room_number) VALUES(1,1,?)","X"+(200+i));
    }
    private void expireNow(long id) { jdbc.update("UPDATE hotel.reservations SET hold_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",id); }

    @Test void expiryCancelsActiveQrSelectionAndPendingPaymentAndIsIdempotent() {
        addRooms(1); long id=reservation(); var qr=select(id,PaymentMethod.QR,PaymentPlan.FULL);
        long cash=reservation(); var cashIntent=select(cash,PaymentMethod.CASH,PaymentPlan.FULL);
        expireNow(id);
        assertEquals(1,jdbc.queryForObject("SELECT hotel.expire_holds()",Integer.class));
        assertEquals("CANCELLED",state(id));
        assertEquals("CANCELLED",intentState(qr.intent().id()));
        assertEquals("FAILED",paymentStatus(qr.intent().id()));
        // A second run, and the job itself, find nothing left to change.
        assertEquals(0,jdbc.queryForObject("SELECT hotel.expire_holds()",Integer.class));
        new PaymentHoldExpiryJob(jdbc).expire();
        assertEquals("FAILED",paymentStatus(qr.intent().id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM hotel.audit_logs WHERE action='CANCEL_RESERVATION' AND entity_id=?",Integer.class,id));
        // The confirmed cash reservation keeps its COMPLETED intent and UNPAID obligation.
        assertEquals("CONFIRMED",state(cash));
        assertEquals("COMPLETED",intentState(cashIntent.intent().id()));
        assertEquals("UNPAID",paymentStatus(cashIntent.intent().id()));
        assertEquals("INVALID_RESERVATION_STATE",assertThrows(PaymentException.class,()->payments.confirmQr(id,qr.intent().id(),guest())).code());
    }

    @Test void expiryReturnsInventoryAndReversesPromotionUse() {
        jdbc.update("INSERT INTO hotel.promotions(code,name,kind,amount,starts_at,ends_at) VALUES('V12','V12',"
            +"'FIXED',10,now()-interval '1 day',now()+interval '1 day')");
        long id=jdbc.queryForObject("SELECT hotel.make_reservation(1,1,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+2,"
            +"'[{\"room_type_id\":1,\"quantity\":1}]'::jsonb,?,false,'ONLINE','V12')",Long.class,"PR-"+UUID.randomUUID());
        select(id,PaymentMethod.QR,PaymentPlan.FULL);
        assertEquals(0,jdbc.queryForObject("SELECT hotel.available_count(1,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)",Integer.class));
        expireNow(id); jdbc.queryForObject("SELECT hotel.expire_holds()",Integer.class);
        assertEquals(1,jdbc.queryForObject("SELECT hotel.available_count(1,(now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM hotel.promotion_usages WHERE reservation_id=? AND reversed_at IS NOT NULL",Integer.class,id));
    }

    @Test void repairReleasesOnlyExpiredUnpaidLeftoversAndListsTheRest() {
        // Reproduce the pre-V12 state: cancelled reservation, ACTIVE intent, PENDING QR payment.
        addRooms(2); long stale=reservation(); var staleQr=select(stale,PaymentMethod.QR,PaymentPlan.FULL);
        long noEvidence=reservation(); var noEvidenceQr=select(noEvidence,PaymentMethod.QR,PaymentPlan.FULL);
        long paid=reservation(); var paidQr=select(paid,PaymentMethod.QR,PaymentPlan.DEPOSIT);
        jdbc.update("INSERT INTO hotel.payments(reservation_id,amount,method,status,obligation_key,paid_at) VALUES(?,10,'CASH','PAID',?,now())",paid,"LEGACY-"+UUID.randomUUID());
        expireNow(stale);
        jdbc.update("UPDATE hotel.reservations SET hold_expires_at=clock_timestamp()+interval '1 hour' WHERE id=?",noEvidence);
        jdbc.update("UPDATE hotel.reservations SET status='CANCELLED' WHERE id IN (?,?,?)",stale,noEvidence,paid);
        expireNow(paid);

        assertEquals(1,jdbc.queryForObject("SELECT hotel.repair_expired_payment_selections()",Integer.class));
        assertEquals("CANCELLED",intentState(staleQr.intent().id())); assertEquals("FAILED",paymentStatus(staleQr.intent().id()));
        assertEquals("ACTIVE",intentState(noEvidenceQr.intent().id())); assertEquals("PENDING",paymentStatus(noEvidenceQr.intent().id()));
        assertEquals("ACTIVE",intentState(paidQr.intent().id())); assertEquals("PENDING",paymentStatus(paidQr.intent().id()));
        assertEquals("RELEASED",jdbc.queryForObject("SELECT outcome FROM hotel.payment_reconciliation_items WHERE intent_id=?",String.class,staleQr.intent().id()));
        assertEquals("NO_EXPIRED_HOLD_EVIDENCE",jdbc.queryForObject("SELECT reason FROM hotel.payment_reconciliation_items WHERE intent_id=?",String.class,noEvidenceQr.intent().id()));
        assertEquals("CANCELLED_WITH_PAID_PAYMENT",jdbc.queryForObject("SELECT reason FROM hotel.payment_reconciliation_items WHERE intent_id=?",String.class,paidQr.intent().id()));
        // Repeatable: nothing else changes and no duplicate rows are recorded.
        assertEquals(0,jdbc.queryForObject("SELECT hotel.repair_expired_payment_selections()",Integer.class));
        assertEquals(3,count("payment_reconciliation_items"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM hotel.payments WHERE reservation_id=? AND status='PAID'",Integer.class,paid));
    }

    @Test void expiryJobWinningTheLockMakesQrConfirmationFailConsistently() throws Exception {
        long id=reservation(); var qr=select(id,PaymentMethod.QR,PaymentPlan.FULL); var actor=guest();
        expireNow(id);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var job=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                jdbc.queryForList("SELECT id FROM hotel.reservations WHERE id=? FOR UPDATE",id);
                int expired=jdbc.queryForObject("SELECT hotel.expire_holds()",Integer.class);
                locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
                catch(InterruptedException e) {throw new RuntimeException(e);}
                return expired;
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var confirm=pool.submit(()->assertThrows(PaymentException.class,()->payments.confirmQr(id,qr.intent().id(),actor)).code());
            Thread.sleep(300); release.countDown();
            assertEquals(1,job.get(10,TimeUnit.SECONDS));
            assertEquals("INVALID_RESERVATION_STATE",confirm.get(10,TimeUnit.SECONDS));
        } finally {release.countDown();pool.shutdownNow();}
        assertEquals("CANCELLED",state(id)); assertEquals("CANCELLED",intentState(qr.intent().id()));
        assertEquals("FAILED",paymentStatus(qr.intent().id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM hotel.payments WHERE reservation_id=? AND status='PAID'",Integer.class,id));
        assertEquals(0,count("booking_confirmation_requests"));
    }

    @Test void qrConfirmationWinningTheLockMakesExpirySkipTheReservation() throws Exception {
        long id=reservation(); var qr=select(id,PaymentMethod.QR,PaymentPlan.FULL); var actor=guest();
        jdbc.update("UPDATE hotel.reservations SET hold_expires_at=clock_timestamp()+interval '1 second' WHERE id=?",id);
        var confirmed=new CountDownLatch(1); var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var payer=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                var result=payments.confirmQr(id,qr.intent().id(),actor);
                confirmed.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Timed out"); }
                catch(InterruptedException e) {throw new RuntimeException(e);}
                return result.reservationStatus();
            }));
            assertTrue(confirmed.await(5,TimeUnit.SECONDS));
            Thread.sleep(1500); // the hold is now past its deadline, but the row is locked by the payer
            assertEquals(0,pool.submit(()->jdbc.queryForObject("SELECT hotel.expire_holds()",Integer.class)).get(10,TimeUnit.SECONDS));
            release.countDown();
            assertEquals("CONFIRMED",payer.get(10,TimeUnit.SECONDS));
        } finally {release.countDown();pool.shutdownNow();}
        assertEquals(0,jdbc.queryForObject("SELECT hotel.expire_holds()",Integer.class));
        assertEquals("CONFIRMED",state(id)); assertEquals("COMPLETED",intentState(qr.intent().id()));
        assertEquals("PAID",paymentStatus(qr.intent().id()));
    }

    // ---- #14 booking confirmation and invoice delivery worker ----
    private String request(String table,String key,long id,String column) {
        return jdbc.queryForObject("SELECT "+column+"::text FROM hotel."+table+" WHERE "+key+"=?",String.class,id);
    }

    @Test void confirmationWorkerSendsOnceAndRecordsSent() {
        long id=reservation(); select(id,PaymentMethod.CASH,PaymentPlan.FULL);
        var captor=org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
        assertEquals(1,dispatcher.dispatchOnce());
        org.mockito.Mockito.verify(mail).send(captor.capture());
        assertArrayEquals(new String[]{"guest@test"},captor.getValue().getTo());
        assertTrue(captor.getValue().getSubject().contains(jdbc.queryForObject("SELECT code FROM hotel.reservations WHERE id=?",String.class,id)));
        assertEquals("SENT",request("booking_confirmation_requests","reservation_id",id,"status"));
        assertNotNull(request("booking_confirmation_requests","reservation_id",id,"sent_at"));
        assertEquals("SENT",payments.options(id,guest()).confirmationStatus());
        assertEquals(0,dispatcher.dispatchOnce());
        org.mockito.Mockito.verify(mail,org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
    }

    @Test void confirmationWorkerRetriesSmtpFailuresThenStopsAtMaxAttempts() {
        long id=reservation(); select(id,PaymentMethod.CASH,PaymentPlan.FULL);
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
            .when(mail).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        assertEquals(1,dispatcher.dispatchOnce());
        assertEquals("PENDING",request("booking_confirmation_requests","reservation_id",id,"status"));
        assertEquals("1",request("booking_confirmation_requests","reservation_id",id,"attempts"));
        assertEquals("SEND_ERROR",request("booking_confirmation_requests","reservation_id",id,"last_error"));
        assertEquals(0,dispatcher.dispatchOnce()); // backoff: not due yet
        for(int attempt=2;attempt<=5;attempt++) {
            jdbc.update("UPDATE hotel.booking_confirmation_requests SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE reservation_id=?",id);
            assertEquals(1,dispatcher.dispatchOnce());
        }
        assertEquals("FAILED",request("booking_confirmation_requests","reservation_id",id,"status"));
        assertEquals("5",request("booking_confirmation_requests","reservation_id",id,"attempts"));
        // Recovery after a transient failure is covered here: reset and SMTP works again.
        org.mockito.Mockito.reset(mail);
        jdbc.update("UPDATE hotel.booking_confirmation_requests SET status='PENDING',attempts=1,next_attempt_at=clock_timestamp()-interval '1 second' WHERE reservation_id=?",id);
        assertEquals(1,dispatcher.dispatchOnce());
        assertEquals("SENT",request("booking_confirmation_requests","reservation_id",id,"status"));
    }

    @Test void concurrentWorkersNeverClaimTheSameConfirmation() throws Exception {
        long id=reservation(); select(id,PaymentMethod.CASH,PaymentPlan.FULL);
        var sending=new CountDownLatch(1); var release=new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call->{ sending.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); return null; })
            .when(mail).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->dispatcher.dispatchOnce());
            assertTrue(sending.await(5,TimeUnit.SECONDS));
            assertEquals(0,pool.submit(()->dispatcher.dispatchOnce()).get(10,TimeUnit.SECONDS));
            release.countDown();
            assertEquals(1,first.get(10,TimeUnit.SECONDS));
        } finally {release.countDown();pool.shutdownNow();}
        org.mockito.Mockito.verify(mail,org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        assertEquals("SENT",request("booking_confirmation_requests","reservation_id",id,"status"));
    }

    @Test void staleLeaseCannotOverwriteANewerAttempt() {
        long id=reservation(); select(id,PaymentMethod.CASH,PaymentPlan.FULL);
        // Worker A claims, its lease expires, worker B claims again (attempts=2) and sends.
        jdbc.update("UPDATE hotel.booking_confirmation_requests SET attempts=1,locked_until=clock_timestamp()-interval '1 second' WHERE reservation_id=?",id);
        assertEquals(1,dispatcher.dispatchOnce());
        assertEquals("2",request("booking_confirmation_requests","reservation_id",id,"attempts"));
        // A's late result is fenced by attempts=1 and changes nothing.
        assertEquals(0,jdbc.update("UPDATE hotel.booking_confirmation_requests SET status='FAILED' WHERE reservation_id=? AND attempts=1 AND status='PENDING'",id));
        assertEquals("SENT",request("booking_confirmation_requests","reservation_id",id,"status"));
    }

    @Test void cancelledOrOldConfirmationsAreNotSent() {
        long cancelled=reservation(); select(cancelled,PaymentMethod.CASH,PaymentPlan.FULL);
        jdbc.query("SELECT hotel.cancel_reservation(?, 'TEST')",rs->{},cancelled);
        long old=reservation(); select(old,PaymentMethod.CASH,PaymentPlan.FULL);
        jdbc.update("UPDATE hotel.booking_confirmation_requests SET created_at=clock_timestamp()-interval '3 days' WHERE reservation_id=?",old);
        assertEquals(2,dispatcher.dispatchOnce());
        org.mockito.Mockito.verifyNoInteractions(mail);
        assertEquals("RESERVATION_CANCELLED",request("booking_confirmation_requests","reservation_id",cancelled,"last_error"));
        assertEquals("EXPIRED_BEFORE_DISPATCH",request("booking_confirmation_requests","reservation_id",old,"last_error"));
    }

    @Test void invoiceDeliveryIsSentAfterCheckout() {
        long id=reservation(); var cash=select(id,PaymentMethod.CASH,PaymentPlan.FULL);
        payments.collectCash(id,cash.intent().id(),new CashCollectionRequest(true),identities.find("staff@test"));
        var staff=new Receptionist(3,1,"RECEPTIONIST");
        checkin.confirm(id,List.of(1L),null,new StayGuest("Guest","ID-1",null),staff);
        checkout.confirm(id,staff);
        long invoice=jdbc.queryForObject("SELECT id FROM hotel.invoices WHERE reservation_id=?",Long.class,id);
        assertEquals("PENDING",request("invoice_delivery_requests","invoice_id",invoice,"status"));
        dispatcher.dispatchOnce();
        assertEquals("SENT",request("invoice_delivery_requests","invoice_id",invoice,"status"));
        var captor=org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
        org.mockito.Mockito.verify(mail,org.mockito.Mockito.times(2)).send(captor.capture());
        String number=jdbc.queryForObject("SELECT invoice_number FROM hotel.invoices WHERE id=?",String.class,invoice);
        assertTrue(captor.getAllValues().stream().anyMatch(m->m.getSubject().contains(number)
            && List.of(m.getTo()).contains("guest@test")));
    }

    // ---- Simulated payments are refused on every write path when the production policy applies ----
    @Autowired com.Hotel.config.MockPaymentPolicy mockPolicy;
    private long snapshot() {
        return jdbc.queryForObject("""
            SELECT (SELECT count(*) FROM hotel.payments)*1000000+(SELECT count(*) FROM hotel.payment_intents)*10000
                  +(SELECT count(*) FROM hotel.audit_logs)*100+(SELECT count(*) FROM hotel.booking_confirmation_requests)
                  +(SELECT count(*) FROM hotel.refunds)*7+(SELECT coalesce(sum(version),0) FROM hotel.reservations)*13
            """,Long.class);
    }
    @Test void productionPolicyRefusesSimulatedWritesBeforeAnyRowAndKeepsCashAndHistory() throws Exception {
        addRooms(1);
        long history=reservation();
        var oldQr=select(history,PaymentMethod.QR,PaymentPlan.FULL); // written while simulation was allowed
        long id=reservation();
        var staffBasic=httpBasic("staff@test","test-password");
        var guestBasic=httpBasic("guest@test","test-password");
        ReflectionTestUtils.setField(mockPolicy,"enabled",false);
        try {
            long before=snapshot();
            mvc.perform(post(url(id)).with(guestBasic).contentType("application/json").content("{\"method\":\"QR\",\"plan\":\"DEPOSIT\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MOCK_PAYMENT_DISABLED"));
            mvc.perform(post(url(history)+"/"+oldQr.intent().id()+"/confirm-qr").with(guestBasic))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MOCK_PAYMENT_DISABLED"));
            mvc.perform(post(url(history)+"/"+oldQr.intent().id()+"/cancel").with(guestBasic))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MOCK_PAYMENT_DISABLED"));
            assertEquals(before,snapshot(),"no payment, intent, audit, outbox, refund or reservation change");
            assertEquals("PENDING_PAYMENT",state(id)); assertEquals("PENDING_PAYMENT",state(history));
            assertEquals("ACTIVE",intentState(oldQr.intent().id())); assertEquals("PENDING",paymentStatus(oldQr.intent().id()));
            // Historical simulated data stays readable.
            mvc.perform(get(url(history)).with(guestBasic)).andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.method").value("QR")).andExpect(jsonPath("$.intent.qrContent").exists());

            // Cash still works end to end: selection, collection, check-in and checkout.
            var cash=select(id,PaymentMethod.CASH,PaymentPlan.FULL);
            assertEquals("CONFIRMED",cash.reservationStatus());
            payments.collectCash(id,cash.intent().id(),new CashCollectionRequest(true),identities.find("staff@test"));
            var staff=new Receptionist(3,1,"RECEPTIONIST");
            checkin.confirm(id,List.of(1L),null,new StayGuest("Occupant","AB123456",null),staff);
            long paymentId=jdbc.queryForObject("SELECT id FROM hotel.payments WHERE reservation_id=? AND status='PAID'",Long.class,id);
            long ledger=snapshot();
            mvc.perform(post("/api/reservations/"+id+"/checkout/payment").with(staffBasic).contentType("application/json")
                    .content("{\"amount\":10,\"method\":\"MOCK_CARD\",\"obligationKey\":\"MOCK-CARD-KEY\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MOCK_PAYMENT_DISABLED"));
            mvc.perform(post("/api/reservations/"+id+"/checkout/payment").with(staffBasic).contentType("application/json")
                    .content("{\"amount\":10,\"method\":\"MOCK_QR\",\"obligationKey\":\"MOCK-QR-KEY\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MOCK_PAYMENT_DISABLED"));
            mvc.perform(post("/api/reservations/"+id+"/checkout/refunds").with(staffBasic).contentType("application/json")
                    .content("{\"paymentId\":"+paymentId+",\"amount\":10,\"method\":\"MOCK_QR\",\"idempotencyKey\":\"MOCK-REFUND\",\"reason\":\"test\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MOCK_PAYMENT_DISABLED"));
            assertEquals(ledger,snapshot());
            mvc.perform(post("/api/reservations/"+id+"/checkout").with(staffBasic)).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceStatus").value("CLOSED"));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM hotel.payments WHERE method<>'CASH' AND status='PAID'",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM hotel.audit_logs WHERE action LIKE 'MOCK_QR%'",Integer.class));
        } finally {
            ReflectionTestUtils.setField(mockPolicy,"enabled",true);
        }
        // With the test configuration (simulation allowed) the QR flow still completes.
        var confirmed=payments.confirmQr(history,oldQr.intent().id(),guest());
        assertEquals("CONFIRMED",confirmed.reservationStatus()); assertEquals("PAID",confirmed.intent().paymentStatus());
    }
}
