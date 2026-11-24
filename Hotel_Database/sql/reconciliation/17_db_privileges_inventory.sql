-- #17 Read-only inventory of who can write reservation and payment state in schema hotel.
-- Run as a role allowed to read the catalog. Nothing here grants or revokes anything.
-- PostgreSQL grants EXECUTE on new functions to PUBLIC by default, so routines such as
-- hotel.confirm_mock_qr are callable by every role that can USE the schema unless revoked.
BEGIN READ ONLY;

-- 1. Roles, login ability and inherited memberships.
SELECT r.rolname, r.rolcanlogin, r.rolsuper, r.rolinherit, r.rolbypassrls,
       array_remove(array_agg(m.rolname ORDER BY m.rolname), NULL) AS member_of
FROM pg_roles r
LEFT JOIN pg_auth_members am ON am.member = r.oid
LEFT JOIN pg_roles m ON m.oid = am.roleid
WHERE r.rolname NOT LIKE 'pg\_%'
GROUP BY r.oid, r.rolname, r.rolcanlogin, r.rolsuper, r.rolinherit, r.rolbypassrls
ORDER BY r.rolname;

-- 2. Schema owner and USAGE/CREATE grants (PUBLIC shown as grantee 0).
SELECT n.nspname, pg_get_userbyid(n.nspowner) AS owner,
       coalesce(nullif(pg_get_userbyid(a.grantee), ''), 'PUBLIC') AS grantee, a.privilege_type
FROM pg_namespace n, aclexplode(coalesce(n.nspacl, acldefault('n', n.nspowner))) a
WHERE n.nspname = 'hotel' ORDER BY 3, 4;

-- 3. Write privileges on the state tables, including grants to PUBLIC.
SELECT c.relname, pg_get_userbyid(c.relowner) AS owner,
       CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END AS grantee, a.privilege_type
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace,
     aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a
WHERE n.nspname = 'hotel' AND c.relkind = 'r'
  AND c.relname IN ('reservations', 'payments', 'payment_intents', 'refunds', 'invoices', 'invoice_adjustments',
                    'booking_confirmation_requests', 'invoice_delivery_requests', 'reservation')
  AND a.privilege_type IN ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')
ORDER BY 1, 3, 4;

-- 4. EXECUTE on state-changing routines, SECURITY DEFINER flag and owner.
SELECT p.proname, pg_get_function_identity_arguments(p.oid) AS args, p.prosecdef AS security_definer,
       pg_get_userbyid(p.proowner) AS owner,
       CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END AS grantee
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace,
     aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
WHERE n.nspname = 'hotel' AND a.privilege_type = 'EXECUTE'
  AND p.proname IN ('confirm_mock_qr', 'make_reservation', 'cancel_reservation', 'expire_holds',
                    'release_payment_selections', 'repair_expired_payment_selections', 'maintenance_block')
ORDER BY 1, 5;

-- 5. Default privileges that will apply to future objects in the schema.
SELECT pg_get_userbyid(d.defaclrole) AS for_role, d.defaclobjtype AS object_type,
       CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END AS grantee, a.privilege_type
FROM pg_default_acl d LEFT JOIN pg_namespace n ON n.oid = d.defaclnamespace,
     aclexplode(d.defaclacl) a
WHERE n.nspname = 'hotel' OR d.defaclnamespace = 0
ORDER BY 1, 2, 3;
COMMIT;

-- Application callers found in the backend (2026-11-23): make_reservation (CanonicalBookingService),
-- cancel_reservation (PaymentRepository.cancelHold), expire_holds (PaymentHoldExpiryJob).
-- confirm_mock_qr has no backend caller; PaymentService writes QR payments itself.
-- Candidate restriction, to test on a private copy with the application role first:
--   REVOKE EXECUTE ON FUNCTION hotel.confirm_mock_qr(bigint, numeric, text) FROM PUBLIC;
