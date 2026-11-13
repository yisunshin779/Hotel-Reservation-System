package com.Hotel.Module_TaiKhoan.entity;

import java.time.LocalDateTime;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "verification_token")
public class VerificationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "token", nullable = false, unique = true)
    private String token;

    // Liên kết N-1 với bảng Account
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "loai_token", nullable = false, length = 20)
    private String loaiToken; // VERIFY_EMAIL hoặc RESET_PASSWORD

    @Column(name = "ngay_het_han", nullable = false)
    private LocalDateTime ngayHetHan;

    public VerificationToken() {}

    // --- GENERATE GETTER VÀ SETTER Ở ĐÂY ---
}