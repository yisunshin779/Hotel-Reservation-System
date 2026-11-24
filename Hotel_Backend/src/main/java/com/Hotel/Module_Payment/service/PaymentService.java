package com.Hotel.Module_Payment.service;

import com.Hotel.Module_Payment.entity.*;
import com.Hotel.Module_Payment.dto.*;
import com.Hotel.Module_Payment.repository.PaymentRepository;
import com.Hotel.Module_Report.security.ReportIdentityService.Identity;
import com.Hotel.Module_CheckInCheckOut.repository.StayRepository;
import com.Hotel.Module_CheckInCheckOut.service.CheckOutService;
import com.Hotel.Module_CheckInCheckOut.entity.Receptionist;
import com.Hotel.config.MockPaymentPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PaymentService {
    private final PaymentRepository repo;
    private final PaymentAuthContext auth;
    private final StayRepository ledger;
    private final CheckOutService checkout;
    private final MockPaymentPolicy mock;
    public PaymentService(PaymentRepository repo,PaymentAuthContext auth,StayRepository ledger,CheckOutService checkout,MockPaymentPolicy mock) {
        this.repo=repo; this.auth=auth; this.ledger=ledger; this.checkout=checkout; this.mock=mock;
    }
    /** Refuses a simulated QR write before the reservation lock and before any row is written. */
    private void requireMockAllowed() {
        if(!mock.allows("MOCK_QR")) throw PaymentException.conflict(MockPaymentPolicy.DISABLED_CODE,MockPaymentPolicy.DISABLED_MESSAGE);
    }
    public long resolveReservationId(String reference) {
        return repo.resolveReservationId(reference);
    }

    @Transactional
    public PaymentSummary cancelUnpaidHold(long id, Identity actor) {
        var reservation = repo.reservation(id, true);
        auth.owner(reservation, actor);
        boolean cancelled = "CANCELLED".equals(reservation.status());
        if ((!cancelled && !"PENDING_PAYMENT".equals(reservation.status())) || repo.paid(id).signum() > 0) {
            throw PaymentException.conflict("RESERVATION_NOT_CANCELLABLE", "Only an unpaid hold can be cancelled");
        }
        repo.latest(id).filter(intent -> "ACTIVE".equals(intent.state())).ifPresent(repo::cancel);
        if (!cancelled) {
            repo.cancelHold(id);
        }
        return summary(repo.reservation(id, false), repo.latest(id).orElse(null));
    }
    private boolean expired(PaymentReservation r,Instant now) {
        return "PENDING_PAYMENT".equals(r.status()) && (r.holdExpiresAt()==null || !now.isBefore(r.holdExpiresAt()));
    }
    private void payable(PaymentReservation r) {
        if(!"PENDING_PAYMENT".equals(r.status())) throw PaymentException.conflict("INVALID_RESERVATION_STATE","Reservation is not awaiting payment");
        // Database wall clock is read after acquiring the row lock, including time spent waiting.
        if(expired(r,repo.now())) throw PaymentException.conflict("HOLD_EXPIRED","The 15 minute hold has expired");
    }
    private PaymentSummary summary(PaymentReservation r,PaymentIntent intent) {
        var paid=repo.paid(r.id());
        var now=repo.now();
        var view=intent==null?null:new PaymentSummary.IntentView(intent.id(),intent.paymentId(),
            "CASH".equals(intent.method())?PaymentMethod.CASH:PaymentMethod.QR,intent.plan(),intent.state(),
            intent.paymentStatus(),intent.amount(),intent.obligationKey(),
            "MOCK_QR".equals(intent.method())?"MOCK|"+r.code()+"|"+intent.amount().toPlainString()+"|"+intent.id():null);
        return new PaymentSummary(r.id(),r.code(),r.status(),r.holdExpiresAt(),now,expired(r,now),r.total(),
            PaymentPlan.DEPOSIT.amount(r.total()),paid,r.total().subtract(paid).add(repo.refunded(r.id())),view,repo.notification(r.id()));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PaymentSummary options(long id,Identity actor) {
        var r=repo.reservation(id,false);
        if(Long.valueOf(actor.id()).equals(r.ownerUserId())) auth.owner(r,actor); else auth.receptionist(r,actor);
        return summary(r,repo.latest(id).orElse(null));
    }
    @Transactional
    public PaymentSummary select(long id,SelectPaymentRequest request,Identity actor) {
        if(request.method()==PaymentMethod.QR) requireMockAllowed();
        var r=repo.reservation(id,true); auth.owner(r,actor);
        var previous=repo.latest(id).orElse(null);
        String method=request.method()==PaymentMethod.QR?"MOCK_QR":"CASH";
        if(previous!=null && "COMPLETED".equals(previous.state()) && previous.method().equals(method) && previous.plan()==request.plan())
            return summary(r,previous);
        payable(r);
        BigDecimal amount=request.plan().amount(r.total());
        if(amount.signum()<=0) throw PaymentException.conflict("INVALID_PAYMENT_AMOUNT","The payable amount must be positive");
        if(previous!=null && "ACTIVE".equals(previous.state())) {
            if(previous.method().equals(method) && previous.plan()==request.plan() && previous.amount().compareTo(amount)==0)
                return summary(r,previous);
            repo.cancel(previous);
        }
        // Do not silently create a second obligation on top of another prepayment writer.
        if(repo.paid(id).signum()>0) throw PaymentException.conflict("PAYMENT_STATE_CONFLICT","A payment is already recorded for this reservation");
        var intent=repo.create(id,request.method(),request.plan(),amount);
        if(request.method()==PaymentMethod.CASH) { repo.confirm(id,true); repo.complete(intent); }
        ledger.audit(actor.id(),id,"PAYMENT_METHOD_SELECTED","{}");
        return summary(repo.reservation(id,false),repo.intent(id,intent.id()));
    }
    @Transactional
    public PaymentSummary confirmQr(long id,UUID intentId,Identity actor) {
        requireMockAllowed();
        var r=repo.reservation(id,true); auth.owner(r,actor);
        var intent=repo.intent(id,intentId);
        if(!"MOCK_QR".equals(intent.method())) throw PaymentException.conflict("INVALID_PAYMENT_METHOD","Select QR before confirming");
        if("PAID".equals(intent.paymentStatus())) return summary(r,intent);
        payable(r);
        if(!"ACTIVE".equals(intent.state()) || !"PENDING".equals(intent.paymentStatus()))
            throw PaymentException.conflict("PAYMENT_SELECTION_INACTIVE","The QR selection was cancelled or replaced");
        if(intent.amount().compareTo(intent.plan().amount(r.total()))!=0)
            throw PaymentException.conflict("PRICE_CHANGED","Select the payment method again to review the updated amount");
        if(repo.paid(id).signum()>0) throw PaymentException.conflict("PAYMENT_STATE_CONFLICT","Another payment is already recorded");
        ledger.recordPayment(id,intent.amount(),intent.method(),intent.obligationKey(),actor.id(),repo.now());
        repo.complete(intent); repo.confirm(id,false);
        ledger.audit(actor.id(),id,"MOCK_QR_CONFIRMED","{}");
        return summary(repo.reservation(id,false),repo.intent(id,intentId));
    }
    @Transactional
    public PaymentSummary cancelQr(long id,UUID intentId,Identity actor) {
        requireMockAllowed();
        var r=repo.reservation(id,true); auth.owner(r,actor);
        var intent=repo.intent(id,intentId);
        if(!"MOCK_QR".equals(intent.method()) || "PAID".equals(intent.paymentStatus()))
            throw PaymentException.conflict("PAYMENT_STATE_CONFLICT","Only an unpaid QR selection can be cancelled");
        if("ACTIVE".equals(intent.state())) {
            repo.cancel(intent); ledger.audit(actor.id(),id,"MOCK_QR_CANCELLED","{}");
        }
        return summary(r,repo.intent(id,intentId));
    }
    @Transactional
    public PaymentSummary collectCash(long id,UUID intentId,CashCollectionRequest request,Identity actor) {
        var r=repo.reservation(id,true); auth.receptionist(r,actor);
        if(!Boolean.TRUE.equals(request.cashReceived())) throw new PaymentException(400,"CASH_NOT_RECEIVED","Confirm that cash was actually received");
        var intent=repo.intent(id,intentId);
        if(!"CASH".equals(intent.method()) || !"COMPLETED".equals(intent.state()))
            throw PaymentException.conflict("CASH_OBLIGATION_REQUIRED","Reservation has no selected cash obligation");
        if("PAID".equals(intent.paymentStatus())) return summary(r,intent);
        if(!"UNPAID".equals(intent.paymentStatus()) || !("CONFIRMED".equals(r.status()) || "CHECKED_IN".equals(r.status())))
            throw PaymentException.conflict("INVALID_RESERVATION_STATE","Cash cannot be collected for this reservation");
        if("CHECKED_IN".equals(r.status())) {
            checkout.payment(id,intent.amount(),intent.method(),intent.obligationKey(),new Receptionist(actor.id(),r.branchId(),actor.role()));
        } else {
            var balance=r.total().subtract(repo.paid(id)).add(repo.refunded(id));
            if(intent.amount().compareTo(balance)>0) throw PaymentException.conflict("OVERPAYMENT","Cash exceeds the remaining balance");
            ledger.recordPayment(id,intent.amount(),intent.method(),intent.obligationKey(),actor.id(),repo.now());
        }
        ledger.audit(actor.id(),id,"CASH_COLLECTED","{}");
        return summary(r,repo.intent(id,intentId));
    }
}
