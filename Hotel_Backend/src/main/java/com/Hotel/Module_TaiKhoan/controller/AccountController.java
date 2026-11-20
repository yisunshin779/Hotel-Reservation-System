package com.Hotel.Module_TaiKhoan.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.Hotel.Module_TaiKhoan.dto.AccountProfileResponseDTO;
import com.Hotel.Module_TaiKhoan.dto.UpdateProfileRequestDTO;
import com.Hotel.Module_TaiKhoan.service.AccountService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/me")
    public ResponseEntity<?> getMyProfile(Authentication authentication) {
        String email = authentication.getName();
        AccountProfileResponseDTO profile = accountService.getCurrentProfile(email);
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "data", profile
        ));
    }

    @PutMapping("/me")
    public ResponseEntity<?> updateMyProfile(Authentication authentication,
                                             @Valid @RequestBody UpdateProfileRequestDTO request) {
        String email = authentication.getName();
        AccountProfileResponseDTO updated = accountService.updateCurrentProfile(email, request);
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Cập nhật hồ sơ thành công!",
            "data", updated
        ));
    }
}