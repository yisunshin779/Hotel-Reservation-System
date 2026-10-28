-- Report contracts OC-16.01/02, OC-17.01, OC-19.01/02/03 and OC-11.02.
-- Apply after 05_checkin_checkout.sql. Additive, repeatable migration.
BEGIN;
SET LOCAL search_path=hotel,public;

CREATE TABLE IF NOT EXISTS stay_guests (
 reservation_id bigint PRIMARY KEY REFERENCES reservations(id),
 full_name varchar(160) NOT NULL, identity_document text NOT NULL,
 customer_id bigint REFERENCES customers(id), verified_by bigint NOT NULL REFERENCES users(id),
 verified_at timestamptz NOT NULL
);

-- The booked type and the physical type may differ after a free upgrade / UC17.
-- Keep the physical room/type FK; reference the booked detail independently.
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='hotel.room_assignments'::regclass
   AND confrelid='hotel.reservation_details'::regclass AND contype='f' AND array_length(conkey,1)=2
 LOOP EXECUTE format('ALTER TABLE hotel.room_assignments DROP CONSTRAINT %I',c.conname); END LOOP;
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='hotel.room_assignments'::regclass AND conname='assignment_booked_detail_fk') THEN
  ALTER TABLE room_assignments ADD CONSTRAINT assignment_booked_detail_fk
   FOREIGN KEY(reservation_detail_id) REFERENCES reservation_details(id);
 END IF;
END $$;

-- Optional explicit order for free upgrades; NULL means no configured upgrade path.
ALTER TABLE room_types ADD COLUMN IF NOT EXISTS upgrade_rank integer CHECK(upgrade_rank>=0);
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS view_direction varchar(80);

CREATE TABLE IF NOT EXISTS assignment_nightly_rates (
 assignment_id bigint NOT NULL REFERENCES room_assignments(id), stay_date date NOT NULL,
 nightly_price numeric(14,2) NOT NULL CHECK(nightly_price>=0), PRIMARY KEY(assignment_id,stay_date)
);
INSERT INTO assignment_nightly_rates(assignment_id,stay_date,nightly_price)
 SELECT a.id,n.stay_date,n.nightly_price FROM room_assignments a
 JOIN reservation_nights n ON n.reservation_detail_id=a.reservation_detail_id
 WHERE NOT EXISTS(SELECT 1 FROM assignment_nightly_rates x WHERE x.assignment_id=a.id)
 ON CONFLICT DO NOTHING;

-- Signed changes to the booked type inventory; prices in reservation_nights remain frozen.
CREATE TABLE IF NOT EXISTS stay_inventory_movements (
 reservation_id bigint NOT NULL REFERENCES reservations(id), room_type_id bigint NOT NULL REFERENCES room_types(id),
 stay_date date NOT NULL, quantity_delta integer NOT NULL,
 PRIMARY KEY(reservation_id,room_type_id,stay_date)
);
CREATE OR REPLACE FUNCTION available_count(p_room_type bigint,p_day date) RETURNS integer
LANGUAGE sql STABLE AS $$
 SELECT (SELECT count(*)::integer FROM hotel.rooms WHERE room_type_id=p_room_type AND active AND deleted_at IS NULL)
 - (SELECT count(*)::integer FROM hotel.room_maintenance m JOIN hotel.rooms r ON r.id=m.room_id
    WHERE r.room_type_id=p_room_type AND m.active AND m.starts_on<=p_day AND m.ends_on>p_day)
 - coalesce((SELECT sum(n.quantity)::integer FROM hotel.reservation_nights n
    JOIN hotel.reservation_details d ON d.id=n.reservation_detail_id JOIN hotel.reservations b ON b.id=d.reservation_id
    WHERE n.room_type_id=p_room_type AND n.stay_date=p_day AND
      (b.status IN ('CONFIRMED','CHECKED_IN') OR (b.status='PENDING_PAYMENT' AND b.hold_expires_at>now()))),0)
 - coalesce((SELECT sum(m.quantity_delta)::integer FROM hotel.stay_inventory_movements m
    JOIN hotel.reservations b ON b.id=m.reservation_id WHERE m.room_type_id=p_room_type AND m.stay_date=p_day
      AND b.status IN ('CONFIRMED','CHECKED_IN')),0);
$$;

-- Rates are configurable, never assumed to be 8%/10%. Zero preserves the demo policy.
-- A branch-wide exclusive tax on the discounted folio is the existing SQL contract.
ALTER TABLE branches ADD COLUMN IF NOT EXISTS tax_rate numeric(7,6) NOT NULL DEFAULT 0 CHECK(tax_rate BETWEEN 0 AND 1);
-- NULL: policy not supplied; no automatic late charge. A configured flat amount applies once
-- after the scheduled checkout date/time in the branch timezone (not on an early departure).
ALTER TABLE branches ADD COLUMN IF NOT EXISTS late_checkout_fee numeric(14,2) CHECK(late_checkout_fee>=0);
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS stay_tax_rate numeric(7,6) CHECK(stay_tax_rate BETWEEN 0 AND 1);
UPDATE reservations r SET stay_tax_rate=b.tax_rate FROM branches b
 WHERE b.id=r.branch_id AND r.status='CHECKED_IN' AND r.stay_tax_rate IS NULL;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS tax_rate numeric(7,6) NOT NULL DEFAULT 0 CHECK(tax_rate BETWEEN 0 AND 1);
ALTER TABLE refunds ADD COLUMN IF NOT EXISTS recorded_by bigint REFERENCES users(id);

CREATE TABLE IF NOT EXISTS invoice_delivery_requests (
 invoice_id bigint PRIMARY KEY REFERENCES invoices(id), requested_at timestamptz NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SENT','FAILED'))
);

-- Cross-module refund safety: the two original independent FKs are insufficient.
CREATE OR REPLACE FUNCTION guard_refund_payment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p hotel.payments%ROWTYPE; allocated numeric;
BEGIN
 SELECT * INTO p FROM hotel.payments WHERE id=NEW.payment_id;
 IF p.id IS NULL OR p.reservation_id<>NEW.reservation_id OR p.status<>'PAID' THEN
  RAISE EXCEPTION 'Refund must reference a paid payment of this reservation' USING ERRCODE='23514';
 END IF;
 SELECT coalesce(sum(amount),0) INTO allocated FROM hotel.refunds
  WHERE payment_id=NEW.payment_id AND status IN ('PENDING','DONE') AND id<>NEW.id;
 IF NEW.status IN ('PENDING','DONE') AND allocated+NEW.amount>p.amount THEN
  RAISE EXCEPTION 'Refund exceeds payment capacity' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND (OLD.payment_id<>NEW.payment_id OR OLD.amount<>NEW.amount OR OLD.idempotency_key<>NEW.idempotency_key
   OR (OLD.status='DONE' AND NEW IS DISTINCT FROM OLD)) THEN
  RAISE EXCEPTION 'Refund obligation / completed refund is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
-- Alphabetical trigger order: guard_checkout_refunds acquires reservation lock first.
DROP TRIGGER IF EXISTS guard_refund_payment ON refunds;
CREATE TRIGGER guard_refund_payment BEFORE INSERT OR UPDATE ON refunds FOR EACH ROW EXECUTE FUNCTION guard_refund_payment();
COMMIT;
