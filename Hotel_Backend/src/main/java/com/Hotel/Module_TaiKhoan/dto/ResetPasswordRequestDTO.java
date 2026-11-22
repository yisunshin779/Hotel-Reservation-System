package com.Hotel.Module_TaiKhoan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ResetPasswordRequestDTO {

    @NotBlank(message = "Token đặt lại mật khẩu không được để trống")
    private String token;

    @NotBlank(message = "Mật khẩu mới không được để trống")
    @Size(min = 6, message = "Mật khẩu mới phải có ít nhất 6 ký tự")
    private String matKhauMoi;

    @NotBlank(message = "Vui lòng nhập lại mật khẩu mới")
    private String nhapLaiMatKhauMoi;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getMatKhauMoi() { return matKhauMoi; }
    public void setMatKhauMoi(String matKhauMoi) { this.matKhauMoi = matKhauMoi; }

    public String getNhapLaiMatKhauMoi() { return nhapLaiMatKhauMoi; }
    public void setNhapLaiMatKhauMoi(String nhapLaiMatKhauMoi) { this.nhapLaiMatKhauMoi = nhapLaiMatKhauMoi; }
}