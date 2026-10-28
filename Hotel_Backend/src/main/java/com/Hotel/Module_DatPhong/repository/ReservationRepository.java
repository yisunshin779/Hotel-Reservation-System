package com.Hotel.Module_DatPhong.repository;

import java.time.LocalDate;

/** Booking persistence contract; implementation belongs to the booking module. */
public interface ReservationRepository {
    int countOccupiedRooms(String maLoai, LocalDate ngayNhan, LocalDate ngayTra);
}
