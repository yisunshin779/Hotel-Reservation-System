package com.Hotel.Module_CheckInCheckOut.service;

import com.Hotel.Module_CheckInCheckOut.dto.InvoiceAdjustmentRequestDTO;
import com.Hotel.Module_CheckInCheckOut.entity.*;
import com.Hotel.Module_CheckInCheckOut.repository.InvoiceAdjustmentRepository;
import com.Hotel.Module_CheckInCheckOut.repository.StayRepository;
import com.Hotel.Module_Report.security.ReportIdentityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class InvoiceAdjustmentService {
    private static final Set<String> TYPES = Set.of("ROOM", "SERVICE", "EXTRA", "DISCOUNT", "TAX");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    private final StayRepository stays;
    private final InvoiceAdjustmentRepository adjustments;
    private final Clock clock;
    public InvoiceAdjustmentService(StayRepository stays, InvoiceAdjustmentRepository adjustments, Clock clock) {
        this.stays = stays; this.adjustments = adjustments; this.clock = clock;
    }
    private void requireAdmin(ReportIdentityService.Identity actor) {
        if (actor == null || !actor.active() || !actor.unlocked() || !"ADMIN".equals(actor.role()))
            throw new StayException(403, "FORBIDDEN", "Chỉ Admin được lập và xem hóa đơn điều chỉnh.");
    }
    private Invoice closedInvoice(long id, boolean lock) {
        var reservation = stays.reservation(id, lock);
        if (reservation.status() != ReservationStatus.CHECKED_OUT)
            throw StayException.conflict("INVOICE_NOT_CLOSED", "Chỉ lập điều chỉnh sau khi đã chốt check-out và hóa đơn.");
        return stays.invoice(id);
    }
    @Transactional
    public InvoiceAdjustment create(long id, InvoiceAdjustmentRequestDTO body, ReportIdentityService.Identity actor) {
        requireAdmin(actor);
        if (body == null || body.reason() == null || body.reason().isBlank() || body.reason().length() > 1000
                || body.idempotencyKey() == null || !body.idempotencyKey().matches("[A-Za-z0-9:_-]{8,100}")
                || body.lines() == null || body.lines().isEmpty() || body.lines().size() > 100)
            throw invalid();
        var lines = body.lines().stream().map(line -> {
            if (line == null || !TYPES.contains(line.type() == null ? "" : line.type())
                    || line.description() == null || line.description().isBlank() || line.description().length() > 500
                    || line.amount() == null || line.amount().signum() == 0 || line.amount().scale() > 2
                    || line.amount().abs().compareTo(MAX_AMOUNT) > 0) throw invalid();
            return new InvoiceAdjustmentLine(line.type(), line.description().strip(), line.amount().setScale(2));
        }).toList();
        String reason = body.reason().strip();
        adjustments.lockKey(body.idempotencyKey());
        var invoice = closedInvoice(id, true);
        var existing = adjustments.byKey(body.idempotencyKey());
        if (existing.isPresent()) {
            var previous = existing.get();
            if (previous.invoiceId() != invoice.id() || !previous.reason().equals(reason) || !previous.lines().equals(lines))
                throw StayException.conflict("ADJUSTMENT_KEY_CONFLICT", "Khóa đã thuộc yêu cầu điều chỉnh khác.");
            return previous;
        }
        var result = adjustments.insert(invoice.id(), "ADJ-" + UUID.randomUUID(), reason,
                body.idempotencyKey(), actor.id(), clock.instant(), lines);
        stays.audit(actor.id(), id, "INVOICE_ADJUSTMENT", "{\"invoiceId\":" + invoice.id()
                + ",\"adjustmentId\":" + result.id() + "}");
        return result;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<InvoiceAdjustment> list(long id, ReportIdentityService.Identity actor) {
        requireAdmin(actor);
        return adjustments.list(closedInvoice(id, false).id());
    }
    private StayException invalid() {
        return new StayException(400, "INVALID_ADJUSTMENT", "Cần lý do, khóa yêu cầu và các dòng điều chỉnh hợp lệ (số tiền khác 0, tối đa 2 chữ số thập phân).");
    }
}
