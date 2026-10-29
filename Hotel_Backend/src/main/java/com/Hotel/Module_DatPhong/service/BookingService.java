package com.Hotel.Module_DatPhong.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.Hotel.Module_DatPhong.dto.BookingRequestDTO;

@Service
public class BookingService {

    /**
     * UC07: Xử lý logic giữ phòng, tích hợp khóa phân tán.
     */
    public String holdRoomForPayment(BookingRequestDTO request) {
        // 1. Xác thực ranh giới (Boundary Validation) cơ bản
        if (request.ngayNhan().isAfter(request.ngayTra()) || request.ngayNhan().isEqual(request.ngayTra())) {
            throw new IllegalArgumentException("Ngày nhận phòng phải diễn ra trước ngày trả phòng.");
        }
        if (request.soLuongPhong() <= 0) {
            throw new IllegalArgumentException("Số lượng phòng đặt phải lớn hơn 0.");
        }

        // TODO: 2. Kích hoạt Redisson Client để xin cấp khóa (Lock) theo maLoaiPhong
        // TODO: 3. Gọi ReservationRepository.countOccupiedRooms() để tính số phòng đang bị chiếm
        // TODO: 4. So sánh số phòng đang chiếm với tổng quỹ phòng, nếu đủ thì ghi xuống DB trạng thái PENDING_PAYMENT
        // TODO: 5. Hẹn giờ (TTL) 15 phút trên Redis để tự động hủy nếu không thanh toán

        // Tạm thời sinh ra một mã giao dịch ảo để Controller biên dịch thành công
        return UUID.randomUUID().toString();
    }
}