-- V12: payment selections follow reservation cancellation; delivery outboxes gain retry state.
-- Requires the operational schema through 08 (payment_intents, booking_confirmation_requests,
-- invoice_delivery_requests). It does not touch hotel.account, so it can also be applied to
-- databases built directly from Hotel_Database/sql.

-- #1 A cancelled reservation must not keep an ACTIVE payment selection or a PENDING QR payment.
CREATE OR REPLACE FUNCTION hotel.release_payment_selections(p_reservation bigint) RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE released integer;
BEGIN
 WITH cancelled AS (
   UPDATE hotel.payment_intents SET state='CANCELLED'
   WHERE reservation_id=p_reservation AND state='ACTIVE'
   RETURNING payment_id
 )
 UPDATE hotel.payments p SET status='FAILED'
 FROM cancelled c WHERE p.id=c.payment_id AND p.status='PENDING';
 GET DIAGNOSTICS released = ROW_COUNT;
 RETURN released;
END $$;

-- Same body as V8 plus the release step. The caller-visible contract is unchanged.
CREATE OR REPLACE FUNCTION hotel.cancel_reservation(p_reservation bigint,p_reason text DEFAULT NULL) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE b hotel.reservations%ROWTYPE;
BEGIN
 SELECT * INTO b FROM hotel.reservations WHERE id=p_reservation FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Reservation missing'; END IF;
 IF b.status='CANCELLED' THEN RETURN; END IF;
 IF b.status NOT IN ('PENDING_PAYMENT','CONFIRMED') THEN RAISE EXCEPTION 'Reservation cannot be cancelled'; END IF;
 UPDATE hotel.reservations SET status='CANCELLED',updated_at=now(),version=version+1 WHERE id=p_reservation;
 UPDATE hotel.promotion_usages SET reversed_at=now() WHERE reservation_id=p_reservation AND reversed_at IS NULL;
 PERFORM hotel.release_payment_selections(p_reservation);
 INSERT INTO hotel.audit_logs(action,entity_name,entity_id,after_data) VALUES('CANCEL_RESERVATION','reservations',p_reservation,jsonb_build_object('reason',p_reason));
 -- Cancellation refunds are not automatic: create via separate approved refund operation.
END $$;

-- Rows left behind by expire_holds before V12. Each candidate is recorded before any change.
CREATE TABLE IF NOT EXISTS hotel.payment_reconciliation_items (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 intent_id uuid NOT NULL UNIQUE REFERENCES hotel.payment_intents(id),
 reservation_id bigint NOT NULL REFERENCES hotel.reservations(id),
 payment_id bigint NOT NULL REFERENCES hotel.payments(id),
 outcome varchar(20) NOT NULL CHECK (outcome IN ('RELEASED','NEEDS_REVIEW')),
 reason varchar(60) NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 resolved_at timestamptz
);

-- Releases only an ACTIVE QR selection whose PENDING payment belongs to a reservation that is
-- CANCELLED, whose hold had expired, and that has no PAID payment. Anything else is listed
-- for manual reconciliation and left unchanged. Safe to run repeatedly.
CREATE OR REPLACE FUNCTION hotel.repair_expired_payment_selections() RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE r record; released integer:=0;
BEGIN
 FOR r IN
   SELECT res.id AS reservation_id, i.id AS intent_id, p.id AS payment_id,
          (res.hold_expires_at IS NOT NULL AND res.hold_expires_at<=now()) AS hold_expired,
          EXISTS(SELECT 1 FROM hotel.payments paid WHERE paid.reservation_id=res.id AND paid.status='PAID') AS has_paid
   FROM hotel.reservations res
   JOIN hotel.payment_intents i ON i.reservation_id=res.id AND i.state='ACTIVE'
   JOIN hotel.payments p ON p.id=i.payment_id
   WHERE res.status='CANCELLED'
   ORDER BY res.id
   FOR UPDATE OF res
 LOOP
   IF r.hold_expired AND NOT r.has_paid THEN
     INSERT INTO hotel.payment_reconciliation_items(intent_id,reservation_id,payment_id,outcome,reason)
     VALUES(r.intent_id,r.reservation_id,r.payment_id,'RELEASED','EXPIRED_HOLD_ACTIVE_SELECTION')
     ON CONFLICT (intent_id) DO UPDATE SET outcome='RELEASED',reason=EXCLUDED.reason,resolved_at=clock_timestamp();
     UPDATE hotel.payment_intents SET state='CANCELLED' WHERE id=r.intent_id AND state='ACTIVE';
     UPDATE hotel.payments SET status='FAILED' WHERE id=r.payment_id AND status='PENDING';
     released:=released+1;
   ELSE
     INSERT INTO hotel.payment_reconciliation_items(intent_id,reservation_id,payment_id,outcome,reason)
     VALUES(r.intent_id,r.reservation_id,r.payment_id,'NEEDS_REVIEW',
            CASE WHEN r.has_paid THEN 'CANCELLED_WITH_PAID_PAYMENT' ELSE 'NO_EXPIRED_HOLD_EVIDENCE' END)
     ON CONFLICT (intent_id) DO NOTHING;
   END IF;
 END LOOP;
 RETURN released;
END $$;

SELECT hotel.repair_expired_payment_selections();

-- #14 Delivery outboxes: claim lease, attempts and terminal state for a retrying worker.
ALTER TABLE hotel.booking_confirmation_requests
 ADD COLUMN IF NOT EXISTS attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0),
 ADD COLUMN IF NOT EXISTS next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 ADD COLUMN IF NOT EXISTS locked_until timestamptz,
 ADD COLUMN IF NOT EXISTS last_error varchar(60),
 ADD COLUMN IF NOT EXISTS sent_at timestamptz;
CREATE INDEX IF NOT EXISTS idx_booking_confirmation_due
 ON hotel.booking_confirmation_requests(next_attempt_at) WHERE status='PENDING';

ALTER TABLE hotel.invoice_delivery_requests
 ADD COLUMN IF NOT EXISTS attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0),
 ADD COLUMN IF NOT EXISTS next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 ADD COLUMN IF NOT EXISTS locked_until timestamptz,
 ADD COLUMN IF NOT EXISTS last_error varchar(60),
 ADD COLUMN IF NOT EXISTS sent_at timestamptz;
CREATE INDEX IF NOT EXISTS idx_invoice_delivery_due
 ON hotel.invoice_delivery_requests(next_attempt_at) WHERE status='PENDING';
