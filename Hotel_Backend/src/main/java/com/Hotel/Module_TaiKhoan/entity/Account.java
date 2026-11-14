package com.Hotel.Module_TaiKhoan.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "ho_ten", nullable = false)
    private String hoTen;

    @Column(name = "email", unique = true, nullable = false, length = 100)
    private String email;

    @Column(name = "so_dien_thoai", length = 20)
    private String soDienThoai;

    // Bổ sung CCCD, cho phép null khi mới đăng ký (Lazy Collection)
    @Column(name = "cccd", unique = true, length = 12)
    private String cccd;

    // Mật khẩu có thể null nếu đăng nhập qua Google
    @Column(name = "mat_khau")
    private String matKhau;

    @Column(name = "auth_provider", nullable = false, length = 20)
    private String authProvider = "LOCAL"; // LOCAL hoặc GOOGLE

    @Column(name = "vai_tro", nullable = false, length = 20)
    private String vaiTro; // VD: ROLE_USER, ROLE_ADMIN, ROLE_RECEPTIONIST

    @Column(name = "trang_thai", nullable = false, length = 20)
    private String trangThai; // VD: CHO_XAC_THUC, DA_KICH_HOAT, BI_KHOA

    @Column(name = "so_lan_dang_nhap_sai")
    private int soLanDangNhapSai = 0;

    @Column(name = "thoi_gian_khoa")
    private LocalDateTime thoiGianKhoa;

    @Column(name = "thoi_gian_tao", nullable = false, updatable = false)
    private LocalDateTime thoiGianTao;

    public Account() {}

    @PrePersist
    protected void onCreate() {
        this.thoiGianTao = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getHoTen() { return hoTen; }
    public void setHoTen(String hoTen) { this.hoTen = hoTen; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getSoDienThoai() { return soDienThoai; }
    public void setSoDienThoai(String soDienThoai) { this.soDienThoai = soDienThoai; }

    public String getCccd() { return cccd; }
    public void setCccd(String cccd) { this.cccd = cccd; }

    public String getMatKhau() { return matKhau; }
    public void setMatKhau(String matKhau) { this.matKhau = matKhau; }

    public String getAuthProvider() { return authProvider; }
    public void setAuthProvider(String authProvider) { this.authProvider = authProvider; }

    public String getVaiTro() { return vaiTro; }
    public void setVaiTro(String vaiTro) { this.vaiTro = vaiTro; }

    public String getTrangThai() { return trangThai; }
    public void setTrangThai(String trangThai) { this.trangThai = trangThai; }

    public int getSoLanDangNhapSai() { return soLanDangNhapSai; }
    public void setSoLanDangNhapSai(int soLanDangNhapSai) { this.soLanDangNhapSai = soLanDangNhapSai; }

    public LocalDateTime getThoiGianKhoa() { return thoiGianKhoa; }
    public void setThoiGianKhoa(LocalDateTime thoiGianKhoa) { this.thoiGianKhoa = thoiGianKhoa; }

    public LocalDateTime getThoiGianTao() { return thoiGianTao; }
}