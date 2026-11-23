-- Existing Google accounts stay unlinked pending verified recovery or linking.
ALTER TABLE hotel.account ADD COLUMN IF NOT EXISTS google_sub varchar(255);
CREATE UNIQUE INDEX IF NOT EXISTS uq_account_google_sub ON hotel.account (google_sub);
