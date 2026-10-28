package com.Hotel.Module_CheckInCheckOut;

import com.Hotel.Module_CheckInCheckOut.entity.*;
import com.Hotel.Module_CheckInCheckOut.repository.StayRepository;
import com.Hotel.Module_CheckInCheckOut.dto.*;
import com.Hotel.Module_CheckInCheckOut.service.CheckInValidator;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BillingTest {
    private BigDecimal n(String v) { return new BigDecimal(v); }
    @Test void preservesSnapshotsAndAccountsForRefunds() {
        var service = new InvoiceLine("SERVICE","Minibar",n("2"),n("15.25"),n("30.50"));
        var extra = new InvoiceLine("EXTRA","Phụ phí",n("1"),n("10"),n("10"));
        var bill = Bill.calculate(n("200"),n("20"),List.of(service),List.of(extra),n("250"),n("10"));
        assertEquals(0,n("220.50").compareTo(bill.grossAmount()));
        assertEquals(0,n("-19.50").compareTo(bill.remainingAmount()));
        assertEquals(0,n("19.50").compareTo(bill.refundAmount()));
        assertThrows(StayException.class,bill::requireSettled);
    }
    @Test void checksExactBalanceWithoutFloatingPointTolerance() {
        var bill=Bill.calculate(n("0.30"),n("0"),List.of(),List.of(),n("0.30"),n("0"));
        assertDoesNotThrow(bill::requireSettled);
        assertThrows(StayException.class,() -> Bill.calculate(n("0.30"),n("0"),List.of(),List.of(),n("0.29"),n("0")).requireSettled());
    }
    @Test void verifiesDateInBranchTimezoneAndDepositPolicy() {
        var clock=Clock.fixed(Instant.parse("2026-10-27T18:00:00Z"),ZoneOffset.UTC);
        var r=new Reservation(1,1,1,"BOOK",ReservationStatus.CONFIRMED,LocalDate.parse("2026-10-28"),
                LocalDate.parse("2026-10-29"),"Asia/Ho_Chi_Minh",n("100"),n("0"),n("100"),true,null,null,0);
        var validator=new CheckInValidator();
        assertDoesNotThrow(() -> validator.validate(r,n("30"),clock));
        assertThrows(StayException.class,() -> validator.validate(r,n("29.99"),clock));
        assertThrows(StayException.class,() -> validator.identity("   "));
        assertThrows(StayException.class,() -> validator.identity("<script>alert(1)</script>"));
        assertEquals("AB123456",validator.identity(" AB123456 "));
    }
}
