package com.Hotel.Module_CheckInCheckOut.service;

import com.Hotel.Module_CheckInCheckOut.entity.Bill;
import com.Hotel.Module_CheckInCheckOut.entity.Payment;
import com.Hotel.Module_CheckInCheckOut.entity.Receptionist;
import com.Hotel.Module_CheckInCheckOut.entity.Refund;
import com.Hotel.Module_CheckInCheckOut.entity.Reservation;
import com.Hotel.Module_CheckInCheckOut.entity.ReservationStatus;
import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import com.Hotel.Module_CheckInCheckOut.repository.StayRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundService {
    private final StayRepository repo;
    private final BillingService billing;
    private final Clock clock;
    public RefundService(StayRepository repo, BillingService billing, Clock clock) { this.repo=repo;this.billing=billing;this.clock=clock; }
    private void requireStay(Reservation r) {
        if(r.status()!=ReservationStatus.CHECKED_IN) throw StayException.conflict("INVALID_RESERVATION_STATE","Chỉ xử lý hoàn chênh lệch khi đang lưu trú.");
    }
    private void capacity(Reservation r, Payment p, BigDecimal amount, Long excludingId) {
        if(p.reservationId()!=r.id() || !"PAID".equals(p.status()))
            throw StayException.conflict("INVALID_REFUND_PAYMENT","Khoản hoàn phải thuộc khoản đã thu của chính đơn này.");
        if(amount.compareTo(p.amount().subtract(repo.allocatedRefunds(r.id(),p.id(),excludingId)))>0)
            throw StayException.conflict("REFUND_EXCEEDS_PAYMENT","Khoản hoàn vượt hạn mức còn lại của khoản thu.");
        // DONE already reduces Bill.refundAmount. Pending refunds reserve the remaining limit.
        var pending=repo.allocatedRefunds(r.id(),null,excludingId).subtract(repo.refunded(r.id()));
        if(amount.compareTo(billing.calculate(r).refundAmount().subtract(pending))>0)
            throw StayException.conflict("REFUND_EXCEEDS_BALANCE","Khoản hoàn vượt phần dư chưa được dành cho khoản hoàn khác.");
    }
    @Transactional
    public Refund request(long id, long paymentId, BigDecimal amount, String method, String key, String reason, Receptionist actor) {
        var r=repo.reservation(id,true); actor.authorize(r.branchId());
        if(amount==null || amount.signum()<=0 || amount.scale()>2 || amount.precision()>14 || paymentId<=0
            || key==null || !key.matches("[A-Za-z0-9:_-]{8,100}") || reason==null || reason.strip().isEmpty() || reason.length()>500
            || !java.util.Set.of("CASH","MOCK_QR","MOCK_CARD").contains(method==null?"":method))
            throw new StayException(400,"INVALID_REFUND","Khoản hoàn, phương thức, khóa hoặc lý do không hợp lệ.");
        var existing=repo.refundByKey(key);
        if(existing.isPresent()) {
            var f=existing.get();
            if(f.reservationId()!=id || f.paymentId()!=paymentId || f.amount().compareTo(amount)!=0
                || !f.method().equals(method) || !Objects.equals(f.reason(),reason.strip()))
                throw StayException.conflict("REFUND_KEY_CONFLICT","Khóa đã thuộc khoản hoàn khác.");
            return f; // Read-only replay also works after checkout.
        }
        requireStay(r);
        var p=repo.payment(paymentId);
        if(!p.method().equals(method)) throw StayException.conflict("REFUND_METHOD_MISMATCH","Phương thức hoàn phải khớp khoản thu gốc.");
        capacity(r,p,amount,null);
        var f=repo.createRefund(id,paymentId,amount,key,reason.strip(),!method.equals("CASH"),actor.id(),clock.instant());
        repo.audit(actor.id(),id,"CHECKOUT_REFUND_REQUEST","{\"refundId\":"+f.id()+"}");
        return f;
    }
    @Transactional
    public Refund confirmCash(long id, long refundId, boolean cashReturned, Receptionist actor) {
        var r=repo.reservation(id,true); actor.authorize(r.branchId());
        var f=repo.refund(id,refundId);
        if(!"CASH".equals(f.method())) throw StayException.conflict("NOT_CASH_REFUND","Chỉ xác nhận khoản hoàn tiền mặt.");
        if("DONE".equals(f.status())) return f;
        requireStay(r);
        if(!cashReturned || !"PENDING".equals(f.status())) throw StayException.conflict("CASH_REFUND_NOT_CONFIRMED","Cần xác nhận đã trả tiền mặt cho khách và khoản hoàn còn chờ xử lý.");
        capacity(r,repo.payment(f.paymentId()),f.amount(),f.id());
        repo.completeRefund(f.id(),actor.id(),clock.instant());
        repo.audit(actor.id(),id,"CHECKOUT_CASH_REFUND","{\"refundId\":"+f.id()+"}");
        return repo.refund(id,refundId);
    }
}
