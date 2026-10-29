package com.Hotel.Module_DatPhong.dto;

import java.time.LocalDate;

/**
 * DTO vận chuyển các tiêu chí tìm kiếm phòng từ Client.
 */
public record SearchRequestDTO(
    LocalDate ngayNhan,
    LocalDate ngayTra,
    int soNguoiLon,
    int soTreEm,
    // Có thể bổ sung thêm các trường tùy chọn sau này:
    // String maLoaiPhong, 
    // Double giaToiThieu,
    // Double giaToiDa
) {
    /**
     * Compact Constructor: Khối mã này tự động chạy ngay khi Record được khởi tạo
     * để kiểm tra tính hợp lệ của dữ liệu (Validation) trước khi tạo ra đối tượng.
     */
    public SearchRequestDTO {
        if (ngayNhan == null || ngayTra == null) {
            throw new IllegalArgumentException("Ngày nhận và ngày trả phòng không được để trống.");
        }
        if (ngayNhan.isAfter(ngayTra) || ngayNhan.isEqual(ngayTra)) {
            throw new IllegalArgumentException("Ngày nhận phòng phải diễn ra trước ngày trả.");
        }
        if (ngayNhan.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Không thể tìm kiếm phòng cho ngày trong quá khứ.");
        }
        if (soNguoiLon < 1) {
            throw new IllegalArgumentException("Phải có ít nhất 1 người lớn để đặt phòng.");
        }
    }
}