package com.Hotel.Module_CheckInCheckOut.service;

import com.Hotel.Module_CheckInCheckOut.entity.Bill;
import com.Hotel.Module_CheckInCheckOut.entity.InvoiceLine;
import com.Hotel.Module_CheckInCheckOut.entity.Reservation;
import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import com.Hotel.Module_CheckInCheckOut.repository.StayRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

@Service
public class BillingService {
    private final StayRepository repo;
    private final Clock clock;
    public BillingService(StayRepository repo, Clock clock) { this.repo = repo; this.clock = clock; }
    public Bill calculate(Reservation r) {
        var rooms=repo.roomLines(r.id());
        var amount=rooms.stream().map(InvoiceLine::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        if (rooms.isEmpty() || amount.compareTo(r.roomAmount())!=0)
            throw StayException.conflict("INVALID_ROOM_SNAPSHOT", "Giá từng đêm không khớp tổng tiền phòng đã chốt.");
        var policy=repo.policy(r);
        var extras=new java.util.ArrayList<>(repo.extraLines(r.id()));
        Instant due=r.checkOut().atTime(policy.checkoutTime()).atZone(ZoneId.of(r.timezone())).toInstant();
        if (clock.instant().isAfter(due) && policy.lateFee()!=null && policy.lateFee().signum()>0)
            extras.add(new InvoiceLine("EXTRA","Phụ phí trả muộn theo chính sách chi nhánh",BigDecimal.ONE,policy.lateFee(),policy.lateFee()));
        return Bill.calculate(rooms, r.discount(), repo.serviceLines(r.id()), extras,
                repo.paid(r.id()), repo.refunded(r.id()), policy.taxRate());
    }
}
