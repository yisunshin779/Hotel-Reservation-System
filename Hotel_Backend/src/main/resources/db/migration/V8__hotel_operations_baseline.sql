-- Snapshot of the operational schema through 08. Demo, seed and fixtures are excluded.

-- Source: Hotel_Database/sql/01_schema.sql
-- Hotel Reservation System | PostgreSQL 15+ | Schema v1.0
-- All times are timestamptz (UTC storage), stay dates are hotel-local dates.
CREATE SCHEMA IF NOT EXISTS hotel;
SET LOCAL search_path=hotel,public;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TYPE reservation_status AS ENUM ('PENDING_PAYMENT','CONFIRMED','CHECKED_IN','CHECKED_OUT','CANCELLED','NO_SHOW');
CREATE TYPE payment_status AS ENUM ('UNPAID','PENDING','PAID','FAILED','REFUNDED');
CREATE TYPE payment_method AS ENUM ('CASH','MOCK_QR','MOCK_CARD');
CREATE TYPE occupancy_status AS ENUM ('VACANT','OCCUPIED');
CREATE TYPE cleaning_status AS ENUM ('READY','DIRTY','CLEANING');
CREATE TYPE invoice_status AS ENUM ('DRAFT','CLOSED','ADJUSTED');

CREATE TABLE branches (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 name varchar(160) NOT NULL UNIQUE, address text NOT NULL, city varchar(100) NOT NULL,
 phone varchar(25), checkin_time time NOT NULL DEFAULT '14:00', checkout_time time NOT NULL DEFAULT '12:00',
 timezone varchar(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh', active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz
);
CREATE TABLE roles (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 code varchar(30) NOT NULL UNIQUE, name varchar(100) NOT NULL,
 description text
);
CREATE TABLE users (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 email varchar(254) NOT NULL UNIQUE, full_name varchar(160) NOT NULL, phone varchar(25),
 password_hash text NOT NULL, role_id bigint NOT NULL REFERENCES roles(id),
 branch_id bigint REFERENCES branches(id), active boolean NOT NULL DEFAULT true,
 email_verified_at timestamptz, failed_login_count integer NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
 locked_until timestamptz, created_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz
);
CREATE TABLE customers (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id bigint UNIQUE REFERENCES users(id), full_name varchar(160) NOT NULL,
 email varchar(254), phone varchar(25), nationality varchar(80), identity_document text,
 vip boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz
);
CREATE TABLE auth_tokens (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id bigint NOT NULL REFERENCES users(id), purpose varchar(30) NOT NULL CHECK (purpose IN ('EMAIL_VERIFY','PASSWORD_RESET','EMAIL_CHANGE')),
 token_hash text NOT NULL UNIQUE, pending_email varchar(254), expires_at timestamptz NOT NULL,
 used_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE room_types (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 branch_id bigint NOT NULL REFERENCES branches(id), name varchar(120) NOT NULL,
 description text, capacity integer NOT NULL CHECK (capacity > 0),
 base_price numeric(14,2) NOT NULL CHECK (base_price > 0),
 area_m2 numeric(7,2), bed_type varchar(80), amenities text,
 active boolean NOT NULL DEFAULT true, deleted_at timestamptz,
 UNIQUE(branch_id,name), UNIQUE(id,branch_id)
);
CREATE TABLE rooms (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 branch_id bigint NOT NULL REFERENCES branches(id),
 room_type_id bigint NOT NULL,
 room_number varchar(20) NOT NULL, floor integer NOT NULL DEFAULT 1,
 occupancy occupancy_status NOT NULL DEFAULT 'VACANT',
 cleaning cleaning_status NOT NULL DEFAULT 'READY',
 active boolean NOT NULL DEFAULT true, deleted_at timestamptz, version integer NOT NULL DEFAULT 0,
 FOREIGN KEY(room_type_id,branch_id) REFERENCES room_types(id,branch_id),
 UNIQUE(branch_id,room_number), UNIQUE(id,room_type_id)
);
CREATE TABLE pricing_rules (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 room_type_id bigint NOT NULL REFERENCES room_types(id), name varchar(140) NOT NULL,
 starts_on date NOT NULL, ends_on date NOT NULL,
 priority integer NOT NULL DEFAULT 0, nightly_price numeric(14,2) NOT NULL CHECK(nightly_price > 0),
 active boolean NOT NULL DEFAULT true, CHECK(ends_on >= starts_on)
);
-- Same priority must never have overlapping active windows.
ALTER TABLE pricing_rules ADD CONSTRAINT pricing_no_same_priority_overlap
 EXCLUDE USING gist(room_type_id WITH =, priority WITH =, daterange(starts_on,ends_on + 1,'[)') WITH &&) WHERE (active);
CREATE TABLE promotions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, code varchar(40) NOT NULL UNIQUE,
 name varchar(150) NOT NULL, kind varchar(12) NOT NULL CHECK(kind IN ('PERCENT','FIXED')),
 amount numeric(14,2) NOT NULL CHECK(amount > 0), max_discount numeric(14,2),
 min_order numeric(14,2) NOT NULL DEFAULT 0 CHECK(min_order >= 0), min_nights integer NOT NULL DEFAULT 1 CHECK(min_nights > 0),
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
 max_uses integer, per_customer_limit integer, active boolean NOT NULL DEFAULT true,
 CHECK(ends_at > starts_at), CHECK(kind <> 'PERCENT' OR amount <= 100),
 CHECK(max_uses IS NULL OR max_uses > 0),CHECK(per_customer_limit IS NULL OR per_customer_limit > 0)
);
CREATE TABLE reservations (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, code varchar(40) NOT NULL UNIQUE,
 branch_id bigint NOT NULL REFERENCES branches(id), customer_id bigint NOT NULL REFERENCES customers(id),
 check_in date NOT NULL, check_out date NOT NULL,
 status reservation_status NOT NULL DEFAULT 'PENDING_PAYMENT',
 hold_expires_at timestamptz, book_channel varchar(12) NOT NULL DEFAULT 'ONLINE' CHECK(book_channel IN ('ONLINE','WALK_IN')),
 guest_count integer NOT NULL DEFAULT 1 CHECK(guest_count > 0),
 room_subtotal numeric(14,2) NOT NULL DEFAULT 0 CHECK(room_subtotal >= 0),
 discount_amount numeric(14,2) NOT NULL DEFAULT 0 CHECK(discount_amount >= 0),
 quoted_total numeric(14,2) NOT NULL DEFAULT 0 CHECK(quoted_total >= 0),
 promotion_id bigint REFERENCES promotions(id), special_requests text,
 actual_checkin timestamptz, actual_checkout timestamptz,
 created_by bigint REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 version integer NOT NULL DEFAULT 0, CHECK(check_out > check_in),
 CHECK(status <> 'PENDING_PAYMENT' OR hold_expires_at IS NOT NULL), UNIQUE(id,branch_id)
);
CREATE TABLE reservation_details (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 reservation_id bigint NOT NULL REFERENCES reservations(id) ON DELETE RESTRICT,
 room_type_id bigint NOT NULL REFERENCES room_types(id),
 quantity integer NOT NULL CHECK(quantity > 0),
 nightly_average numeric(14,2) NOT NULL CHECK(nightly_average >= 0),
 subtotal numeric(14,2) NOT NULL CHECK(subtotal >= 0),
 UNIQUE(reservation_id,room_type_id), UNIQUE(id,room_type_id)
);
CREATE TABLE reservation_nights (
 reservation_detail_id bigint NOT NULL REFERENCES reservation_details(id) ON DELETE RESTRICT,
 room_type_id bigint NOT NULL REFERENCES room_types(id), stay_date date NOT NULL,
 quantity integer NOT NULL CHECK(quantity > 0),
 nightly_price numeric(14,2) NOT NULL CHECK(nightly_price >= 0),
 PRIMARY KEY(reservation_detail_id,stay_date)
);
CREATE INDEX idx_reservation_nights_avail ON reservation_nights(room_type_id,stay_date);
CREATE TABLE room_assignments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 reservation_detail_id bigint NOT NULL, room_type_id bigint NOT NULL,
 room_id bigint NOT NULL, assigned_at timestamptz NOT NULL DEFAULT now(),
 released_at timestamptz, arrival_at timestamptz, departure_at timestamptz,
 reason text, FOREIGN KEY(reservation_detail_id,room_type_id) REFERENCES reservation_details(id,room_type_id),
 FOREIGN KEY(room_id,room_type_id) REFERENCES rooms(id,room_type_id),
 CHECK(released_at IS NULL OR released_at >= assigned_at)
);
CREATE UNIQUE INDEX uq_room_current_assignment ON room_assignments(room_id) WHERE released_at IS NULL;
CREATE TABLE room_maintenance (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, room_id bigint NOT NULL REFERENCES rooms(id),
 starts_on date NOT NULL, ends_on date NOT NULL, reason text NOT NULL,
 active boolean NOT NULL DEFAULT true, created_by bigint REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(), closed_at timestamptz,
 CHECK(ends_on > starts_on)
);
ALTER TABLE room_maintenance ADD CONSTRAINT no_overlapping_maintenance
 EXCLUDE USING gist (room_id WITH =, daterange(starts_on,ends_on,'[)') WITH &&) WHERE (active);
