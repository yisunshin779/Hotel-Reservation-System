package com.Hotel.Module_DatPhong.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.Hotel.Module_DatPhong.dto.BookingRequestDTO;
import com.Hotel.Module_DatPhong.service.BookingService;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    // Dependency Injection qua Constructor (Khuyên dùng thay vì @Autowired để dễ viết Unit Test)
    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }
    @PostMapping("/hold")
    public ResponseEntity<?> holdRoom(@RequestBody BookingRequestDTO request) {
        try {
            // Bước 1: Delegate (Giao phó) toàn bộ thuật toán xử lý xuống Service
            String reservationId = bookingService.holdRoomForPayment(request);
            
            // Bước 2: Trả về HTTP 201 (Created) kèm mã đơn đặt phòng nếu thành công
            return ResponseEntity.status(HttpStatus.CREATED).body(reservationId);
            
        } catch (IllegalStateException e) {
            // Xử lý lỗi HTTP 409 (Conflict) nếu hết phòng hoặc không lấy được khóa Redisson
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
            
        } catch (IllegalArgumentException e) {
            // Xử lý lỗi HTTP 400 (Bad Request) nếu ngày nhận/trả không hợp lệ
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    public ResponseEntity<?> mockPayment(@PathVariable UUID id) {
        try {
            String result = bookingService.confirmPayment(id);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }
}