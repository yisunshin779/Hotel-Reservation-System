package com.Hotel.Module_DatPhong.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "room_type")
public class RoomType {

    @Id
    @Column(name = "id", length = 50, nullable = false)
    private String id; // Sử dụng mã định danh như "STD", "DLX", "VIP" 

    @Column(name = "ten_loai_phong", nullable = false)
    private String tenLoaiPhong;

    @Column(name = "tong_so_phong", nullable = false)
    private int tongSoPhong; // Trường dữ liệu cốt lõi phục vụ thuật toán kiểm tra quỹ phòng

    @Column(name = "gia_tien_co_ban", nullable = false)
    private BigDecimal giaTienCoBan; // Dùng BigDecimal để đảm bảo độ chính xác cho tiền tệ

    public RoomType() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenLoaiPhong() { return tenLoaiPhong; }
    public void setTenLoaiPhong(String tenLoaiPhong) { this.tenLoaiPhong = tenLoaiPhong; }

    public int getTongSoPhong() { return tongSoPhong; }
    public void setTongSoPhong(int tongSoPhong) { this.tongSoPhong = tongSoPhong; }

    public BigDecimal getGiaTienCoBan() { return giaTienCoBan; }
    public void setGiaTienCoBan(BigDecimal giaTienCoBan) { this.giaTienCoBan = giaTienCoBan; }
}