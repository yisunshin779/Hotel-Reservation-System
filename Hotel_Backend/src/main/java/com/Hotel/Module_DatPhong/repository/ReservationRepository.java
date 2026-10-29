package com.Hotel.Module_DatPhong.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.Hotel.Module_DatPhong.entity.Reservation;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, String> {

    // Cách 1: Sử dụng JPQL (Truy vấn theo tên Entity của Java thay vì tên bảng MySQL)
    @Query("SELECT COALESCE(SUM(r.soLuongPhong), 0) FROM Reservation r " +
           "WHERE r.maLoaiPhong = :maLoai " +
           "AND r.trangThai IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN') " +
           "AND r.ngayNhan < :ngayTra AND r.ngayTra > :ngayNhan")
    int countOccupiedRooms(
        @Param("maLoai") String maLoai, 
        @Param("ngayNhan") LocalDate ngayNhan, 
        @Param("ngayTra") LocalDate ngayTra
    );

}