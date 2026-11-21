package com.Hotel.Module_TaiKhoan.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.Hotel.Module_TaiKhoan.entity.Account;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {
    // Tự động sinh SQL: SELECT * FROM account WHERE email = ?
    Optional<Account> findByEmail(String email);
    
    // Tự động sinh SQL: SELECT COUNT(*) > 0 FROM account WHERE email = ?
    boolean existsByEmail(String email);

    boolean existsByCccdAndIdNot(String cccd, UUID id);
}