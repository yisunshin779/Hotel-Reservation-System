-- UC16 / UC19 integration guards. Apply after 01_schema.sql and 02_operations.sql.
-- Additive and repeatable; no replacement of booking / service / payment workflows.
BEGIN;
SET LOCAL search_path=hotel,public;

-- Upgrade the v1 legacy checkout routine as well: it originally inserted CLOSED
-- before its lines. A closed invoice must now be assembled as DRAFT then frozen.
-- Fresh installations use the corrected 02_operations.sql; this block upgrades
-- databases where that older function was already installed, and is repeatable.
DO $migration$
DECLARE definition text;
BEGIN
 SELECT pg_get_functiondef('hotel.checkout_close(bigint,text,numeric)'::regprocedure) INTO definition;
 IF strpos(definition, 'f.total,''CLOSED'',now())') > 0 THEN
   definition:=replace(definition,'f.total,''CLOSED'',now())','f.total,''DRAFT'',NULL)');
   definition:=replace(definition,'UPDATE hotel.rooms SET occupancy=''VACANT''',
     'UPDATE hotel.invoices SET status=''CLOSED'',closed_at=now() WHERE id=invoice_id; UPDATE hotel.rooms SET occupancy=''VACANT''');
   EXECUTE definition;
 END IF;
END $migration$;

-- Financial writers (including existing SQL functions and future UC08/15/18 adapters)
-- must serialize with checkout on the reservation row. This prevents a service charge
-- or payment from committing behind a closed invoice after checkout calculated its bill.
CREATE OR REPLACE FUNCTION hotel.guard_stay_financial_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE rid bigint; current_state hotel.reservation_status;
BEGIN
 IF TG_OP='DELETE' THEN rid:=OLD.reservation_id; ELSE rid:=NEW.reservation_id; END IF;
 IF TG_OP='UPDATE' AND NEW.reservation_id<>OLD.reservation_id THEN
   RAISE EXCEPTION 'Cannot move financial records between reservations' USING ERRCODE='23514';
 END IF;
 SELECT status INTO current_state FROM hotel.reservations WHERE id=rid FOR UPDATE;
 IF current_state='CHECKED_OUT' OR EXISTS(SELECT 1 FROM hotel.invoices WHERE reservation_id=rid AND status='CLOSED') THEN
   RAISE EXCEPTION 'Closed stay financial data is immutable' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS guard_checkout_services ON hotel.service_usages;
CREATE TRIGGER guard_checkout_services BEFORE INSERT OR UPDATE OR DELETE ON hotel.service_usages
 FOR EACH ROW EXECUTE FUNCTION hotel.guard_stay_financial_write();
DROP TRIGGER IF EXISTS guard_checkout_extras ON hotel.extra_charges;
CREATE TRIGGER guard_checkout_extras BEFORE INSERT OR UPDATE OR DELETE ON hotel.extra_charges
 FOR EACH ROW EXECUTE FUNCTION hotel.guard_stay_financial_write();
DROP TRIGGER IF EXISTS guard_checkout_payments ON hotel.payments;
CREATE TRIGGER guard_checkout_payments BEFORE INSERT OR UPDATE OR DELETE ON hotel.payments
 FOR EACH ROW EXECUTE FUNCTION hotel.guard_stay_financial_write();
DROP TRIGGER IF EXISTS guard_checkout_refunds ON hotel.refunds;
CREATE TRIGGER guard_checkout_refunds BEFORE INSERT OR UPDATE OR DELETE ON hotel.refunds
 FOR EACH ROW EXECUTE FUNCTION hotel.guard_stay_financial_write();

CREATE OR REPLACE FUNCTION hotel.guard_closed_invoice() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='CLOSED' THEN
   RAISE EXCEPTION 'Closed invoices cannot be changed' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS guard_closed_invoice ON hotel.invoices;
CREATE TRIGGER guard_closed_invoice BEFORE UPDATE OR DELETE ON hotel.invoices
 FOR EACH ROW EXECUTE FUNCTION hotel.guard_closed_invoice();

CREATE OR REPLACE FUNCTION hotel.guard_closed_invoice_line() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE iid bigint; state hotel.invoice_status;
BEGIN
 IF TG_OP='DELETE' THEN iid:=OLD.invoice_id; ELSE iid:=NEW.invoice_id; END IF;
 IF TG_OP='UPDATE' AND OLD.invoice_id<>NEW.invoice_id THEN
   RAISE EXCEPTION 'Cannot move invoice lines' USING ERRCODE='23514';
 END IF;
 SELECT status INTO state FROM hotel.invoices WHERE id=iid FOR UPDATE;
 IF state='CLOSED' THEN RAISE EXCEPTION 'Closed invoice lines cannot be changed' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS guard_closed_invoice_line ON hotel.invoice_lines;
CREATE TRIGGER guard_closed_invoice_line BEFORE INSERT OR UPDATE OR DELETE ON hotel.invoice_lines
 FOR EACH ROW EXECUTE FUNCTION hotel.guard_closed_invoice_line();
COMMIT;
