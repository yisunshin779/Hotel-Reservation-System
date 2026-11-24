package com.Hotel.Module_TaiKhoan.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Builds and sends e-mails. Called only by the notification dispatcher, outside business
 * transactions; business flows queue requests in an outbox instead of calling SMTP directly.
 */
@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;
    @Value("${app.public-base-url}")
    private String publicBaseUrl;
    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendVerificationEmail(String toEmail, String token) {
        String verificationUrl = UriComponentsBuilder.fromHttpUrl(publicBaseUrl)
                .pathSegment("api", "v1", "auth", "verify")
                .queryParam("token", token).build().encode().toUriString();
        send(toEmail, "Xác thực tài khoản hệ thống khách sạn",
                "Chào mừng bạn gia nhập hệ thống. Vui lòng click vào đường dẫn sau để kích hoạt tài khoản:\n\n"
                + verificationUrl);
    }

    public void sendResetPasswordEmail(String toEmail, String token, long validMinutes) {
        // Trỏ về trang nhập mật khẩu mới của Frontend
        String resetUrl = UriComponentsBuilder.fromHttpUrl(frontendBaseUrl)
                .pathSegment("reset-password")
                .queryParam("token", token).build().encode().toUriString();
        send(toEmail, "Yêu cầu đặt lại mật khẩu - Hệ thống Khách sạn",
                "Bạn vừa yêu cầu đặt lại mật khẩu. Vui lòng truy cập đường dẫn sau (có hiệu lực trong "
                + validMinutes + " phút):\n\n" + resetUrl
                + "\n\nNếu bạn không thực hiện yêu cầu này, hãy bỏ qua email này.");
    }

    public void sendPasswordChangedEmail(String toEmail) {
        send(toEmail, "Mật khẩu đã được thay đổi - Hệ thống Khách sạn",
                "Mật khẩu tài khoản của bạn vừa được đặt lại. Các phiên đăng nhập trước đó đã bị hủy.\n\n"
                + "Nếu bạn không thực hiện thao tác này, hãy liên hệ bộ phận hỗ trợ ngay.");
    }

    public void send(String toEmail, String subject, String text) {
        SimpleMailMessage email = new SimpleMailMessage();
        if (fromEmail != null && !fromEmail.isBlank()) {
            email.setFrom(fromEmail);
        }
        email.setTo(toEmail);
        email.setSubject(subject);
        email.setText(text);
        mailSender.send(email);
    }
}
