package com.Hotel.Module_CheckInCheckOut.service;

import com.Hotel.Module_CheckInCheckOut.entity.Reservation;
import com.Hotel.Module_CheckInCheckOut.entity.ReservationStatus;
import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import java.math.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class CheckInValidator {
    public void validate(Reservation r, BigDecimal netPaid, Clock clock) {
        if (r.status() != ReservationStatus.CONFIRMED)
            throw StayException.conflict("INVALID_RESERVATION_STATE", "Đơn phải ở trạng thái CONFIRMED và chưa check-in.");
        if (!LocalDate.now(clock.withZone(ZoneId.of(r.timezone()))).equals(r.checkIn()))
            throw StayException.conflict("INVALID_CHECKIN_DATE", "Chỉ nhận phòng đúng ngày nhận theo múi giờ chi nhánh.");
        // Existing schema: non-null hold_expires_at identifies a booking requiring advance payment.
        // Pay-at-hotel bookings have no hold. The documented minimum advance payment is 30%.
        BigDecimal required = r.prepaid() ? r.quotedTotal().multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        if (netPaid.compareTo(required) < 0)
            throw StayException.conflict("PAYMENT_REQUIRED", "Đơn chưa đáp ứng khoản thanh toán trước tối thiểu 30%.");
    }
    public String identity(String value) {
        if (value == null || !value.strip().matches("[\\p{L}\\p{N}][\\p{L}\\p{N} ./'-]{3,79}"))
            throw new StayException(400, "INVALID_IDENTITY", "Giấy tờ phải có 4–80 ký tự hợp lệ; lễ tân phải đối chiếu bản gốc.");
        return value.strip();
    }
}
