-- #16 Read-only inventory of legacy Booking rows (hotel.reservation, JPA) before a cutover.
-- Run on a restored copy only. Nothing here assigns an owner, a branch or a price.
-- Legacy rows carry: id (UUID), ma_loai_phong (string), so_luong_phong, ngay_nhan, ngay_tra, trang_thai, thoi_gian_tao.
-- They do not carry customer, branch, nightly prices or guest count, so none of them can be imported
-- automatically; every class below needs an approved mapping or a manual decision.
BEGIN READ ONLY;

-- 1. Classification with the evidence each row has or lacks.
WITH legacy AS (
  SELECT r.*,
         EXISTS (SELECT 1 FROM hotel.reservations c WHERE c.code = r.id::text) AS already_canonical,
         EXISTS (SELECT 1 FROM hotel.room_type t WHERE t.id = r.ma_loai_phong) AS legacy_type_known
  FROM hotel.reservation r
)
SELECT CASE
         WHEN already_canonical THEN 'A_ALREADY_IN_CANONICAL'
         WHEN trang_thai = 'PENDING_PAYMENT' AND thoi_gian_tao > now() - interval '15 minutes' THEN 'B_ACTIVE_HOLD'
         WHEN trang_thai = 'PENDING_PAYMENT' THEN 'C_EXPIRED_HOLD_NOT_SWEPT'
         WHEN trang_thai = 'CONFIRMED' AND ngay_tra > current_date THEN 'D_FUTURE_OR_CURRENT_CONFIRMED'
         WHEN trang_thai = 'CONFIRMED' THEN 'E_PAST_CONFIRMED'
         ELSE 'F_OTHER_' || trang_thai
       END AS class,
       count(*) AS rows,
       count(*) FILTER (WHERE NOT legacy_type_known) AS unknown_room_type,
       min(ngay_nhan) AS first_check_in, max(ngay_tra) AS last_check_out
FROM legacy GROUP BY 1 ORDER BY 1;

-- 2. Rows that would block or be affected at cutover (holds and future stays), with what is missing.
SELECT r.id, r.trang_thai, r.ma_loai_phong, r.so_luong_phong, r.ngay_nhan, r.ngay_tra, r.thoi_gian_tao,
       'customer, branch, canonical room type, nightly price' AS missing_evidence
FROM hotel.reservation r
WHERE r.trang_thai = 'PENDING_PAYMENT' OR (r.trang_thai = 'CONFIRMED' AND r.ngay_tra > current_date)
ORDER BY r.ngay_nhan, r.id;

-- 3. Legacy room-type codes in use: each needs an explicit mapping to hotel.room_types(id, branch_id).
SELECT r.ma_loai_phong, t.ten_loai_phong, count(*) AS reservations,
       sum(r.so_luong_phong) FILTER (WHERE r.trang_thai = 'CONFIRMED' AND r.ngay_tra > current_date) AS future_rooms
FROM hotel.reservation r LEFT JOIN hotel.room_type t ON t.id = r.ma_loai_phong
GROUP BY 1, 2 ORDER BY 1;

-- 4. Accounts that may own legacy rows: hotel.reservation has no owner column, so ownership needs another source.
SELECT count(*) FILTER (WHERE l.account_id IS NULL AND a.vai_tro = 'ROLE_USER' AND a.trang_thai = 'DA_KICH_HOAT')
         AS active_guests_without_link,
       count(*) FILTER (WHERE l.account_id IS NOT NULL) AS linked_accounts,
       (SELECT count(*) FROM hotel.account_link_reviews WHERE resolved_at IS NULL) AS pending_link_reviews
FROM hotel.account a LEFT JOIN hotel.account_user_links l ON l.account_id = a.id;
COMMIT;
