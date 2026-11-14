package com.Hotel.Module_TaiKhoan.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.Hotel.Module_TaiKhoan.dto.RegistrationRequestDTO;
import com.Hotel.Module_TaiKhoan.entity.Account;
import com.Hotel.Module_TaiKhoan.repository.AccountRepository;

@Service
public class AuthService {
    
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    // Constructor Injection
    public AuthService(AccountRepository accountRepository, PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void registerUser(RegistrationRequestDTO request) {
        // 1. Kiểm tra tính đồng nhất của mật khẩu
        if (!request.getMatKhau().equals(request.getNhapLaiMatKhau())) {
            throw new IllegalArgumentException("Mật khẩu nhập lại không khớp.");
        }

        // 2. Rào chắn chống trùng lặp Email (Luồng ngoại lệ 4b)
        if (accountRepository.existsByEmail(request.getEmail())) {
            throw new IllegalStateException("Email này đã được sử dụng trong hệ thống.");
        }

        // 3. Khởi tạo thực thể và đổ dữ liệu từ DTO
        Account newAccount = new Account();
        newAccount.setHoTen(request.getHoTen());
        newAccount.setEmail(request.getEmail());
        newAccount.setSoDienThoai(request.getSoDienThoai());

        // 4. Băm mật khẩu (Hashing) - Chuyển plain-text thành chuỗi mã hóa 1 chiều
        newAccount.setMatKhau(passwordEncoder.encode(request.getMatKhau()));

        // 5. Cài đặt các giá trị hệ thống cho tài khoản mới
        newAccount.setVaiTro("ROLE_USER");
        newAccount.setTrangThai("CHO_XAC_THUC");
        newAccount.setAuthProvider("LOCAL");

        // 6. Ghi xuống Cơ sở dữ liệu
        accountRepository.save(newAccount);

        // TODO: Sinh VerificationToken và gọi EmailService để gửi link kích hoạt (Sẽ triển khai sau)
    }
}
