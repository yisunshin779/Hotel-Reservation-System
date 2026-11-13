package com.Hotel.Module_TaiKhoan.repository;

import com.Hotel.Module_TaiKhoan.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {
    // Tự động sinh SQL: SELECT * FROM account WHERE email = ?
    Optional<Account> findByEmail(String email);
    
    // Tự động sinh SQL: SELECT COUNT(*) > 0 FROM account WHERE email = ?
    boolean existsByEmail(String email);
}