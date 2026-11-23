package com.Hotel.Module_TaiKhoan.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.Hotel.Module_TaiKhoan.dto.AccountProfileResponseDTO;
import com.Hotel.Module_TaiKhoan.dto.UpdateProfileRequestDTO;
import com.Hotel.Module_TaiKhoan.entity.Account;
import com.Hotel.Module_TaiKhoan.repository.AccountRepository;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public AccountProfileResponseDTO getCurrentProfile(String email) {
        Account account = accountRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản."));
        return AccountProfileResponseDTO.fromEntity(account);
    }

    @Transactional
    public AccountProfileResponseDTO updateCurrentProfile(String email, UpdateProfileRequestDTO request) {
        Account account = accountRepository.lockByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản."));

        account.setHoTen(request.getHoTen().trim());

        String phone = (request.getSoDienThoai() == null || request.getSoDienThoai().isBlank())
                ? null : request.getSoDienThoai().trim();
        account.setSoDienThoai(phone);

        String cccd = (request.getCccd() == null || request.getCccd().isBlank())
                ? null : request.getCccd().trim();

        if (cccd != null && accountRepository.existsByCccdAndIdNot(cccd, account.getId())) {
            throw new IllegalArgumentException("Số CCCD này đã được sử dụng bởi một tài khoản khác.");
        }
        account.setCccd(cccd);

        Account saved = accountRepository.save(account);
        return AccountProfileResponseDTO.fromEntity(saved);
    }

    public List<AccountProfileResponseDTO> getAllAccounts() {
        return accountRepository.findAll()
                .stream()
                .map(AccountProfileResponseDTO::fromEntity)
                .toList();
    }

    @Transactional
    public AccountProfileResponseDTO updateAccountRole(UUID targetAccountId, String newRole, String adminEmail) {
        Account target = accountRepository.lockById(targetAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản với ID này."));

        if (target.getEmail().equalsIgnoreCase(adminEmail)) {
            throw new IllegalStateException("Bạn không thể tự thay đổi vai trò của chính mình.");
        }

        target.setVaiTro(newRole);
        Account saved = accountRepository.save(target);
        return AccountProfileResponseDTO.fromEntity(saved);
    }

    @Transactional
    public AccountProfileResponseDTO updateAccountStatus(UUID targetAccountId, String newStatus, String adminEmail) {
        Account target = accountRepository.lockById(targetAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản với ID này."));

        if (target.getEmail().equalsIgnoreCase(adminEmail)) {
            throw new IllegalStateException("Bạn không thể tự khóa hoặc thay đổi trạng thái của chính mình.");
        }

        target.setTrangThai(newStatus);

        // Nếu Admin kích hoạt lại tài khoản -> xóa luôn bộ đếm khóa Brute-force (nếu có)
        if ("DA_KICH_HOAT".equals(newStatus)) {
            target.setSoLanDangNhapSai(0);
            target.setThoiGianKhoa(null);
        }

        Account saved = accountRepository.save(target);
        return AccountProfileResponseDTO.fromEntity(saved);
    }
}
