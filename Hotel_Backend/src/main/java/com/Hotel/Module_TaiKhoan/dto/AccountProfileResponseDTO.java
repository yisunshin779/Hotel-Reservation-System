package com.Hotel.Module_TaiKhoan.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.Hotel.Module_TaiKhoan.entity.Account;

public class AccountProfileResponseDTO {
    private UUID id;
    private String hoTen;
    private String email;
    private String soDienThoai;
    private String cccd;
    private String authProvider;
    private String vaiTro;
    private String trangThai;
    private LocalDateTime thoiGianTao;

    public static AccountProfileResponseDTO fromEntity(Account account) {
        AccountProfileResponseDTO dto = new AccountProfileResponseDTO();
        dto.id = account.getId();
        dto.hoTen = account.getHoTen();
        dto.email = account.getEmail();
        dto.soDienThoai = account.getSoDienThoai();
        dto.cccd = account.getCccd();
        dto.authProvider = account.getAuthProvider();
        dto.vaiTro = account.getVaiTro();
        dto.trangThai = account.getTrangThai();
        dto.thoiGianTao = account.getThoiGianTao();
        return dto;
    }

    public UUID getId() { return id; }
    public String getHoTen() { return hoTen; }
    public String getEmail() { return email; }
    public String getSoDienThoai() { return soDienThoai; }
    public String getCccd() { return cccd; }
    public String getAuthProvider() { return authProvider; }
    public String getVaiTro() { return vaiTro; }
    public String getTrangThai() { return trangThai; }
    public LocalDateTime getThoiGianTao() { return thoiGianTao; }
}