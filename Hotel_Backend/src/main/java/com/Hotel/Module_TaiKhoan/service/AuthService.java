package com.Hotel.Module_TaiKhoan.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.Hotel.Module_TaiKhoan.dto.AuthResponseDTO;
import com.Hotel.Module_TaiKhoan.dto.LoginRequestDTO;
import com.Hotel.Module_TaiKhoan.dto.RegistrationRequestDTO;
import com.Hotel.Module_TaiKhoan.entity.Account;
import com.Hotel.Module_TaiKhoan.entity.VerificationToken;
import com.Hotel.Module_TaiKhoan.repository.AccountRepository;
import com.Hotel.Module_TaiKhoan.repository.VerificationTokenRepository;
import com.Hotel.Module_TaiKhoan.security.JwtTokenProvider;

@Service
public class AuthService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final VerificationTokenRepository verificationTokenRepository; 
    private final EmailService emailService;                               

    public AuthService(AccountRepository accountRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       VerificationTokenRepository verificationTokenRepository,
                       EmailService emailService) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.verificationTokenRepository = verificationTokenRepository;
        this.emailService = emailService;
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

        // 7. Sinh mã xác thực ngẫu nhiên (dùng cấu trúc UUID)
        String token = java.util.UUID.randomUUID().toString();
    
        VerificationToken verificationToken = new VerificationToken();
        verificationToken.setToken(token);
        verificationToken.setAccount(newAccount);
        verificationToken.setLoaiToken("VERIFY_EMAIL");
        verificationToken.setNgayHetHan(java.time.LocalDateTime.now().plusHours(24)); // Có hiệu lực trong 24 giờ
    
        verificationTokenRepository.save(verificationToken);

        // 8. Ra lệnh cho bưu tá gửi email
        emailService.sendVerificationEmail(newAccount.getEmail(), token);
    }

    // Thêm hàm Login (UC02)
    public AuthResponseDTO login(LoginRequestDTO request) {
        // 1. Tìm tài khoản bằng Email (Ném lỗi chung chung để bảo mật theo UC02)
        Account account = accountRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Thông tin đăng nhập không đúng"));

        // 2. So khớp mật khẩu
        if (!passwordEncoder.matches(request.getMatKhau(), account.getMatKhau())) {
            // TODO: Bổ sung logic tăng số lần đăng nhập sai (soLanDangNhapSai) ở đây
            throw new IllegalArgumentException("Thông tin đăng nhập không đúng");
        }

        // 3. Kiểm tra trạng thái tài khoản
        if ("CHO_XAC_THUC".equals(account.getTrangThai())) {
            throw new IllegalStateException("Tài khoản chưa được xác thực. Vui lòng kiểm tra email.");
        }
        if ("BI_KHOA".equals(account.getTrangThai())) {
            throw new IllegalStateException("Tài khoản đã bị quản trị viên khoá.");
        }

        // 4. Nếu mọi thứ hợp lệ, sinh Token và trả về
        String token = jwtTokenProvider.generateToken(account.getEmail(), account.getVaiTro());
        
        // TODO: Reset bộ đếm soLanDangNhapSai về 0
        
        return new AuthResponseDTO(token, account.getEmail(), account.getVaiTro());
    }

    @Transactional
    public void verifyEmailToken(String token) {
        // 1. Tìm token trong cơ sở dữ liệu
        VerificationToken verificationToken = verificationTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Mã xác thực không tồn tại hoặc không hợp lệ."));

        // 2. Kiểm tra thời hạn của token
        if (verificationToken.getNgayHetHan().isBefore(java.time.LocalDateTime.now())) {
            throw new IllegalStateException("Mã xác thực đã hết hạn. Vui lòng yêu cầu gửi lại.");
        }

        // 3. Kích hoạt tài khoản
        Account account = verificationToken.getAccount();
        account.setTrangThai("DA_KICH_HOAT");
        accountRepository.save(account);

        // 4. Xóa token sau khi đã sử dụng
        verificationTokenRepository.delete(verificationToken);
    }
}
