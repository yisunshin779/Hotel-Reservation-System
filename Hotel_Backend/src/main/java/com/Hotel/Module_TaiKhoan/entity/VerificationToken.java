package com.Hotel.Module_TaiKhoan.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Account getAccount() { return account; }
    public void setAccount(Account account) { this.account = account; }

    public String getLoaiToken() { return loaiToken; }
    public void setLoaiToken(String loaiToken) { this.loaiToken = loaiToken; }

    public LocalDateTime getNgayHetHan() { return ngayHetHan; }
    public void setNgayHetHan(LocalDateTime ngayHetHan) { this.ngayHetHan = ngayHetHan; }
}