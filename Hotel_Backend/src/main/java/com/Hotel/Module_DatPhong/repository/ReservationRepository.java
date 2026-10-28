package Hotel_Backend.Module_DatPhong;

import java.time.LocalDate;

// Integration boundary: Reservation is currently an empty, unmapped placeholder.
// Do not enable a JPA repository until the booking module supplies its entity.
public interface ReservationRepository {
    int countOccupiedRooms(String maLoai, LocalDate ngayNhan, LocalDate ngayTra);
}
