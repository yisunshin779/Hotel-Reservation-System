-- Core service operations. Applications should use these routines inside transactions.
SET search_path=hotel,public;

CREATE OR REPLACE FUNCTION nightly_rate(p_room_type bigint,p_day date) RETURNS numeric
LANGUAGE sql STABLE AS $$
 SELECT coalesce((SELECT pr.nightly_price FROM hotel.pricing_rules pr WHERE pr.room_type_id=p_room_type
 AND pr.active AND p_day BETWEEN pr.starts_on AND pr.ends_on ORDER BY priority DESC,id DESC LIMIT 1),
 (SELECT base_price FROM hotel.room_types WHERE id=p_room_type));
$$;

CREATE OR REPLACE FUNCTION available_count(p_room_type bigint,p_day date) RETURNS integer
LANGUAGE sql STABLE AS $$
 SELECT (
  SELECT count(*)::integer FROM hotel.rooms r WHERE r.room_type_id=p_room_type AND r.active AND r.deleted_at IS NULL
 ) - (
  SELECT count(*)::integer FROM hotel.room_maintenance m JOIN hotel.rooms r ON r.id=m.room_id
  WHERE r.room_type_id=p_room_type AND m.active AND m.starts_on <= p_day AND m.ends_on > p_day
 ) - COALESCE((
  SELECT sum(n.quantity)::integer FROM hotel.reservation_nights n
  JOIN hotel.reservation_details d ON d.id=n.reservation_detail_id
  JOIN hotel.reservations b ON b.id=d.reservation_id
  WHERE n.room_type_id=p_room_type AND n.stay_date=p_day
  AND (b.status IN ('CONFIRMED','CHECKED_IN') OR
       (b.status='PENDING_PAYMENT' AND b.hold_expires_at > now()))
 ),0);
$$;

-- Uses transaction advisory locks by room type, with deterministic ordering to avoid deadlocks.
-- IMPORTANT: Direct INSERT/UPDATE on reservations, reservation_nights, rooms and room_maintenance
-- must be restricted for application DB users, otherwise concurrency guarantees can be bypassed.
CREATE OR REPLACE FUNCTION make_reservation(
 p_customer bigint,p_branch bigint,p_checkin date,p_checkout date,
 p_items jsonb,p_code text,p_pay_later boolean DEFAULT false,p_channel text DEFAULT 'ONLINE',p_promo_code text DEFAULT NULL
) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE rid bigint; item record; d date; sub numeric:=0; line_total numeric; price numeric;
 promo hotel.promotions%ROWTYPE; disc numeric:=0; applicable integer; current_uses integer; customer_uses integer;
