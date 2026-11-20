package com.Hotel.Module_TaiKhoan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class UpdateStatusRequestDTO {

    @NotBlank(message = "Trạng thái không được để trống")
    @Pattern(
        regexp = "^(DA_KICH_HOAT|BI_KHOA)$",
        message = "Trạng thái không hợp lệ. Chỉ chấp nhận: DA_KICH_HOAT, BI_KHOA"
    )
    private String trangThai;

    public String getTrangThai() { return trangThai; }
    public void setTrangThai(String trangThai) { this.trangThai = trangThai; }
}