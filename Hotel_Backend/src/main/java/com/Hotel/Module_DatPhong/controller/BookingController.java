package com.Hotel.Module_DatPhong.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.BeanInstantiationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.Hotel.Module_DatPhong.dto.BookingRequestDTO;
import com.Hotel.Module_DatPhong.dto.SearchRequestDTO;
import com.Hotel.Module_DatPhong.service.BookingService;
import com.Hotel.Module_DatPhong.service.CanonicalBookingService;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final CanonicalBookingService canonical;
    private final boolean canonicalEnabled;

    // Dependency Injection qua Constructor (Khuyên dùng thay vì @Autowired để dễ viết Unit Test)
    public BookingController(BookingService bookingService, CanonicalBookingService canonical,
                             @Value("${hotel.booking.canonical-enabled:false}") boolean canonicalEnabled) {
        this.bookingService = bookingService;
        this.canonical = canonical;
        this.canonicalEnabled = canonicalEnabled;
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchRooms(@ModelAttribute SearchRequestDTO request) {
        try {
            List<?> availableRooms = canonicalEnabled ? canonical.search(request)
                    : bookingService.searchAvailableRooms(request);
            
            if (availableRooms.isEmpty()) {
                return ResponseEntity.ok("Không có phòng trống phù hợp với khoảng thời gian này.");
            }
            return ResponseEntity.ok(availableRooms);
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    @PostMapping("/hold")
    public ResponseEntity<?> holdRoom(@RequestBody BookingRequestDTO request, Authentication authentication,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            // Bước 1: Delegate (Giao phó) toàn bộ thuật toán xử lý xuống Service
            String reservationId = canonicalEnabled
                    ? canonical.hold(request, authentication.getName(), requireIdempotencyKey(idempotencyKey))
                    : bookingService.holdRoomForPayment(request);
            
            // Bước 2: Trả về HTTP 201 (Created) kèm mã đơn đặt phòng nếu thành công
            return ResponseEntity.status(HttpStatus.CREATED).body(reservationId);
            
        } catch (IllegalStateException e) {
            // Xử lý lỗi HTTP 409 (Conflict) nếu hết phòng.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
            
        } catch (IllegalArgumentException e) {
            // Xử lý lỗi HTTP 400 (Bad Request) nếu ngày nhận/trả không hợp lệ
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        }
    }

    /**
     * Canonical hold requires a UUID Idempotency-Key, validated before any write. The same customer,
     * key and request return the same reservation code; the same key with other data returns 409.
     */
    private static UUID requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        try {
            UUID key = UUID.fromString(value.trim());
            if (!key.toString().equalsIgnoreCase(value.trim())) {
                throw new IllegalArgumentException("Idempotency-Key must be a UUID");
            }
            return key;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Idempotency-Key must be a UUID", ex);
        }
    }

    /**
     * Search criteria are validated while the record is bound. Without these handlers the binding
     * failure reached the generic handler and returned HTTP 500 for invalid dates.
     */
    @ExceptionHandler(BeanInstantiationException.class)
    public ResponseEntity<String> invalidCriteria(BeanInstantiationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof IllegalArgumentException) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(cause.getMessage());
        }
        throw ex;
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<String> unreadableCriteria(BindException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Dữ liệu tìm kiếm không hợp lệ.");
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
