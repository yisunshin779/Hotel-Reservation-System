package com.Hotel.Module_DatPhong.service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.Hotel.Module_DatPhong.dto.BookingRequestDTO;
import com.Hotel.Module_DatPhong.entity.Reservation;
import com.Hotel.Module_DatPhong.repository.ReservationRepository;
import com.Hotel.Module_DatPhong.repository.RoomTypeRepository;

@Service
public class BookingService {

    private final RedissonClient redissonClient;
    private final ReservationRepository reservationRepository;
    private final RoomTypeRepository roomTypeRepository;

    public BookingService(RedissonClient redissonClient, 
                          ReservationRepository reservationRepository,
                          RoomTypeRepository roomTypeRepository) {
        this.redissonClient = redissonClient;
        this.reservationRepository = reservationRepository;
        this.roomTypeRepository = roomTypeRepository;
    }

    /**
     * UC07: Xử lý logic giữ phòng với cơ chế Distributed Lock chống Overbooking
     */
    @Transactional // Đảm bảo toàn vẹn dữ liệu Database nếu có lỗi xảy ra
    public String holdRoomForPayment(BookingRequestDTO request) {
        // 1. Validation ranh giới cơ bản
        if (request.ngayNhan().isAfter(request.ngayTra()) || request.ngayNhan().isEqual(request.ngayTra())) {
            throw new IllegalArgumentException("Ngày nhận phòng phải diễn ra trước ngày trả phòng.");
        }

        // 2. Tạo khóa định danh duy nhất cho Loại phòng đang được thao tác
        String lockKey = "LOCK_ROOM_" + request.maLoaiPhong();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 3. Kích hoạt thuật toán Khóa phân tán (Distributed Lock)
            boolean isLocked = lock.tryLock(2, 5, TimeUnit.SECONDS);
            
            if (!isLocked) {
                throw new IllegalStateException("Hệ thống đang xử lý một giao dịch khác cho loại phòng này, vui lòng thử lại sau vài giây.");
            }

            // --- BẮT ĐẦU VÙNG CRITICAL SECTION (Chỉ 1 luồng được chạy vào đây) ---
            
            Integer totalRooms = roomTypeRepository.getTotalRoomsByMaLoai(request.maLoaiPhong());
            if (totalRooms == null) {
                throw new IllegalArgumentException("Mã loại phòng không tồn tại trong hệ thống.");
            }

            int occupiedRooms = reservationRepository.countOccupiedRooms(
                request.maLoaiPhong(), 
                request.ngayNhan(), 
                request.ngayTra()
            );

            if (occupiedRooms + request.soLuongPhong() > totalRooms) {
                throw new IllegalStateException("Rất tiếc, loại phòng này không đủ số lượng trống trong giai đoạn bạn chọn.");
            }

            // 4. Khởi tạo và ghi nhận giao dịch an toàn
            Reservation reservation = new Reservation();
            reservation.setMaLoaiPhong(request.maLoaiPhong());
            reservation.setSoLuongPhong(request.soLuongPhong());
            reservation.setNgayNhan(request.ngayNhan());
            reservation.setNgayTra(request.ngayTra());
            reservation.setTrangThai("PENDING_PAYMENT");

            reservationRepository.save(reservation);

            // 5. Đẩy key vào Redis với TTL 15 phút (900 giây)
            String redisKey = "PAYMENT_PENDING:" + reservation.getId();
            RBucket<String> paymentBucket = redissonClient.getBucket(redisKey);
            paymentBucket.set("AWAITING_PAYMENT", 15, TimeUnit.MINUTES);

            return reservation.getId().toString();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Luồng xử lý đồng bộ bị gián đoạn hệ thống.");
        } finally {
            // 4. Giải phóng tài nguyên
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
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
}