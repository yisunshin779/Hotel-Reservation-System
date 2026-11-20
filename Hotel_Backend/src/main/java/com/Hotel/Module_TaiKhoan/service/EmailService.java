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
}