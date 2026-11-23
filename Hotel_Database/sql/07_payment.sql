-- UC08 / OC-08.01-03. Apply after 05_checkin_checkout and 06_stay_contracts.
BEGIN;
CREATE TABLE IF NOT EXISTS hotel.payment_intents (
 id uuid PRIMARY KEY,
 reservation_id bigint NOT NULL REFERENCES hotel.reservations(id),
 payment_id bigint NOT NULL UNIQUE REFERENCES hotel.payments(id),
 payment_plan varchar(10) NOT NULL CHECK(payment_plan IN ('FULL','DEPOSIT')),
 state varchar(12) NOT NULL CHECK(state IN ('ACTIVE','CANCELLED','COMPLETED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_active_intent
 ON hotel.payment_intents(reservation_id) WHERE state='ACTIVE';
CREATE INDEX IF NOT EXISTS idx_payment_intent_reservation ON hotel.payment_intents(reservation_id,payment_id DESC);
-- Transactional outbox: a notification worker can consume only committed requests.
-- PENDING means queued, never that an email/SMS has already been sent.
CREATE TABLE IF NOT EXISTS hotel.booking_confirmation_requests (
 reservation_id bigint PRIMARY KEY REFERENCES hotel.reservations(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SENT','FAILED'))
);
COMMIT;
