package com.Hotel.Module_TaiKhoan.service;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.Hotel.Module_TaiKhoan.dto.AuthResponseDTO;
import com.Hotel.Module_TaiKhoan.dto.LoginRequestDTO;
import com.Hotel.Module_TaiKhoan.dto.RegistrationRequestDTO;
import com.Hotel.Module_TaiKhoan.dto.ResetPasswordRequestDTO;
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
    
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MINUTES = 15;
    public AuthResponseDTO login(LoginRequestDTO request) {
        // 1. Tìm tài khoản bằng Email
        Account account = accountRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new IllegalArgumentException("Thông tin đăng nhập không đúng"));

        // 2. Kiểm tra trạng thái xác thực và khóa vĩnh viễn từ Admin
        if ("CHO_XAC_THUC".equals(account.getTrangThai())) {
            throw new IllegalStateException("Tài khoản chưa được xác thực. Vui lòng kiểm tra email.");
        }
        if ("BI_KHOA".equals(account.getTrangThai())) {
            throw new IllegalStateException("Tài khoản đã bị quản trị viên khóa.");
        }

        // 3. Kiểm tra trạng thái khóa tạm thời do Brute-force
        LocalDateTime now = LocalDateTime.now();
        if (account.getThoiGianKhoa() != null) {
            if (account.getThoiGianKhoa().isAfter(now)) {
                long remainingSeconds = Duration.between(now, account.getThoiGianKhoa()).getSeconds();
                long remainingMinutes = Math.max(1, (remainingSeconds + 59) / 60); // Làm tròn lên theo phút
                throw new IllegalStateException(
                    "Tài khoản tạm thời bị khóa do đăng nhập sai nhiều lần. Vui lòng thử lại sau " 
                    + remainingMinutes + " phút."
                );
            } else {
                // Đã hết 15 phút khóa -> Tự động mở khóa và reset bộ đếm
                account.setSoLanDangNhapSai(0);
                account.setThoiGianKhoa(null);
                accountRepository.save(account);
            }
        }

        // 4. Đối chiếu mật khẩu
        if (!passwordEncoder.matches(request.getMatKhau(), account.getMatKhau())) {
            int failedAttempts = account.getSoLanDangNhapSai() + 1;
            account.setSoLanDangNhapSai(failedAttempts);

            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                account.setThoiGianKhoa(now.plusMinutes(LOCK_DURATION_MINUTES));
                accountRepository.save(account);
                throw new IllegalStateException(
                    "Bạn đã đăng nhập sai quá " + MAX_FAILED_ATTEMPTS 
                    + " lần. Tài khoản bị khóa tạm thời trong " + LOCK_DURATION_MINUTES + " phút."
                );
            }

            accountRepository.save(account);
            throw new IllegalArgumentException("Thông tin đăng nhập không đúng");
        }

        // 5. Đăng nhập thành công -> Reset bộ đếm nếu trước đó từng nhập sai
        if (account.getSoLanDangNhapSai() > 0 || account.getThoiGianKhoa() != null) {
            account.setSoLanDangNhapSai(0);
            account.setThoiGianKhoa(null);
            accountRepository.save(account);
        }

        // 6. Sinh JWT và trả về
        String token = jwtTokenProvider.generateToken(account.getEmail(), account.getVaiTro());
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

    @Transactional
    public void forgotPassword(String email) {
        Account account = accountRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản với email này."));

        if ("BI_KHOA".equals(account.getTrangThai())) {
            throw new IllegalStateException("Tài khoản đã bị quản trị viên khóa.");
        }
        if ("GOOGLE".equalsIgnoreCase(account.getAuthProvider()) && account.getMatKhau() == null) {
            throw new IllegalStateException("Tài khoản này đăng nhập bằng Google, không hỗ trợ đặt lại mật khẩu nội bộ.");
        }

        // Sinh token đặt lại mật khẩu (hiệu lực ngắn: 15 phút)
        String token = java.util.UUID.randomUUID().toString();
        VerificationToken resetToken = new VerificationToken();
        resetToken.setToken(token);
        resetToken.setAccount(account);
        resetToken.setLoaiToken("RESET_PASSWORD");
        resetToken.setNgayHetHan(LocalDateTime.now().plusMinutes(15));

        verificationTokenRepository.save(resetToken);
        emailService.sendResetPasswordEmail(account.getEmail(), token);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequestDTO request) {
        if (!request.getMatKhauMoi().equals(request.getNhapLaiMatKhauMoi())) {
            throw new IllegalArgumentException("Mật khẩu nhập lại không khớp.");
        }

        VerificationToken resetToken = verificationTokenRepository.findByToken(request.getToken())
            .orElseThrow(() -> new IllegalArgumentException("Mã đặt lại mật khẩu không tồn tại hoặc không hợp lệ."));

        // Ngăn dùng nhầm token VERIFY_EMAIL cho luồng đổi mật khẩu
        if (!"RESET_PASSWORD".equals(resetToken.getLoaiToken())) {
            throw new IllegalArgumentException("Loại token không hợp lệ cho thao tác đặt lại mật khẩu.");
        }

        if (resetToken.getNgayHetHan().isBefore(LocalDateTime.now())) {
            verificationTokenRepository.delete(resetToken);
            throw new IllegalStateException("Mã đặt lại mật khẩu đã hết hạn. Vui lòng gửi lại yêu cầu.");
        }

        Account account = resetToken.getAccount();
        account.setMatKhau(passwordEncoder.encode(request.getMatKhauMoi()));
        // Mở khóa Brute-force nếu tài khoản đang bị khóa tạm thời
        account.setSoLanDangNhapSai(0);
        account.setThoiGianKhoa(null);
        accountRepository.save(account);

        // Xóa token để chỉ dùng đúng 1 lần
        verificationTokenRepository.delete(resetToken);
    }
}