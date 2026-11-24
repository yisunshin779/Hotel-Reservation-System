package com.Hotel.Module_TaiKhoan.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.Hotel.Module_TaiKhoan.dto.AccountProfileResponseDTO;
import com.Hotel.Module_TaiKhoan.dto.UpdateProfileRequestDTO;
import com.Hotel.Module_TaiKhoan.entity.Account;
import com.Hotel.Module_TaiKhoan.exception.AccountAccessDeniedException;
import com.Hotel.Module_TaiKhoan.exception.AccountConflictException;
import com.Hotel.Module_TaiKhoan.repository.AccountRepository;
import com.Hotel.Module_TaiKhoan.security.JwtTokenProvider;
import com.Hotel.config.SchemaCapabilities;

@Service
public class AccountService {
    static final String ADMIN = "ROLE_ADMIN";
    static final String ACTIVE = "DA_KICH_HOAT";
    static final String LOCKED = "BI_KHOA";
    static final String PENDING = "CHO_XAC_THUC";
    /** 403 code: the caller lost Admin rights, was locked, or its session was revoked before the write. */
    public static final String ADMIN_ACCESS_REVOKED = "ADMIN_ACCESS_REVOKED";
    /** Serializes every change that can remove an active Admin (pg_advisory_xact_lock key). */
    private static final long ADMIN_CHANGE_LOCK = 7_201_000_001L;

    private final AccountRepository accountRepository;
    private final JdbcTemplate jdbc;
    private final SchemaCapabilities schema;
    private final AccountEmailOutbox outbox;
    private final Clock clock;

    public AccountService(AccountRepository accountRepository, JdbcTemplate jdbc, SchemaCapabilities schema,
                          AccountEmailOutbox outbox, Clock clock) {
        this.accountRepository = accountRepository;
        this.jdbc = jdbc;
        this.schema = schema;
        this.outbox = outbox;
        this.clock = clock;
    }

    public AccountProfileResponseDTO getCurrentProfile(String email) {
        Account account = accountRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản."));
        return AccountProfileResponseDTO.fromEntity(account);
    }

    /**
     * UC04 / OC-04.01. A linked account also updates the linked hotel.users and customers rows in the
     * same transaction, so booking and stay modules read the same contact data.
     */
    @Transactional
    public AccountProfileResponseDTO updateCurrentProfile(String email, UpdateProfileRequestDTO request) {
        Account account = accountRepository.lockByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản."));

        String name = request.getHoTen().trim();
        account.setHoTen(name);

        String phone = (request.getSoDienThoai() == null || request.getSoDienThoai().isBlank())
                ? null : request.getSoDienThoai().trim();
        account.setSoDienThoai(phone);

        String cccd = (request.getCccd() == null || request.getCccd().isBlank())
                ? null : request.getCccd().trim();

        if (cccd != null && accountRepository.existsByCccdAndIdNot(cccd, account.getId())) {
            throw new IllegalArgumentException("Số CCCD này đã được sử dụng bởi một tài khoản khác.");
        }
        account.setCccd(cccd);

        Account saved = accountRepository.saveAndFlush(account);
        Long linkedUser = linkedUser(account.getId());
        if (linkedUser != null) {
            jdbc.update("UPDATE hotel.users SET full_name = ?, phone = ? WHERE id = ?", name, phone, linkedUser);
            jdbc.update("""
                    UPDATE hotel.customers SET full_name = ?, phone = ?
                    WHERE user_id = ? AND deleted_at IS NULL
                    """, name, phone, linkedUser);
        }
        return AccountProfileResponseDTO.fromEntity(saved);
    }

    public List<AccountProfileResponseDTO> getAllAccounts() {
        return accountRepository.findAll()
                .stream()
                .map(AccountProfileResponseDTO::fromEntity)
                .toList();
    }

