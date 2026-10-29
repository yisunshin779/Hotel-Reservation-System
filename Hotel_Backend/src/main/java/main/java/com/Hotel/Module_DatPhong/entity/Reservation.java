package main.java.com.Hotel.Module_DatPhong.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservation") // Thay đổi tên này cho khớp chính xác với file 01_schema.sql của bạn
public class Reservation {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id; // Sử dụng UUID làm khóa chính để tăng tính bảo mật cho đơn đặt phòng

    @Column(name = "ma_loai_phong", nullable = false)
    private String maLoaiPhong;

    @Column(name = "so_luong_phong", nullable = false)
    private int soLuongPhong;

    @Column(name = "ngay_nhan", nullable = false)
    private LocalDate ngayNhan;

    @Column(name = "ngay_tra", nullable = false)
    private LocalDate ngayTra;

    @Column(name = "trang_thai", length = 50, nullable = false)
    private String trangThai; // Lưu các giá trị: PENDING_PAYMENT, CONFIRMED, CHECKED_IN

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Các hàm Constructor (Khởi tạo)
    public Reservation() {
    }

    // Các hàm Getters và Setters bắt buộc để Spring Boot thao tác với dữ liệu
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

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
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}