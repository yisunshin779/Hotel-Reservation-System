-- UC19 / OC-19.03: Admin issues a separate adjustment with a mandatory reason.
-- Apply after 06_stay_contracts.sql (or 07_payment.sql when Payment is enabled).
-- Original invoices, lines, payments, refunds and reservation state remain immutable.
BEGIN;
SET LOCAL search_path=hotel,public;

CREATE TABLE IF NOT EXISTS invoice_adjustments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 invoice_id bigint NOT NULL REFERENCES invoices(id),
 reservation_id bigint NOT NULL REFERENCES reservations(id),
 adjustment_number varchar(45) NOT NULL UNIQUE,
 idempotency_key varchar(100) NOT NULL UNIQUE CHECK(idempotency_key ~ '^[A-Za-z0-9:_-]{8,100}$'),
 reason varchar(1000) NOT NULL CHECK(length(btrim(reason))>0),
 created_by bigint NOT NULL REFERENCES users(id),
 issued_at timestamptz NOT NULL,
 lines jsonb NOT NULL CHECK(jsonb_typeof(lines)='array'),
 total numeric(14,2) NOT NULL,
 adjusted_total numeric(14,2) NOT NULL CHECK(adjusted_total>=0)
);
CREATE INDEX IF NOT EXISTS idx_invoice_adjustments_invoice ON invoice_adjustments(invoice_id,id);

CREATE OR REPLACE FUNCTION hotel.guard_invoice_adjustment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original hotel.invoices%ROWTYPE; rid bigint; state hotel.reservation_status;
        line jsonb; amount numeric; delta numeric:=0; previous numeric;
BEGIN
 IF TG_OP<>'INSERT' THEN
  RAISE EXCEPTION 'Issued invoice adjustments are immutable' USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM hotel.users u JOIN hotel.roles r ON r.id=u.role_id
   WHERE u.id=NEW.created_by AND r.code='ADMIN' AND u.active AND u.deleted_at IS NULL
     AND (u.locked_until IS NULL OR u.locked_until<=clock_timestamp())) THEN
  RAISE EXCEPTION 'Only an active Admin may issue an adjustment' USING ERRCODE='23514';
 END IF;
 SELECT reservation_id INTO rid FROM hotel.invoices WHERE id=NEW.invoice_id;
 -- Same reservation lock as checkout; serializes cumulative balance validation.
 SELECT status INTO state FROM hotel.reservations WHERE id=rid FOR UPDATE;
 SELECT * INTO original FROM hotel.invoices WHERE id=NEW.invoice_id;
 IF original.id IS NULL OR original.status<>'CLOSED' OR state<>'CHECKED_OUT' THEN
  RAISE EXCEPTION 'Adjustment requires a closed invoice and checked-out stay' USING ERRCODE='23514';
 END IF;
 NEW.reservation_id:=rid;
 IF jsonb_typeof(NEW.lines) IS DISTINCT FROM 'array' THEN
  RAISE EXCEPTION 'Adjustment lines must be an array' USING ERRCODE='23514';
 END IF;
 IF jsonb_array_length(NEW.lines) NOT BETWEEN 1 AND 100 THEN
  RAISE EXCEPTION 'Adjustment requires 1 to 100 lines' USING ERRCODE='23514';
 END IF;
 FOR line IN SELECT value FROM jsonb_array_elements(NEW.lines) LOOP
  IF jsonb_typeof(line) IS DISTINCT FROM 'object'
    OR coalesce(line->>'type','') NOT IN ('ROOM','SERVICE','EXTRA','DISCOUNT','TAX')
    OR jsonb_typeof(line->'description') IS DISTINCT FROM 'string'
    OR coalesce(length(btrim(line->>'description')),0) NOT BETWEEN 1 AND 500
    OR jsonb_typeof(line->'amount') IS DISTINCT FROM 'number' THEN
   RAISE EXCEPTION 'Invalid adjustment line' USING ERRCODE='23514';
  END IF;
  amount:=(line->>'amount')::numeric;
  IF amount=0 OR abs(amount)>999999999999.99 OR amount<>round(amount,2) THEN
   RAISE EXCEPTION 'Invalid adjustment amount' USING ERRCODE='23514';
  END IF;
  delta:=delta+amount;
 END LOOP;
 SELECT coalesce(sum(total),0) INTO previous FROM hotel.invoice_adjustments WHERE invoice_id=NEW.invoice_id;
 IF abs(delta)>999999999999.99 OR original.total+previous+delta NOT BETWEEN 0 AND 999999999999.99 THEN
  RAISE EXCEPTION 'Adjustment would make the cumulative invoice total invalid' USING ERRCODE='23514';
 END IF;
 NEW.total:=delta;
 NEW.adjusted_total:=original.total+previous+delta;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS guard_invoice_adjustment ON invoice_adjustments;
CREATE TRIGGER guard_invoice_adjustment BEFORE INSERT OR UPDATE OR DELETE ON invoice_adjustments
 FOR EACH ROW EXECUTE FUNCTION hotel.guard_invoice_adjustment();
COMMIT;
