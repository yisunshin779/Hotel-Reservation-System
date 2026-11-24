package com.Hotel.Module_TaiKhoan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ResetPasswordRequestDTO {

    @NotBlank(message = "Token đặt lại mật khẩu không được để trống")
    private String token;

    @NotBlank(message = "Mật khẩu mới không được để trống")
    // OC-03.02: mật khẩu mới theo cùng quy tắc với đăng ký (OC-01.01).
    @Size(min = 8, message = "Mật khẩu mới phải có ít nhất 8 ký tự")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{8,}$", message = "Mật khẩu mới phải bao gồm cả chữ và số")
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