package com.Hotel.Module_TaiKhoan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class UpdateProfileRequestDTO {

    @NotBlank(message = "Họ tên không được để trống")
    private String hoTen;

    @Pattern(regexp = "^$|^(0|\\+84)[0-9]{9}$", message = "Số điện thoại không hợp lệ")
    private String soDienThoai;

    // Cho phép để trống nếu chưa muốn điền, nhưng nếu điền phải đúng 12 chữ số CCCD
    @Pattern(regexp = "^$|^[0-9]{12}$", message = "CCCD phải gồm đúng 12 chữ số")
    private String cccd;

    public String getHoTen() { return hoTen; }
    public void setHoTen(String hoTen) { this.hoTen = hoTen; }

    public String getSoDienThoai() { return soDienThoai; }
    public void setSoDienThoai(String soDienThoai) { this.soDienThoai = soDienThoai; }

    public String getCccd() { return cccd; }
    public void setCccd(String cccd) { this.cccd = cccd; }
}