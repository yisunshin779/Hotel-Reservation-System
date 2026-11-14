package com.Hotel.Module_TaiKhoan.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 1. Bắt lỗi từ @Valid (MethodArgumentNotValidException)
     * Xảy ra khi Frontend gửi DTO vi phạm các luật như @NotBlank, @Email, @Pattern...
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        
        // Duyệt qua tất cả các trường bị lỗi và lấy tin nhắn (message) đã định nghĩa trong DTO
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        // Đóng gói thành JSON chuẩn
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", "Dữ liệu đầu vào không hợp lệ");
        response.put("errors", errors); // Frontend sẽ dựa vào object errors này để báo đỏ từng ô input

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST); // Trả về HTTP 400
    }

    /**
     * 2. Bắt lỗi logic nghiệp vụ (IllegalArgumentException & IllegalStateException)
     * Xảy ra khi AuthService chủ động ném lỗi (vd: Mật khẩu không khớp, Email đã tồn tại)
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleBusinessLogicExceptions(RuntimeException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        
        // Lấy đúng câu thông báo tiếng Việt mà bạn đã viết trong AuthService
        response.put("message", ex.getMessage()); 

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST); // Trả về HTTP 400
    }

    /**
     * 3. Bắt các lỗi hệ thống không lường trước được (Exception chung)
     * Tránh việc sập server hoặc rò rỉ mã nguồn ra ngoài
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGlobalException(Exception ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau.");
        
        // Trong môi trường Dev, bạn có thể print log ra console để debug
        ex.printStackTrace(); 

        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR); // Trả về HTTP 500
    }
}