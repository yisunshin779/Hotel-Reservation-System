package com.Hotel.Module_CheckInCheckOut.service;

import com.Hotel.Module_CheckInCheckOut.dto.CheckOutResultDTO;
import com.Hotel.Module_CheckInCheckOut.entity.Bill;
import com.Hotel.Module_CheckInCheckOut.entity.Invoice;
import com.Hotel.Module_CheckInCheckOut.entity.Receptionist;
import com.Hotel.Module_CheckInCheckOut.entity.Reservation;
import com.Hotel.Module_CheckInCheckOut.entity.ReservationStatus;
import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import com.Hotel.Module_CheckInCheckOut.repository.StayRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class CheckOutService {
    private final StayRepository repo;
    private final BillingService billing;
    private final Clock clock;
    public CheckOutService(StayRepository repo, BillingService billing, Clock clock) {
        this.repo = repo; this.billing = billing; this.clock = clock;
    }

    private Reservation requireStay(long id, Receptionist actor, boolean lock) {
        var r = repo.reservation(id, lock); actor.authorize(r.branchId());
        if (r.status() != ReservationStatus.CHECKED_IN) throw StayException.conflict("INVALID_RESERVATION_STATE", "Đơn phải đang lưu trú.");
        return r;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Bill preview(long id, Receptionist actor) {
        return billing.calculate(requireStay(id, actor, false));
    }
    @Transactional
    public Bill payment(long id, BigDecimal amount, String method, String key, Receptionist actor) {
        var r = requireStay(id, actor, true);
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.precision() > 14
                || key == null || !key.matches("[A-Za-z0-9:_-]{8,100}")
                || !java.util.Set.of("CASH", "MOCK_QR", "MOCK_CARD").contains(method == null ? "" : method))
            throw new StayException(400, "INVALID_PAYMENT", "Khoản thu, phương thức hoặc khóa thanh toán không hợp lệ.");
        // Repository validates an existing obligation before inserting, including cross-UC keys.
        boolean created = repo.recordPayment(id, amount, method, key, actor.id(), clock.instant());
        var bill = billing.calculate(r);
        if (created && bill.remainingAmount().signum() < 0)
            throw StayException.conflict("OVERPAYMENT", "Khoản thu vượt số dư cần thanh toán.");
        if (created) repo.audit(actor.id(), id, "CHECKOUT_PAYMENT", "{}");
        return bill;
    }
    @Transactional
    public CheckOutResultDTO confirm(long id, Receptionist actor) {
        var r = repo.reservation(id, true); actor.authorize(r.branchId());
        if (r.status() == ReservationStatus.CHECKED_OUT) return result(r, repo.invoice(id));
        if (r.status() != ReservationStatus.CHECKED_IN) throw StayException.conflict("INVALID_RESERVATION_STATE", "Đơn phải đang lưu trú.");
        var bill = billing.calculate(r); bill.requireSettled();
        if(repo.allocatedRefunds(id,null,null).compareTo(repo.refunded(id))>0)
            throw StayException.conflict("PENDING_REFUND","Cần hoàn tất khoản hoàn đang chờ trước khi chốt hóa đơn.");
        if (repo.assignedRoomIds(id).isEmpty()) throw StayException.conflict("MISSING_ASSIGNMENT", "Đơn không có phòng đang lưu trú.");
        Instant now = clock.instant();
        var invoice = repo.closeInvoice(r, bill, "INV-" + UUID.randomUUID(), now);
        repo.requestInvoiceDelivery(invoice.id(),now);
        repo.release(id, now);
        repo.audit(actor.id(), id, "CHECK_OUT", "{\"invoiceId\":" + invoice.id() + "}");
        return new CheckOutResultDTO(id, "CHECKED_OUT", invoice.id(), "CLOSED", bill.grossAmount(), bill.paidAmount(),
                bill.refundedAmount(), now, "VACANT", "DIRTY");
    }
    private CheckOutResultDTO result(Reservation r, Invoice invoice) {
        return new CheckOutResultDTO(r.id(), "CHECKED_OUT", invoice.id(), invoice.status(), invoice.total(),
                repo.paid(r.id()), repo.refunded(r.id()), r.actualCheckOut(), "VACANT", "DIRTY");
    }
}
