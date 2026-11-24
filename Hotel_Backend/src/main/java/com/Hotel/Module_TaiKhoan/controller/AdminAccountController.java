package com.Hotel.Module_TaiKhoan.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.Hotel.Module_TaiKhoan.dto.AccountProfileResponseDTO;
import com.Hotel.Module_TaiKhoan.dto.UpdateRoleRequestDTO;
import com.Hotel.Module_TaiKhoan.dto.UpdateStatusRequestDTO;
import com.Hotel.Module_TaiKhoan.exception.AccountAccessDeniedException;
import com.Hotel.Module_TaiKhoan.security.JwtTokenProvider;
import com.Hotel.Module_TaiKhoan.service.AccountService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAccountController {

    private final AccountService accountService;

    public AdminAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // 1. Lấy danh sách toàn bộ tài khoản trong hệ thống
    @GetMapping
    public ResponseEntity<?> getAllAccounts() {
        List<AccountProfileResponseDTO> accounts = accountService.getAllAccounts();
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "data", accounts
        ));
    }

    // 2. Phân quyền tài khoản (USER <-> RECEPTIONIST <-> ADMIN)
    @PatchMapping("/{id}/role")
    public ResponseEntity<?> updateAccountRole(@PathVariable UUID id,
                                               @Valid @RequestBody UpdateRoleRequestDTO request,
                                               Authentication authentication) {
        AccountProfileResponseDTO updated =
                accountService.updateAccountRole(id, request.getVaiTro(), session(authentication));
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Cập nhật vai trò tài khoản thành công!",
            "data", updated
        ));
    }

    // 3. Khóa hoặc mở khóa tài khoản
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateAccountStatus(@PathVariable UUID id,
                                                 @Valid @RequestBody UpdateStatusRequestDTO request,
                                                 Authentication authentication) {
        AccountProfileResponseDTO updated =
                accountService.updateAccountStatus(id, request.getTrangThai(), session(authentication));
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Cập nhật trạng thái tài khoản thành công!",
            "data", updated
        ));
    }

    /** The JWT session verified by JwtAuthenticationFilter; the service re-checks it under the write lock. */
    private static JwtTokenProvider.Session session(Authentication authentication) {
        if (authentication != null && authentication.getDetails() instanceof JwtTokenProvider.Session session) {
            return session;
        }
        throw new AccountAccessDeniedException(AccountService.ADMIN_ACCESS_REVOKED,
                "Phiên quản trị không hợp lệ. Vui lòng đăng nhập lại.");
    }
}