BEGIN
 IF p_checkout<=p_checkin OR p_checkin < (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
 THEN RAISE EXCEPTION 'Invalid stay dates'; END IF;
 IF NOT EXISTS(SELECT 1 FROM hotel.branches WHERE id=p_branch AND active) THEN RAISE EXCEPTION 'Branch not active'; END IF;
 IF NOT EXISTS(SELECT 1 FROM hotel.customers WHERE id=p_customer AND deleted_at IS NULL) THEN RAISE EXCEPTION 'Customer missing'; END IF;
 IF jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items)=0 THEN RAISE EXCEPTION 'At least one room type required'; END IF;
 IF EXISTS(SELECT 1 FROM jsonb_to_recordset(p_items) AS x(room_type_id bigint,quantity integer)
           GROUP BY room_type_id HAVING count(*)>1) THEN RAISE EXCEPTION 'Duplicate room type'; END IF;
 FOR item IN SELECT x.room_type_id,x.quantity FROM jsonb_to_recordset(p_items) AS x(room_type_id bigint,quantity integer) ORDER BY x.room_type_id
 LOOP
   IF item.room_type_id IS NULL OR item.quantity IS NULL OR item.quantity<=0 OR
      NOT EXISTS(SELECT 1 FROM hotel.room_types WHERE id=item.room_type_id AND branch_id=p_branch AND active AND deleted_at IS NULL)
   THEN RAISE EXCEPTION 'Invalid room type or quantity'; END IF;
   PERFORM pg_advisory_xact_lock(7101,item.room_type_id::integer);
 END LOOP;
 FOR item IN SELECT x.room_type_id,x.quantity FROM jsonb_to_recordset(p_items) AS x(room_type_id bigint,quantity integer)
 LOOP
  d:=p_checkin; line_total:=0;
  WHILE d<p_checkout LOOP
    IF hotel.available_count(item.room_type_id,d)<item.quantity THEN
      RAISE EXCEPTION 'Not enough inventory for room type % on %',item.room_type_id,d;
    END IF;
    price:=hotel.nightly_rate(item.room_type_id,d);
    line_total:=line_total+price*item.quantity;
    d:=d+1;
  END LOOP;
  sub:=sub+line_total;
 END LOOP;
 IF p_promo_code IS NOT NULL THEN
   SELECT * INTO promo FROM hotel.promotions WHERE code=p_promo_code AND active FOR UPDATE;
   IF NOT FOUND OR now() NOT BETWEEN promo.starts_at AND promo.ends_at OR sub<promo.min_order OR (p_checkout-p_checkin)<promo.min_nights THEN
      RAISE EXCEPTION 'Promotion is invalid'; END IF;
   SELECT count(*) INTO current_uses FROM hotel.promotion_usages WHERE promotion_id=promo.id AND reversed_at IS NULL;
   SELECT count(*) INTO customer_uses FROM hotel.promotion_usages WHERE promotion_id=promo.id AND customer_id=p_customer AND reversed_at IS NULL;
   IF (promo.max_uses IS NOT NULL AND current_uses>=promo.max_uses) OR (promo.per_customer_limit IS NOT NULL AND customer_uses>=promo.per_customer_limit)
   THEN RAISE EXCEPTION 'Promotion use limit exceeded'; END IF;
   disc:=CASE WHEN promo.kind='PERCENT' THEN round(sub*promo.amount/100,2) ELSE promo.amount END;
   IF promo.max_discount IS NOT NULL THEN disc:=least(disc,promo.max_discount); END IF;
   disc:=least(disc,sub);
 END IF;
 INSERT INTO hotel.reservations(code,branch_id,customer_id,check_in,check_out,status,hold_expires_at,book_channel,guest_count,room_subtotal,discount_amount,quoted_total,promotion_id)
 VALUES(p_code,p_branch,p_customer,p_checkin,p_checkout,
 CASE WHEN p_pay_later THEN 'CONFIRMED'::hotel.reservation_status ELSE 'PENDING_PAYMENT'::hotel.reservation_status END,
 CASE WHEN p_pay_later THEN NULL ELSE now()+interval '15 minutes' END,
 p_channel,1,sub,disc,sub-disc,promo.id) RETURNING id INTO rid;
 FOR item IN SELECT x.room_type_id,x.quantity FROM jsonb_to_recordset(p_items) AS x(room_type_id bigint,quantity integer)
 LOOP
  DECLARE detailid bigint; line_total2 numeric:=0;
  BEGIN
   SELECT sum(hotel.nightly_rate(item.room_type_id,day::date))*item.quantity INTO line_total2
   FROM generate_series(p_checkin::timestamp,(p_checkout-1)::timestamp,interval '1 day') day;
   INSERT INTO hotel.reservation_details(reservation_id,room_type_id,quantity,nightly_average,subtotal)
   VALUES(rid,item.room_type_id,item.quantity,line_total2/((p_checkout-p_checkin)*item.quantity),line_total2) RETURNING id INTO detailid;
   INSERT INTO hotel.reservation_nights(reservation_detail_id,room_type_id,stay_date,quantity,nightly_price)
   SELECT detailid,item.room_type_id,day::date,item.quantity,hotel.nightly_rate(item.room_type_id,day::date)
   FROM generate_series(p_checkin::timestamp,(p_checkout-1)::timestamp,interval '1 day') day;
  END;
 END LOOP;
 IF promo.id IS NOT NULL THEN INSERT INTO hotel.promotion_usages(promotion_id,reservation_id,customer_id,discount_amount)
 VALUES(promo.id,rid,p_customer,disc); END IF;
 INSERT INTO hotel.audit_logs(action,entity_name,entity_id,after_data) VALUES('MAKE_RESERVATION','reservations',rid,jsonb_build_object('code',p_code));
 RETURN rid;
END $$;