    /**
     * OC-27.02 role change. For an account linked to hotel.users, business roles and branch scope are
     * managed in hotel.users; changing only the Account role would make the two sources disagree.
     */
    @Transactional
    public AccountProfileResponseDTO updateAccountRole(UUID targetAccountId, String newRole, JwtTokenProvider.Session actor) {
        lockAdminChanges();
        UUID actorId = requireActingAdmin(actor);
        Account target = accountRepository.lockById(targetAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản với ID này."));

        if (target.getId().equals(actorId)) {
            throw new IllegalStateException("Bạn không thể tự thay đổi vai trò của chính mình.");
        }
        if (newRole.equals(target.getVaiTro())) {
            return AccountProfileResponseDTO.fromEntity(target);
        }
        if (linkedUser(target.getId()) != null) {
            throw new AccountConflictException("ROLE_MANAGED_BY_HOTEL_USERS",
                    "Vai trò nghiệp vụ của tài khoản đã liên kết được quản lý trong hotel.users.");
        }
        if (ADMIN.equals(target.getVaiTro()) && ACTIVE.equals(target.getTrangThai())) {
            requireAnotherActiveAdmin(target.getId());
        }

        String before = target.getVaiTro();
        target.setVaiTro(newRole);
        target.revokeTokens();
        Account saved = accountRepository.saveAndFlush(target);
        audit(actorId, target.getId(), "ACCOUNT_ROLE_CHANGED", "vai_tro", before, newRole);
        return AccountProfileResponseDTO.fromEntity(saved);
    }

    /**
     * OC-27.02 lock and unlock. Both revoke earlier tokens; unlock restores the pre-lock status. An account that
     * returns to CHO_XAC_THUC gets its verification e-mail back while its token is valid (see
     * {@link #resumeVerificationEmail}); it stays pending until the token is used.
     */
    @Transactional
    public AccountProfileResponseDTO updateAccountStatus(UUID targetAccountId, String newStatus, JwtTokenProvider.Session actor) {
        lockAdminChanges();
        UUID actorId = requireActingAdmin(actor);
        Account target = accountRepository.lockById(targetAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản với ID này."));

        if (target.getId().equals(actorId)) {
            throw new IllegalStateException("Bạn không thể tự khóa hoặc thay đổi trạng thái của chính mình.");
        }
        String before = target.getTrangThai();
        boolean unlock = ACTIVE.equals(newStatus);
        if (unlock ? !LOCKED.equals(before) : LOCKED.equals(before)) {
            if (newStatus.equals(before)) {
                return AccountProfileResponseDTO.fromEntity(target);
            }
            throw new AccountConflictException("INVALID_ACCOUNT_STATUS_CHANGE",
                    "Chỉ có thể mở khóa tài khoản đang bị khóa.");
        }
        if (!unlock && ADMIN.equals(target.getVaiTro()) && ACTIVE.equals(before)) {
            requireAnotherActiveAdmin(target.getId());
        }

        String after = unlock ? statusBeforeLastLock(target.getId()) : LOCKED;
        target.setTrangThai(after);
        if (unlock) {
            // Admin kích hoạt lại tài khoản -> xóa luôn bộ đếm khóa Brute-force (nếu có)
            target.setSoLanDangNhapSai(0);
            target.setThoiGianKhoa(null);
        }
        target.revokeTokens();
        Account saved = accountRepository.saveAndFlush(target);
        if (unlock && PENDING.equals(after)) {
            resumeVerificationEmail(target.getId());
        }
        audit(actorId, target.getId(), unlock ? "ACCOUNT_UNLOCKED" : "ACCOUNT_LOCKED", "trang_thai", before, after);
        return AccountProfileResponseDTO.fromEntity(saved);
    }

    /**
     * While an account is BI_KHOA, the worker ends its verification e-mail FAILED/ACCOUNT_LOCKED instead of
     * sending it. On unlock to CHO_XAC_THUC, the newest VERIFY_EMAIL token is reused when it is still valid
     * (same deadline rule as the verification API): if none of its outbox rows is PENDING or SENT, a new row is
     * queued. Earlier rows are never revived, so their attempt numbers cannot become a valid claim again.
     * An expired token queues nothing; issuing a new one is OC-01.03 (backlog).
     *
     * <p>Lock order: account (already locked FOR UPDATE by the caller), then the token, then its outbox rows.
     * The worker share-locks the account before storing ACCOUNT_LOCKED, so whichever commits first is seen
     * by the other.
     */
    private void resumeVerificationEmail(UUID account) {
        var token = jdbc.query("""
                SELECT id, ngay_het_han FROM hotel.verification_token
                WHERE account_id = ? AND loai_token = 'VERIFY_EMAIL'
                ORDER BY ngay_het_han DESC, id LIMIT 1 FOR UPDATE
                """, (rs, row) -> java.util.Map.entry((UUID) rs.getObject("id"),
                        rs.getTimestamp("ngay_het_han").toLocalDateTime()), account)
                .stream().findFirst().orElse(null);
        if (token == null
                || AuthService.isExpired(token.getValue(), LocalDateTime.now(clock.withZone(ZoneId.systemDefault())))) {
            return;
        }
        List<String> rows = jdbc.queryForList("""
                SELECT status FROM hotel.account_email_outbox WHERE token_id = ? FOR UPDATE
                """, String.class, token.getKey());
        if (!rows.contains("PENDING") && !rows.contains("SENT")) {
            outbox.enqueue(account, AccountEmailOutbox.Purpose.VERIFY_EMAIL, token.getKey());
        }
    }

    private void lockAdminChanges() {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", ADMIN_CHANGE_LOCK);
    }

    /**
     * Re-checks the caller after the advisory lock: the request-time filter check can be stale when the
     * change waited for another Admin change. The caller's row is read FOR SHARE, so a concurrent password
     * reset, lock or role change of the caller either commits first (and is seen here) or waits for this
     * transaction. Lock order on this path: advisory lock, caller row (share), target row (update).
     */
    private UUID requireActingAdmin(JwtTokenProvider.Session actor) {
        if (actor == null || actor.accountId() == null) {
            throw revoked();
        }
        var current = jdbc.query("""
                SELECT vai_tro, trang_thai, token_version FROM hotel.account WHERE id = ? FOR SHARE
                """, (rs, row) -> ADMIN.equals(rs.getString("vai_tro")) && ACTIVE.equals(rs.getString("trang_thai"))
                        && actor.matches(rs.getInt("token_version")), actor.accountId());
        if (current.isEmpty() || !current.get(0)) {
            throw revoked();
        }
        return actor.accountId();
    }

    private static AccountAccessDeniedException revoked() {
        return new AccountAccessDeniedException(ADMIN_ACCESS_REVOKED,
                "Quyền quản trị hoặc phiên đăng nhập của bạn không còn hiệu lực. Vui lòng đăng nhập lại.");
    }

    private void requireAnotherActiveAdmin(UUID excluded) {
        Integer others = jdbc.queryForObject("""
                SELECT count(*) FROM hotel.account
                WHERE vai_tro = 'ROLE_ADMIN' AND trang_thai = 'DA_KICH_HOAT' AND id <> ?
                """, Integer.class, excluded);
        if (others == null || others == 0) {
            throw new AccountConflictException("LAST_ACTIVE_ADMIN",
                    "Không thể khóa hoặc hạ quyền Admin đang hoạt động cuối cùng.");
        }
    }

    /** A pending account that was locked returns to pending, so unlocking never skips e-mail verification. */
    private String statusBeforeLastLock(UUID account) {
        return jdbc.query("""
                SELECT before_data->>'trang_thai' FROM hotel.account_audit_logs
                WHERE target_account_id = ? AND action = 'ACCOUNT_LOCKED'
                ORDER BY recorded_at DESC, id DESC LIMIT 1
                """, (rs, row) -> rs.getString(1), account).stream()
                .filter(PENDING::equals).findFirst().orElse(ACTIVE);
    }

    private void audit(UUID actor, UUID target, String action, String field, String before, String after) {
        jdbc.update("""
                INSERT INTO hotel.account_audit_logs(actor_account_id, target_account_id, action, before_data, after_data)
                VALUES (?, ?, ?, jsonb_build_object(?::text, ?::text), jsonb_build_object(?::text, ?::text))
                """, actor, target, action, field, before, field, after);
    }

    private Long linkedUser(UUID account) {
        if (!schema.hasAccountLinks()) {
            return null;
        }
        return jdbc.query("SELECT user_id FROM hotel.account_user_links WHERE account_id = ?",
                (rs, row) -> rs.getLong(1), account).stream().findFirst().orElse(null);
    }
}
