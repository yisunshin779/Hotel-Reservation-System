package com.Hotel.Module_DatPhong.dto;

import java.time.LocalDate;

/**
 * Đối tượng vận chuyển dữ liệu (DTO) từ Client lên Controller.
 * Bản chất Record của Java giúp đảm bảo dữ liệu không bị thay đổi (immutable) trong quá trình xử lý.
 */
public record BookingRequestDTO(
    String maLoaiPhong,
    int soLuongPhong,
    LocalDate ngayNhan,
    LocalDate ngayTra,
    String thongTinKhachHang // Có thể là email hoặc mã khách hàng
) {
    // Không cần viết Getters/Setters, Java tự động xử lý.
    // Khi gọi dữ liệu, thay vì dùng request.getMaLoaiPhong(), ta dùng request.maLoaiPhong()
}