CREATE OR REPLACE FUNCTION confirm_mock_qr(p_reservation bigint,p_amount numeric,p_key text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE b hotel.reservations%ROWTYPE;
BEGIN
 SELECT * INTO b FROM hotel.reservations WHERE id=p_reservation FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Reservation missing'; END IF;
 IF EXISTS(SELECT 1 FROM hotel.payments WHERE obligation_key=p_key AND reservation_id=p_reservation AND status='PAID') THEN RETURN; END IF;
 IF b.status<>'PENDING_PAYMENT' OR b.hold_expires_at<=now() THEN RAISE EXCEPTION 'Reservation not payable'; END IF;
 IF p_amount<=0 OR p_amount>b.quoted_total THEN RAISE EXCEPTION 'Invalid payment amount'; END IF;
 INSERT INTO hotel.payments(reservation_id,amount,method,status,obligation_key,paid_at)
 VALUES(p_reservation,p_amount,'MOCK_QR','PAID',p_key,now());
 UPDATE hotel.reservations SET status='CONFIRMED',updated_at=now(),version=version+1 WHERE id=p_reservation;
END $$;

CREATE OR REPLACE FUNCTION cancel_reservation(p_reservation bigint,p_reason text DEFAULT NULL) RETURNS void LANGUAGE plpgsql AS $$
DECLARE b hotel.reservations%ROWTYPE;
BEGIN
 SELECT * INTO b FROM hotel.reservations WHERE id=p_reservation FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Reservation missing'; END IF;
 IF b.status='CANCELLED' THEN RETURN; END IF;
 IF b.status NOT IN ('PENDING_PAYMENT','CONFIRMED') THEN RAISE EXCEPTION 'Reservation cannot be cancelled'; END IF;
 UPDATE hotel.reservations SET status='CANCELLED',updated_at=now(),version=version+1 WHERE id=p_reservation;
 UPDATE hotel.promotion_usages SET reversed_at=now() WHERE reservation_id=p_reservation AND reversed_at IS NULL;
 INSERT INTO hotel.audit_logs(action,entity_name,entity_id,after_data) VALUES('CANCEL_RESERVATION','reservations',p_reservation,jsonb_build_object('reason',p_reason));
 -- Cancellation refunds are not automatic: create via separate approved refund operation.
END $$;

CREATE OR REPLACE FUNCTION expire_holds() RETURNS integer LANGUAGE plpgsql AS $$
DECLARE r record; counter integer:=0;
BEGIN
 FOR r IN SELECT id FROM hotel.reservations WHERE status='PENDING_PAYMENT' AND hold_expires_at<=now() ORDER BY id FOR UPDATE SKIP LOCKED
 LOOP
  PERFORM hotel.cancel_reservation(r.id,'HOLD_TIMEOUT'); counter:=counter+1;
 END LOOP;
 RETURN counter;
END $$;

CREATE OR REPLACE FUNCTION maintenance_block(p_room bigint,p_start date,p_end date,p_reason text) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE rt bigint; mid bigint; d date;
BEGIN
 IF p_end<=p_start THEN RAISE EXCEPTION 'Invalid maintenance period'; END IF;
 SELECT room_type_id INTO rt FROM hotel.rooms WHERE id=p_room AND active;
 IF NOT FOUND THEN RAISE EXCEPTION 'Room missing'; END IF;
 PERFORM pg_advisory_xact_lock(7101,rt::integer);
 IF EXISTS(SELECT 1 FROM hotel.room_assignments WHERE room_id=p_room AND released_at IS NULL) THEN RAISE EXCEPTION 'Occupied room'; END IF;
 d:=p_start;
 WHILE d<p_end LOOP
   IF hotel.available_count(rt,d)<1 THEN RAISE EXCEPTION 'Maintenance would violate reservation capacity on %',d; END IF;
   d:=d+1;
 END LOOP;
 INSERT INTO hotel.room_maintenance(room_id,starts_on,ends_on,reason) VALUES(p_room,p_start,p_end,p_reason) RETURNING id INTO mid;
 RETURN mid;
END $$;

CREATE OR REPLACE FUNCTION checkin_assign(p_reservation_detail bigint,p_room bigint,p_reason text DEFAULT NULL) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE b hotel.reservations%ROWTYPE; detail hotel.reservation_details%ROWTYPE; rm hotel.rooms%ROWTYPE; num integer; aid bigint;
BEGIN
 SELECT * INTO detail FROM hotel.reservation_details WHERE id=p_reservation_detail;
 IF NOT FOUND THEN RAISE EXCEPTION 'Reservation detail missing'; END IF;
 SELECT * INTO b FROM hotel.reservations WHERE id=detail.reservation_id FOR UPDATE;
 SELECT * INTO rm FROM hotel.rooms WHERE id=p_room FOR UPDATE;
 IF b.status NOT IN ('CONFIRMED','CHECKED_IN') OR rm.id IS NULL OR rm.room_type_id<>detail.room_type_id OR rm.branch_id<>b.branch_id
 OR NOT rm.active OR rm.occupancy<>'VACANT' OR rm.cleaning<>'READY' THEN RAISE EXCEPTION 'Room not available for checkin'; END IF;
 IF (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date <> b.check_in AND b.status='CONFIRMED' THEN RAISE EXCEPTION 'Outside check-in day'; END IF;
 IF EXISTS(SELECT 1 FROM hotel.room_maintenance WHERE room_id=p_room AND active AND starts_on<b.check_out AND ends_on>b.check_in) THEN
 RAISE EXCEPTION 'Room maintenance overlaps stay'; END IF;
 SELECT count(*) INTO num FROM hotel.room_assignments WHERE reservation_detail_id=p_reservation_detail AND released_at IS NULL;
 IF num>=detail.quantity THEN RAISE EXCEPTION 'Assigned room quantity exceeded'; END IF;
 IF EXISTS(SELECT 1 FROM hotel.room_assignments WHERE room_id=p_room AND released_at IS NULL) THEN RAISE EXCEPTION 'Already assigned'; END IF;
 INSERT INTO hotel.room_assignments(reservation_detail_id,room_type_id,room_id,arrival_at,reason)
 VALUES(p_reservation_detail,detail.room_type_id,p_room,now(),p_reason) RETURNING id INTO aid;
 UPDATE hotel.rooms SET occupancy='OCCUPIED',version=version+1 WHERE id=p_room;
 UPDATE hotel.reservations SET status='CHECKED_IN',actual_checkin=coalesce(actual_checkin,now()),updated_at=now() WHERE id=b.id;
 RETURN aid;
END $$;

CREATE OR REPLACE FUNCTION add_service_usage(p_reservation bigint,p_service bigint,p_qty integer,p_recorded_by bigint DEFAULT NULL) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE sid bigint; unitprice numeric; bbranch bigint; sbranch bigint;
BEGIN
 SELECT branch_id INTO bbranch FROM hotel.reservations WHERE id=p_reservation AND status='CHECKED_IN';
 SELECT branch_id,current_price INTO sbranch,unitprice FROM hotel.services WHERE id=p_service AND active;
 IF bbranch IS NULL OR sbranch IS NULL OR bbranch<>sbranch OR p_qty<=0 THEN RAISE EXCEPTION 'Invalid service usage'; END IF;
 INSERT INTO hotel.service_usages(reservation_id,service_id,quantity,unit_price,recorded_by)
 VALUES(p_reservation,p_service,p_qty,unitprice,p_recorded_by) RETURNING id INTO sid;
 RETURN sid;
END $$;

-- Folio totals are based on stored snapshots; tax_rate is a demo parameter, not a legal tax assertion.
CREATE OR REPLACE FUNCTION folio_preview(p_reservation bigint,p_tax_rate numeric DEFAULT 0)
 RETURNS TABLE(room_cost numeric,service_cost numeric,extra_cost numeric,discount numeric,tax numeric,total numeric,paid numeric,balance numeric)
 LANGUAGE sql STABLE AS $$
 SELECT b.room_subtotal,
 coalesce((SELECT sum(s.quantity*s.unit_price) FROM hotel.service_usages s WHERE s.reservation_id=b.id AND s.voided_at IS NULL),0),
 coalesce((SELECT sum(e.amount) FROM hotel.extra_charges e WHERE e.reservation_id=b.id),0),
 b.discount_amount,
 round(greatest(0,b.room_subtotal+coalesce((SELECT sum(s.quantity*s.unit_price) FROM hotel.service_usages s WHERE s.reservation_id=b.id AND s.voided_at IS NULL),0)+coalesce((SELECT sum(e.amount) FROM hotel.extra_charges e WHERE e.reservation_id=b.id),0)-b.discount_amount)*p_tax_rate,2),
 round(greatest(0,b.room_subtotal+coalesce((SELECT sum(s.quantity*s.unit_price) FROM hotel.service_usages s WHERE s.reservation_id=b.id AND s.voided_at IS NULL),0)+coalesce((SELECT sum(e.amount) FROM hotel.extra_charges e WHERE e.reservation_id=b.id),0)-b.discount_amount)*(1+p_tax_rate),2),
 coalesce((SELECT sum(p.amount) FROM hotel.payments p WHERE p.reservation_id=b.id AND p.status='PAID'),0)-coalesce((SELECT sum(f.amount) FROM hotel.refunds f WHERE f.reservation_id=b.id AND f.status='DONE'),0),
 round(greatest(0,b.room_subtotal+coalesce((SELECT sum(s.quantity*s.unit_price) FROM hotel.service_usages s WHERE s.reservation_id=b.id AND s.voided_at IS NULL),0)+coalesce((SELECT sum(e.amount) FROM hotel.extra_charges e WHERE e.reservation_id=b.id),0)-b.discount_amount)*(1+p_tax_rate),2)
 -coalesce((SELECT sum(p.amount) FROM hotel.payments p WHERE p.reservation_id=b.id AND p.status='PAID'),0)+coalesce((SELECT sum(f.amount) FROM hotel.refunds f WHERE f.reservation_id=b.id AND f.status='DONE'),0)
 FROM hotel.reservations b WHERE b.id=p_reservation;
$$;

CREATE OR REPLACE FUNCTION checkout_close(p_reservation bigint,p_invoice_number text,p_tax_rate numeric DEFAULT 0) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE b hotel.reservations%ROWTYPE; f record; invoice_id bigint; assigned_count integer;
BEGIN
 SELECT * INTO b FROM hotel.reservations WHERE id=p_reservation FOR UPDATE;
 IF b.status<>'CHECKED_IN' THEN RAISE EXCEPTION 'Not checked in'; END IF;
 IF p_tax_rate<0 OR p_tax_rate>1 THEN RAISE EXCEPTION 'Invalid tax rate'; END IF;
 SELECT * INTO f FROM hotel.folio_preview(p_reservation,p_tax_rate);
 IF abs(f.balance)>0.005 THEN RAISE EXCEPTION 'Folio not settled, balance %',f.balance; END IF;
 INSERT INTO hotel.invoices(reservation_id,invoice_number,room_amount,service_amount,extras_amount,discount_amount,tax_amount,total,status,closed_at)
 VALUES(p_reservation,p_invoice_number,f.room_cost,f.service_cost,f.extra_cost,f.discount,f.tax,f.total,'DRAFT',NULL) RETURNING id INTO invoice_id;
 INSERT INTO hotel.invoice_lines(invoice_id,line_type,description,quantity,unit_price,amount)
 VALUES(invoice_id,'ROOM','Room nights',1,f.room_cost,f.room_cost);
 INSERT INTO hotel.invoice_lines(invoice_id,line_type,description,quantity,unit_price,amount)
 SELECT invoice_id,'SERVICE',s.name,u.quantity,u.unit_price,u.quantity*u.unit_price
 FROM hotel.service_usages u JOIN hotel.services s ON s.id=u.service_id WHERE u.reservation_id=p_reservation AND u.voided_at IS NULL;
 INSERT INTO hotel.invoice_lines(invoice_id,line_type,description,quantity,unit_price,amount)
 SELECT invoice_id,'EXTRA',description,1,amount,amount FROM hotel.extra_charges WHERE reservation_id=p_reservation;
 IF f.discount>0 THEN INSERT INTO hotel.invoice_lines(invoice_id,line_type,description,quantity,unit_price,amount) VALUES(invoice_id,'DISCOUNT','Booking discount',1,-f.discount,-f.discount); END IF;
 IF f.tax>0 THEN INSERT INTO hotel.invoice_lines(invoice_id,line_type,description,quantity,unit_price,amount) VALUES(invoice_id,'TAX','Configured demo tax',1,f.tax,f.tax); END IF;
 UPDATE hotel.invoices SET status='CLOSED',closed_at=now() WHERE id=invoice_id;
 UPDATE hotel.rooms SET occupancy='VACANT',cleaning='DIRTY',version=version+1
 WHERE id IN (SELECT a.room_id FROM hotel.room_assignments a JOIN hotel.reservation_details d ON d.id=a.reservation_detail_id WHERE d.reservation_id=p_reservation AND a.released_at IS NULL);
 UPDATE hotel.room_assignments SET released_at=now(),departure_at=now()
 WHERE reservation_detail_id IN (SELECT id FROM hotel.reservation_details WHERE reservation_id=p_reservation) AND released_at IS NULL;
 UPDATE hotel.reservations SET status='CHECKED_OUT',actual_checkout=now(),updated_at=now() WHERE id=p_reservation;
 RETURN invoice_id;
END $$;
