package com.Hotel.Module_TaiKhoan.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.Hotel.Module_TaiKhoan.dto.AuthResponseDTO;
import com.Hotel.Module_TaiKhoan.dto.ForgotPasswordRequestDTO;
import com.Hotel.Module_TaiKhoan.dto.GoogleLoginRequestDTO;
import com.Hotel.Module_TaiKhoan.dto.LoginRequestDTO;
import com.Hotel.Module_TaiKhoan.dto.RegistrationRequestDTO;
import com.Hotel.Module_TaiKhoan.dto.ResetPasswordRequestDTO;
import com.Hotel.Module_TaiKhoan.service.AuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    // Constructor Injection
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@Valid @RequestBody RegistrationRequestDTO request) {
        
        // Gọi Service để xử lý logic băm mật khẩu và lưu DB
        authService.registerUser(request);
        
        // Trả về mã HTTP 201 (Created) cùng phản hồi dạng JSON chuẩn
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                    "status", "success",
                    "message", "Đăng ký thành công! Hệ thống đang chờ xác thực email."
                ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@Valid @RequestBody LoginRequestDTO request) {
    
    // Gọi Service xử lý logic và nhận hộp DTO chứa Token
        AuthResponseDTO response = authService.login(request);
    
    // Trả Token về cho React
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Đăng nhập thành công!",
            "data", response
        ));
    }

    @GetMapping("/verify")
    public ResponseEntity<?> verifyEmail(@RequestParam("token") String token) {
        // Gọi Service rà soát và đối chiếu token
        authService.verifyEmailToken(token);
    
        return ResponseEntity.ok(java.util.Map.of(
            "status", "success",
            "message", "Tài khoản đã được xác thực thành công. Bạn có thể đăng nhập."
        ));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDTO request) {
        authService.forgotPassword(request.getEmail());
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Liên kết đặt lại mật khẩu đã được gửi tới email của bạn."
        ));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequestDTO request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Đặt lại mật khẩu thành công. Bạn có thể đăng nhập bằng mật khẩu mới."
        ));
    }

    @PostMapping("/google")
    public ResponseEntity<?> loginWithGoogle(@Valid @RequestBody GoogleLoginRequestDTO request) {
        AuthResponseDTO response = authService.loginWithGoogle(request.getIdToken());
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Đăng nhập bằng Google thành công!",
            "data", response
        ));
    }
}