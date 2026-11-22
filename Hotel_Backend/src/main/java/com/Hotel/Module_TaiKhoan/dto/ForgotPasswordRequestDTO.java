package com.Hotel.Module_TaiKhoan.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class ForgotPasswordRequestDTO {

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    private String email;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}