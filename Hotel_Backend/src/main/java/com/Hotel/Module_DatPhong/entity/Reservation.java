package com.Hotel.Module_DatPhong.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservation") 
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id; // Chuyển sang kiểu UUID và để Spring Boot tự động sinh mã

    @Column(name = "ma_loai_phong", nullable = false)
    private String maLoaiPhong;

    @Column(name = "so_luong_phong", nullable = false)
    private int soLuongPhong;

    @Column(name = "ngay_nhan", nullable = false)
    private LocalDate ngayNhan;

    @Column(name = "ngay_tra", nullable = false)
    private LocalDate ngayTra;

    @Column(name = "trang_thai", length = 50, nullable = false)
    private String trangThai; 

    // Đổi tên biến từ createdAt thành thoiGianTao để khớp với BookingService
    @Column(name = "thoi_gian_tao", nullable = false)
    private LocalDateTime thoiGianTao;

    public Reservation() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getMaLoaiPhong() { return maLoaiPhong; }
    public void setMaLoaiPhong(String maLoaiPhong) { this.maLoaiPhong = maLoaiPhong; }

    public int getSoLuongPhong() { return soLuongPhong; }
    public void setSoLuongPhong(int soLuongPhong) { this.soLuongPhong = soLuongPhong; }

    public LocalDate getNgayNhan() { return ngayNhan; }
    public void setNgayNhan(LocalDate ngayNhan) { this.ngayNhan = ngayNhan; }

    public LocalDate getNgayTra() { return ngayTra; }
    public void setNgayTra(LocalDate ngayTra) { this.ngayTra = ngayTra; }

    public String getTrangThai() { return trangThai; }
    public void setTrangThai(String trangThai) { this.trangThai = trangThai; }
    
    public LocalDateTime getThoiGianTao() { return thoiGianTao; }
    public void setThoiGianTao(LocalDateTime thoiGianTao) { this.thoiGianTao = thoiGianTao; }
}