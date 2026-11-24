-- V13: Account session revocation, Account audit, booking-profile review and e-mail outbox.
-- Requires hotel.account and hotel.verification_token (V9). Independent of V11.

-- #8 Every JWT carries the version current at issue time. Increment to revoke older tokens.
ALTER TABLE hotel.account ADD COLUMN IF NOT EXISTS token_version integer NOT NULL DEFAULT 0;
ALTER TABLE hotel.account DROP CONSTRAINT IF EXISTS ck_account_token_version;
ALTER TABLE hotel.account ADD CONSTRAINT ck_account_token_version CHECK (token_version >= 0);

-- #10 Account identifiers are UUID, so hotel.audit_logs (bigint FK to users) cannot hold them.
CREATE TABLE IF NOT EXISTS hotel.account_audit_logs (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 actor_account_id uuid REFERENCES hotel.account(id),
 target_account_id uuid NOT NULL REFERENCES hotel.account(id),
 action varchar(40) NOT NULL,
 before_data jsonb,
 after_data jsonb,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS idx_account_audit_target ON hotel.account_audit_logs(target_account_id, recorded_at DESC);

-- #5 Verified accounts whose e-mail already belongs to hotel.users wait for an explicit link.
CREATE TABLE IF NOT EXISTS hotel.account_link_reviews (
 account_id uuid PRIMARY KEY REFERENCES hotel.account(id),
 reason varchar(40) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 resolved_at timestamptz
);

-- #13 Rows hold identifiers only. Recipient, link and token are resolved at send time.
CREATE TABLE IF NOT EXISTS hotel.account_email_outbox (
 id uuid PRIMARY KEY,
 account_id uuid NOT NULL REFERENCES hotel.account(id),
 purpose varchar(30) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL','RESET_PASSWORD','PASSWORD_CHANGED')),
 token_id uuid REFERENCES hotel.verification_token(id) ON DELETE CASCADE,
 status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
 attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 locked_until timestamptz,
 last_error varchar(60),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 sent_at timestamptz,
 CHECK ((purpose = 'PASSWORD_CHANGED') = (token_id IS NULL))
);
CREATE INDEX IF NOT EXISTS idx_account_email_due ON hotel.account_email_outbox(next_attempt_at) WHERE status='PENDING';
