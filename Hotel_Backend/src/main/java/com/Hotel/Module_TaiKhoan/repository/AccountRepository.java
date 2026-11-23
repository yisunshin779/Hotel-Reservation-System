package com.Hotel.Module_TaiKhoan.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.Hotel.Module_TaiKhoan.entity.Account;

import jakarta.persistence.LockModeType;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {
    // Tự động sinh SQL: SELECT * FROM account WHERE email = ?
    Optional<Account> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> lockById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.email = :email")
    Optional<Account> lockByEmail(String email);

    Optional<Account> findByGoogleSubject(String googleSubject);

    boolean existsByEmailIgnoreCase(String email);
    
    // Tự động sinh SQL: SELECT COUNT(*) > 0 FROM account WHERE email = ?
    boolean existsByEmail(String email);

    boolean existsByCccdAndIdNot(String cccd, UUID id);
}
