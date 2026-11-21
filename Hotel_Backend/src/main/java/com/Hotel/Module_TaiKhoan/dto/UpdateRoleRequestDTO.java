package com.Hotel.Module_TaiKhoan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class UpdateRoleRequestDTO {

    @NotBlank(message = "Vai trò không được để trống")
    @Pattern(
        regexp = "^(ROLE_USER|ROLE_RECEPTIONIST|ROLE_ADMIN)$",
        message = "Vai trò không hợp lệ. Chỉ chấp nhận: ROLE_USER, ROLE_RECEPTIONIST, ROLE_ADMIN"
    )
    private String vaiTro;

    public String getVaiTro() { return vaiTro; }
    public void setVaiTro(String vaiTro) { this.vaiTro = vaiTro; }
}