package com.Hotel.Module_TaiKhoan.repository;

import com.Hotel.Module_TaiKhoan.entity.VerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

/**
 * Lock order for token use: read the owner without a lock, lock the account row, then lock the token.
 * Issuing a token locks the account row too, so issue and use are serialized per account.
 */
@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<VerificationToken> findByToken(String token);

    /** Unlocked lookup of the owning account, used to take the account lock before the token lock. */
    @Query("select t.account.id from VerificationToken t where t.token = :token")
    Optional<UUID> findAccountIdByToken(String token);

    /**
     * Deletes every token of {@code purpose} for the account. Pending outbox rows that reference them are
     * removed by the account_email_outbox.token_id ON DELETE CASCADE, so their links are never sent.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from VerificationToken t where t.account.id = :accountId and t.loaiToken = :purpose")
    int deleteByAccountAndPurpose(UUID accountId, String purpose);
}