CREATE TABLE services (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, branch_id bigint NOT NULL REFERENCES branches(id),
 category varchar(90) NOT NULL, name varchar(140) NOT NULL, unit varchar(30) NOT NULL DEFAULT 'lần',
 current_price numeric(14,2) NOT NULL CHECK(current_price >= 0), active boolean NOT NULL DEFAULT true,
 deleted_at timestamptz, UNIQUE(branch_id,category,name)
);
CREATE TABLE service_usages (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 reservation_id bigint NOT NULL REFERENCES reservations(id), service_id bigint NOT NULL REFERENCES services(id),
 quantity integer NOT NULL CHECK(quantity > 0), unit_price numeric(14,2) NOT NULL CHECK(unit_price >= 0),
 used_at timestamptz NOT NULL DEFAULT now(), note text, voided_at timestamptz,
 void_reason text, recorded_by bigint REFERENCES users(id),
 CHECK((voided_at IS NULL AND void_reason IS NULL) OR (voided_at IS NOT NULL AND void_reason IS NOT NULL))
);
CREATE TABLE extra_charges (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, reservation_id bigint NOT NULL REFERENCES reservations(id),
 description text NOT NULL, amount numeric(14,2) NOT NULL CHECK(amount <> 0),
 created_at timestamptz NOT NULL DEFAULT now(), created_by bigint REFERENCES users(id)
);
CREATE TABLE promotion_usages (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 promotion_id bigint NOT NULL REFERENCES promotions(id), reservation_id bigint NOT NULL UNIQUE REFERENCES reservations(id),
 customer_id bigint NOT NULL REFERENCES customers(id), discount_amount numeric(14,2) NOT NULL CHECK(discount_amount >= 0),
 used_at timestamptz NOT NULL DEFAULT now(), reversed_at timestamptz
);
CREATE TABLE invoices (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 reservation_id bigint NOT NULL UNIQUE REFERENCES reservations(id), invoice_number varchar(45) NOT NULL UNIQUE,
 room_amount numeric(14,2) NOT NULL CHECK(room_amount >= 0), service_amount numeric(14,2) NOT NULL CHECK(service_amount >= 0),
 extras_amount numeric(14,2) NOT NULL DEFAULT 0, discount_amount numeric(14,2) NOT NULL DEFAULT 0 CHECK(discount_amount >= 0),
 tax_amount numeric(14,2) NOT NULL DEFAULT 0 CHECK(tax_amount >= 0),
 total numeric(14,2) NOT NULL CHECK(total >= 0), issued_at timestamptz NOT NULL DEFAULT now(),
 status invoice_status NOT NULL DEFAULT 'DRAFT', closed_at timestamptz
);
CREATE TABLE invoice_lines (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, invoice_id bigint NOT NULL REFERENCES invoices(id),
 line_type varchar(20) NOT NULL CHECK(line_type IN ('ROOM','SERVICE','EXTRA','DISCOUNT','TAX')),
 description text NOT NULL, quantity numeric(10,2) NOT NULL CHECK(quantity > 0),
 unit_price numeric(14,2) NOT NULL, amount numeric(14,2) NOT NULL
);
CREATE TABLE payments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, reservation_id bigint NOT NULL REFERENCES reservations(id),
 invoice_id bigint REFERENCES invoices(id), amount numeric(14,2) NOT NULL CHECK(amount > 0),
 method payment_method NOT NULL, status payment_status NOT NULL DEFAULT 'PENDING',
 obligation_key varchar(100) NOT NULL UNIQUE, paid_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), recorded_by bigint REFERENCES users(id)
);
CREATE TABLE refunds (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 payment_id bigint NOT NULL REFERENCES payments(id), reservation_id bigint NOT NULL REFERENCES reservations(id),
 amount numeric(14,2) NOT NULL CHECK(amount > 0), status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','DONE','REJECTED')),
 reason text, idempotency_key varchar(100) NOT NULL UNIQUE, created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz
);
CREATE TABLE reviews (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, reservation_id bigint NOT NULL UNIQUE REFERENCES reservations(id),
 room_type_id bigint NOT NULL REFERENCES room_types(id), score integer NOT NULL CHECK(score BETWEEN 1 AND 5),
 cleanliness integer CHECK(cleanliness BETWEEN 1 AND 5), service_score integer CHECK(service_score BETWEEN 1 AND 5),
 content text, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE support_requests (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 reservation_id bigint NOT NULL REFERENCES reservations(id), title varchar(180) NOT NULL, category varchar(50) NOT NULL,
 content text NOT NULL, status varchar(20) NOT NULL DEFAULT 'NEW' CHECK(status IN ('NEW','PROCESSING','RESOLVED','REOPENED')),
 created_at timestamptz NOT NULL DEFAULT now(), resolved_at timestamptz
);
CREATE TABLE support_replies (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, request_id bigint NOT NULL REFERENCES support_requests(id),
 author_user_id bigint REFERENCES users(id), message text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE audit_logs (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 actor_id bigint REFERENCES users(id), action varchar(100) NOT NULL, entity_name varchar(80) NOT NULL,
 entity_id bigint, before_data jsonb, after_data jsonb, ip inet, recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservations_branch_date ON reservations(branch_id,check_in,check_out);
CREATE INDEX idx_reservations_customer ON reservations(customer_id,created_at DESC);
CREATE INDEX idx_reservations_expiring ON reservations(hold_expires_at) WHERE status='PENDING_PAYMENT';
CREATE INDEX idx_payments_reservation ON payments(reservation_id,status);
CREATE INDEX idx_services_usage_reservation ON service_usages(reservation_id) WHERE voided_at IS NULL;
CREATE INDEX idx_maintenance_dates ON room_maintenance(room_id,starts_on,ends_on) WHERE active;
CREATE INDEX idx_audit_log_time ON audit_logs(recorded_at DESC);

-- Source: Hotel_Database/sql/02_operations.sql
-- Core service operations. Applications should use these routines inside transactions.
SET LOCAL search_path=hotel,public;

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

-- Source: Hotel_Database/sql/05_checkin_checkout.sql
-- UC16 / UC19 integration guards. Apply after 01_schema.sql and 02_operations.sql.
-- Additive and repeatable; no replacement of booking / service / payment workflows.
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

-- Source: Hotel_Database/sql/06_stay_contracts.sql
-- Report contracts OC-16.01/02, OC-17.01, OC-19.01/02/03 and OC-11.02.
-- Apply after 05_checkin_checkout.sql. Additive, repeatable migration.
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

-- Source: Hotel_Database/sql/07_payment.sql
-- UC08 / OC-08.01-03. Apply after 05_checkin_checkout and 06_stay_contracts.
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

-- Source: Hotel_Database/sql/08_invoice_adjustments.sql
-- UC19 / OC-19.03: Admin issues a separate adjustment with a mandatory reason.
-- Apply after 06_stay_contracts.sql (or 07_payment.sql when Payment is enabled).
-- Original invoices, lines, payments, refunds and reservation state remain immutable.
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
