package com.Hotel.Module_TaiKhoan.security;

import java.security.Key;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtTokenProvider {

    // Khóa bí mật dùng để ký JWT (Trong thực tế nên để ở file application.yml)
    @Value("${app.jwtSecret:MotChuoiBaoMatCucKyDaiVaPhucTapChoHeThongKhachSanCuaBan123456789}")
    private String jwtSecret;

    // Thời gian sống của Token (VD: 86400000 ms = 24 giờ)
    @Value("${app.jwtExpirationMs:86400000}")
    private int jwtExpirationMs;

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    // 1. Hàm tạo Token khi đăng nhập thành công
    public String generateToken(String email, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .setSubject(email) // Lưu email làm định danh chính
                .claim("role", role) // Nhét thêm Vai trò để Frontend phân quyền UI
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    // 2. Hàm lấy Email ngược lại từ Token (dùng khi kiểm duyệt Request)
    public String getEmailFromJwt(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
        return claims.getSubject();
    }
}