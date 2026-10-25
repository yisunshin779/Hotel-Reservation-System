package Hotel_Backend.Module_DatPhong;

public interface ReservationRepository extends JpaRepository<Reservation, String> {
    @Query("SELECT SUM(r.soLuongPhong) FROM Reservation r " +
           "WHERE r.maLoaiPhong = :maLoai " +
           "AND r.trangThai IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN') " +
           "AND r.ngayNhan < :ngayTra AND r.ngayTra > :ngayNhan") // Công thức phát hiện giao cắt thời gian
    int countOccupiedRooms(String maLoai, LocalDate ngayNhan, LocalDate ngayTra);
}