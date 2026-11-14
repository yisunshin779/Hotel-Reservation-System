package com.Hotel.Module_TaiKhoan.dto;

public class AuthResponseDTO {
    private String token;
    private String type = "Bearer";
    private String email;
    private String vaiTro;

    public AuthResponseDTO(String token, String email, String vaiTro) {
        this.token = token;
        this.email = email;
        this.vaiTro = vaiTro;
    }

    // Getters
    public String getToken() { return token; }
    public String getType() { return type; }
    public String getEmail() { return email; }
    public String getVaiTro() { return vaiTro; }
}