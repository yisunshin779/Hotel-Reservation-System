package com.Hotel.Module_TaiKhoan.security;

import java.security.Key;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;

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

    // 3. Bổ sung hàm validateToken vào cuối file:
    public boolean validateToken(String authToken) {
        try {
            Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(authToken);
            return true;
        } catch (SignatureException ex) {
            System.out.println("Chữ ký JWT không hợp lệ");
        } catch (MalformedJwtException ex) {
            System.out.println("Token JWT bị hỏng, sai định dạng");
        } catch (ExpiredJwtException ex) {
            System.out.println("Token JWT đã hết hạn");
        } catch (UnsupportedJwtException ex) {
            System.out.println("Token JWT không được hỗ trợ");
        } catch (IllegalArgumentException ex) {
            System.out.println("Chuỗi claims của JWT trống");
        }
        return false;
    }
}