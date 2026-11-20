package com.Hotel.Module_TaiKhoan.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendVerificationEmail(String toEmail, String token) {
        String subject = "Xác thực tài khoản hệ thống khách sạn";
        String verificationUrl = "http://localhost:8080/api/v1/auth/verify?token=" + token;
        String message = "Chào mừng bạn gia nhập hệ thống. Vui lòng click vào đường dẫn sau để kích hoạt tài khoản:\n\n"
                + verificationUrl;

        SimpleMailMessage email = new SimpleMailMessage();
        if (fromEmail != null && !fromEmail.isBlank()) {
            email.setFrom(fromEmail);
        }
        email.setTo(toEmail);
        email.setSubject(subject);
        email.setText(message);

        mailSender.send(email);
    }

    public void sendResetPasswordEmail(String toEmail, String token) {
        String subject = "Yêu cầu đặt lại mật khẩu - Hệ thống Khách sạn";
        // Trỏ về trang nhập mật khẩu mới của Frontend
        String resetUrl = "http://localhost:5173/reset-password?token=" + token;
        String message = "Bạn vừa yêu cầu đặt lại mật khẩu. Vui lòng truy cập đường dẫn sau (có hiệu lực trong 15 phút):\n\n"
                + resetUrl
                + "\n\nMã token (dùng khi test API): " + token
                + "\n\nNếu bạn không thực hiện yêu cầu này, hãy bỏ qua email này.";

        SimpleMailMessage email = new SimpleMailMessage();
        if (fromEmail != null && !fromEmail.isBlank()) {
            email.setFrom(fromEmail);
        }
        email.setTo(toEmail);
        email.setSubject(subject);
        email.setText(message);

        mailSender.send(email);
    }   
}