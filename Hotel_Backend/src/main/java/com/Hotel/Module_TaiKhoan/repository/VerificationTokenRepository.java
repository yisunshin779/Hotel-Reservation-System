package com.Hotel.Module_TaiKhoan.repository;

import com.Hotel.Module_TaiKhoan.entity.VerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<VerificationToken> findByToken(String token);
}
