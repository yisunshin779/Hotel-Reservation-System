package com.Hotel.Module_DatPhong.service;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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

            // TODO: Đẩy 1 key vào Redis với TTL 900 giây (15 phút) để tự động hủy đơn nếu không thanh toán
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
}