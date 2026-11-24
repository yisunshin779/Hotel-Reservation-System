package com.Hotel.Module_DatPhong.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.Hotel.Module_DatPhong.dto.BookingRequestDTO;
import com.Hotel.Module_DatPhong.dto.SearchRequestDTO;
import com.Hotel.Module_DatPhong.entity.Reservation;
import com.Hotel.Module_DatPhong.entity.RoomType;
import com.Hotel.Module_DatPhong.repository.ReservationRepository;
import com.Hotel.Module_DatPhong.repository.RoomTypeRepository;
import com.Hotel.config.MockPaymentPolicy;

@Service
public class BookingService {

    private final RedissonClient redissonClient;
    private final ReservationRepository reservationRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final MockPaymentPolicy mockPayments;

    public BookingService(@Lazy RedissonClient redissonClient,
                          ReservationRepository reservationRepository,
                          RoomTypeRepository roomTypeRepository,
                          MockPaymentPolicy mockPayments) {
        this.redissonClient = redissonClient;
        this.reservationRepository = reservationRepository;
        this.roomTypeRepository = roomTypeRepository;
        this.mockPayments = mockPayments;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String holdRoomForPayment(BookingRequestDTO request) {
        if (request.ngayNhan() == null || request.ngayTra() == null
                || !request.ngayNhan().isBefore(request.ngayTra())) {
            throw new IllegalArgumentException("Ngày nhận phòng phải diễn ra trước ngày trả phòng.");
        }
        if (request.soLuongPhong() <= 0 || request.maLoaiPhong() == null || request.maLoaiPhong().isBlank()) {
            throw new IllegalArgumentException("Mã loại phòng và số lượng phòng không hợp lệ.");
        }

        RoomType roomType = roomTypeRepository.lockById(request.maLoaiPhong())
                .orElseThrow(() -> new IllegalArgumentException("Mã loại phòng không tồn tại trong hệ thống."));
        int occupiedRooms = reservationRepository.countOccupiedRooms(
                roomType.getId(), request.ngayNhan(), request.ngayTra());
        if (request.soLuongPhong() > roomType.getTongSoPhong() - occupiedRooms) {
            throw new IllegalStateException("Rất tiếc, loại phòng này không đủ số lượng trống trong giai đoạn bạn chọn.");
        }

        Reservation reservation = new Reservation();
        reservation.setMaLoaiPhong(roomType.getId());
        reservation.setSoLuongPhong(request.soLuongPhong());
        reservation.setNgayNhan(request.ngayNhan());
        reservation.setNgayTra(request.ngayTra());
        reservation.setTrangThai("PENDING_PAYMENT");
        reservationRepository.saveAndFlush(reservation);

        RBucket<String> paymentBucket = redissonClient.getBucket("PAYMENT_PENDING:" + reservation.getId());
        paymentBucket.set("AWAITING_PAYMENT", 15, TimeUnit.MINUTES);
        return reservation.getId().toString();
    }

    /**
     * Tự động quét và hủy các đơn chưa thanh toán quá 15 phút.
     * Chạy ngầm định kỳ mỗi 60,000 mili-giây (1 phút).
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void cleanUpExpiredReservations() {
        // Tính toán mốc thời gian của 15 phút trước
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(15);
        
        // Kích hoạt transaction xóa các bản ghi quá hạn
        int canceledCount = reservationRepository.cancelExpiredReservations(cutoffTime);
        
        if (canceledCount > 0) {
            System.out.println("Đã tự động hủy " + canceledCount + " đơn đặt phòng quá hạn thanh toán.");
        }
    }

    /**
     * Mô phỏng hệ thống thanh toán trả về kết quả thành công.
     */
    public String confirmPayment(UUID reservationId) {
        // Legacy simulated payment (no HTTP mapping). Follows the same switch as the Payment module.
        if (!mockPayments.enabled()) {
            throw new IllegalStateException(MockPaymentPolicy.DISABLED_CODE + ": " + MockPaymentPolicy.DISABLED_MESSAGE);
        }
        // 1. Tìm đơn đặt phòng trong Database
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã đơn đặt phòng này."));

        // 2. Chốt chặn trạng thái
        if (!"PENDING_PAYMENT".equals(reservation.getTrangThai())) {
            throw new IllegalStateException("Đơn đặt phòng không ở trạng thái chờ thanh toán.");
        }

        // 3. Đổi trạng thái thành công
        reservation.setTrangThai("CONFIRMED");
        reservationRepository.save(reservation);

        // 4. BƯỚC SINH TỬ: Xóa key trong Redis để chặn Cron Job quét dọn
        String redisKey = "PAYMENT_PENDING:" + reservation.getId();
        redissonClient.getBucket(redisKey).delete();

        return "Thanh toán thành công. Đơn đặt phòng " + reservation.getId() + " đã được xác nhận!";
    }

    public List<RoomType> searchAvailableRooms(SearchRequestDTO request) {
        // Tầng DTO đã tự động chặn các request có ngày trả < ngày nhận
        // Ở đây chỉ cần gọi thẳng xuống Repository
        return roomTypeRepository.findAvailableRoomTypes(request.ngayNhan(), request.ngayTra());
    }
}
