-- Hotel Reservation System | PostgreSQL 15+ | Schema v1.0
-- All times are timestamptz (UTC storage), stay dates are hotel-local dates.
BEGIN;
CREATE SCHEMA IF NOT EXISTS hotel;
SET search_path=hotel,public;
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
COMMIT;
