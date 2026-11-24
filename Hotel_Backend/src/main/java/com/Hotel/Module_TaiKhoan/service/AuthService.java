package com.Hotel.Module_TaiKhoan.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

@Service
public class AuthService {

    private final AccountRepository accountRepository;
    private final HotelCustomerProvisioning customers;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final VerificationTokenRepository verificationTokenRepository;
    private final AccountEmailOutbox outbox;
    private final Clock clock;

    /** OC-03.01: a reset token is valid for 30 minutes and can be used once. */
    public static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30);
    public static final Duration VERIFY_TOKEN_TTL = Duration.ofHours(24);
    static final String RESET_PASSWORD = "RESET_PASSWORD";

    public AuthService(AccountRepository accountRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       VerificationTokenRepository verificationTokenRepository,
                       AccountEmailOutbox outbox,
                       HotelCustomerProvisioning customers,
                       Clock clock) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.verificationTokenRepository = verificationTokenRepository;
        this.outbox = outbox;
        this.customers = customers;
        this.clock = clock;
    }

    // verification_token stores server-local timestamps; keep that convention with a testable clock.
    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
    }

    private String issue(Account account) {
        return jwtTokenProvider.generateToken(account.getId(), account.getEmail(), account.getVaiTro(),
                account.getTokenVersion());
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
        verificationToken.setNgayHetHan(now().plus(VERIFY_TOKEN_TTL)); // Có hiệu lực trong 24 giờ

        // Flush so the JDBC outbox row can reference the new account and token.
        verificationTokenRepository.saveAndFlush(verificationToken);

        // 8. Xếp email vào outbox trong cùng giao dịch; worker gửi sau khi commit.
        outbox.enqueue(newAccount.getId(), AccountEmailOutbox.Purpose.VERIFY_EMAIL, verificationToken.getId());
    }

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MINUTES = 15;
    @Transactional(noRollbackFor = {IllegalArgumentException.class, IllegalStateException.class})
    public AuthResponseDTO login(LoginRequestDTO request) {
        // 1. Tìm tài khoản bằng Email
        Account account = accountRepository.lockByEmail(request.getEmail())
            .orElseThrow(() -> new IllegalArgumentException("Thông tin đăng nhập không đúng"));

        // 2. Kiểm tra trạng thái xác thực và khóa vĩnh viễn từ Admin
        if ("CHO_XAC_THUC".equals(account.getTrangThai())) {
            throw new IllegalStateException("Tài khoản chưa được xác thực. Vui lòng kiểm tra email.");
        }
        if ("BI_KHOA".equals(account.getTrangThai())) {
            throw new IllegalStateException("Tài khoản đã bị quản trị viên khóa.");
        }

        // 3. Kiểm tra trạng thái khóa tạm thời do Brute-force
        LocalDateTime now = now();
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

        if (account.getMatKhau() == null || account.getMatKhau().isBlank()) {
            throw new IllegalStateException(
                "Tài khoản này được đăng ký qua Google. Vui lòng sử dụng nút 'Đăng nhập với Google'."
            );
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
        return new AuthResponseDTO(issue(account), account.getEmail(), account.getVaiTro());
    }

    @Transactional
    public HotelCustomerProvisioning.Result verifyEmailToken(String token) {
        // Lock order account -> token, the same as password reset and token issue.
        String unknown = "Mã xác thực không tồn tại hoặc không hợp lệ.";
        Account account = lockTokenOwner(token, unknown);
        VerificationToken verificationToken = lockedToken(token, account, unknown);

        if (!"VERIFY_EMAIL".equals(verificationToken.getLoaiToken())) {
            throw new IllegalArgumentException("Loại token không hợp lệ cho thao tác xác thực email.");
        }

        // Kiểm tra thời hạn của token (cùng quy tắc với NotificationDispatcher khi gửi link)
        if (isExpired(verificationToken.getNgayHetHan())) {
            throw new IllegalStateException("Mã xác thực đã hết hạn. Vui lòng yêu cầu gửi lại.");
        }

        // Kích hoạt tài khoản
        if (!"CHO_XAC_THUC".equals(account.getTrangThai())) {
            throw new IllegalStateException("Tài khoản không ở trạng thái chờ xác thực.");
        }
        account.setTrangThai("DA_KICH_HOAT");
        accountRepository.save(account);
        HotelCustomerProvisioning.Result profile = customers.provision(account);

        // Xóa token sau khi đã sử dụng
        verificationTokenRepository.delete(verificationToken);
        return profile;
    }

    /** A token is valid up to and including its deadline; the API and the e-mail worker share this rule. */
    public static boolean isExpired(LocalDateTime deadline, LocalDateTime now) {
        return deadline == null || deadline.isBefore(now);
    }

    private boolean isExpired(LocalDateTime deadline) {
        return isExpired(deadline, now());
    }

    /** Finds the owner without a lock, then locks the account row before any token row. */
    private Account lockTokenOwner(String token, String unknownMessage) {
        UUID accountId = token == null ? null : verificationTokenRepository.findAccountIdByToken(token).orElse(null);
        if (accountId == null) {
            throw new IllegalArgumentException(unknownMessage);
        }
        return accountRepository.lockById(accountId).orElseThrow(() -> new IllegalArgumentException(unknownMessage));
    }

    /** Re-reads and locks the token under the account lock: a concurrent use may have deleted it. */
    private VerificationToken lockedToken(String token, Account owner, String unknownMessage) {
        return verificationTokenRepository.findByToken(token)
                .filter(found -> owner.getId().equals(found.getAccount().getId()))
                .orElseThrow(() -> new IllegalArgumentException(unknownMessage));
    }

    /**
     * OC-03.01. The caller always receives the same public response; a token is created and an
     * e-mail queued only for an active account with a local password. Nothing reveals which case applied.
     */
    @Transactional
    public void forgotPassword(String email) {
        // Locks the account row: issuing a reset token is serialized with its use (resetPassword).
        Account account = accountRepository.lockByEmail(email).orElse(null);
        if (account == null || !"DA_KICH_HOAT".equals(account.getTrangThai())
                || account.getMatKhau() == null || account.getMatKhau().isBlank()) {
            return;
        }

        VerificationToken resetToken = new VerificationToken();
        resetToken.setToken(UUID.randomUUID().toString());
        resetToken.setAccount(account);
        resetToken.setLoaiToken(RESET_PASSWORD);
        resetToken.setNgayHetHan(now().plus(RESET_TOKEN_TTL));

        verificationTokenRepository.saveAndFlush(resetToken);
        outbox.enqueue(account.getId(), AccountEmailOutbox.Purpose.RESET_PASSWORD, resetToken.getId());
    }

    /**
     * OC-03.02. Under the account lock the token, its purpose, its deadline and the account status are
     * checked again. On success, in one transaction: the password changes, token_version increases (older
     * JWTs are rejected), every RESET_PASSWORD token of the account is deleted (older links stop working and
     * their pending e-mails are dropped) and the PASSWORD_CHANGED e-mail is queued. VERIFY_EMAIL tokens stay.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequestDTO request) {
        if (!request.getMatKhauMoi().equals(request.getNhapLaiMatKhauMoi())) {
            throw new IllegalArgumentException("Mật khẩu nhập lại không khớp.");
        }
        String unknown = "Mã đặt lại mật khẩu không tồn tại hoặc không hợp lệ.";
        Account account = lockTokenOwner(request.getToken(), unknown);
        VerificationToken resetToken = lockedToken(request.getToken(), account, unknown);

        // Ngăn dùng nhầm token VERIFY_EMAIL cho luồng đổi mật khẩu
        if (!RESET_PASSWORD.equals(resetToken.getLoaiToken())) {
            throw new IllegalArgumentException("Loại token không hợp lệ cho thao tác đặt lại mật khẩu.");
        }
        if (isExpired(resetToken.getNgayHetHan())) {
            throw new IllegalStateException("Mã đặt lại mật khẩu đã hết hạn. Vui lòng gửi lại yêu cầu.");
        }
        if ("BI_KHOA".equals(account.getTrangThai())) {
            throw new IllegalStateException("Tài khoản đã bị quản trị viên khóa.");
        }
        if (!"DA_KICH_HOAT".equals(account.getTrangThai())) {
            throw new IllegalStateException("Tài khoản chưa được kích hoạt.");
        }
        account.setMatKhau(passwordEncoder.encode(request.getMatKhauMoi()));
        // Mở khóa Brute-force nếu tài khoản đang bị khóa tạm thời
        account.setSoLanDangNhapSai(0);
        account.setThoiGianKhoa(null);
        // OC-03.02: hủy các phiên đăng nhập cũ và thông báo đổi mật khẩu.
        account.revokeTokens();
        accountRepository.save(account);
        outbox.enqueue(account.getId(), AccountEmailOutbox.Purpose.PASSWORD_CHANGED, null);

        // Mọi link đặt lại đã cấp (kể cả link vừa dùng) hết hiệu lực; token VERIFY_EMAIL giữ nguyên.
        verificationTokenRepository.deleteByAccountAndPurpose(account.getId(), RESET_PASSWORD);
    }

    @Value("${google.client-id}")
    private String googleClientId;
    private GoogleIdTokenVerifier googleVerifier;

    @jakarta.annotation.PostConstruct
    void configureGoogleVerifier() {
        if (googleClientId.isBlank()) {
            return;
        }
        if (!googleClientId.matches("[a-zA-Z0-9-]+\\.apps\\.googleusercontent\\.com")) {
            throw new IllegalArgumentException("HOTEL_GOOGLE_CLIENT_ID is not a Google OAuth client ID");
        }
        googleVerifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(java.util.List.of(googleClientId))
                .build();
    }

    @Transactional
    public AuthResponseDTO loginWithGoogle(String idTokenString) {
        var payload = verifyGoogleToken(idTokenString);
        Account account = accountRepository.findByGoogleSubject(payload.getSubject()).orElse(null);
        if (account == null) {
            String email = payload.getEmail();
            if (!Boolean.TRUE.equals(payload.getEmailVerified()) || email == null || email.isBlank()) {
                throw new IllegalArgumentException("Tài khoản Google cần email đã xác minh.");
            }
            email = email.toLowerCase(Locale.ROOT);
            if (accountRepository.existsByEmailIgnoreCase(email)) {
                throw new IllegalStateException("Tài khoản đã tồn tại. Hãy đăng nhập nội bộ để liên kết Google hoặc liên hệ hỗ trợ.");
            }

            account = new Account();
            Object name = payload.get("name");
            account.setHoTen(name instanceof String text && !text.isBlank() ? text : email);
            account.setEmail(email);
            account.setAuthProvider("GOOGLE");
            account.setVaiTro("ROLE_USER");
            account.setTrangThai("DA_KICH_HOAT");
            account.setGoogleSubject(payload.getSubject());
            saveGoogleAccount(account);
            customers.provision(account);
        }

        requireActive(account);
        return new AuthResponseDTO(issue(account), account.getEmail(), account.getVaiTro());
    }

    @Transactional
    public void linkGoogle(String accountEmail, String idTokenString) {
        var payload = verifyGoogleToken(idTokenString);
        Account account = accountRepository.lockByEmail(accountEmail)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy tài khoản."));
        requireActive(account);

        String subject = payload.getSubject();
        if (subject.equals(account.getGoogleSubject())) {
            return;
        }
        if (account.getGoogleSubject() != null || accountRepository.findByGoogleSubject(subject).isPresent()) {
            throw new IllegalStateException("Liên kết Google đã tồn tại.");
        }
        account.setGoogleSubject(subject);
        saveGoogleAccount(account);
    }

    private GoogleIdToken.Payload verifyGoogleToken(String idTokenString) {
        if (googleVerifier == null) {
            throw new IllegalStateException("Đăng nhập Google chưa được cấu hình.");
        }

        try {
            GoogleIdToken idToken = googleVerifier.verify(idTokenString);
            if (idToken != null) {
                var payload = idToken.getPayload();
                String issuer = payload.getIssuer();
                if (("https://accounts.google.com".equals(issuer) || "accounts.google.com".equals(issuer))
                        && payload.getExpirationTimeSeconds() != null
                        && payload.getExpirationTimeSeconds() > java.time.Instant.now().getEpochSecond()
                        && payload.getSubject() != null && !payload.getSubject().isBlank()
                        && payload.getSubject().length() <= 255) {
                    return payload;
                }
            }
        } catch (java.io.IOException | java.security.GeneralSecurityException ex) {
            throw new IllegalArgumentException("Không thể xác thực Google ID Token.");
        }
        throw new IllegalArgumentException("Google ID Token không hợp lệ hoặc đã hết hạn.");
    }

    private void saveGoogleAccount(Account account) {
        try {
            accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("Liên kết Google hoặc email đã được sử dụng.", ex);
        }
    }

    private void requireActive(Account account) {
        if (!"DA_KICH_HOAT".equals(account.getTrangThai())) {
            throw new IllegalStateException("Tài khoản chưa được kích hoạt hoặc đã bị khóa.");
        }
    }
}
