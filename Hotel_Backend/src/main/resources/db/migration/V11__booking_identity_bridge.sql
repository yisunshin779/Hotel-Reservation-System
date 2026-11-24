ALTER TABLE hotel.users ALTER COLUMN full_name TYPE varchar(255);
ALTER TABLE hotel.customers ALTER COLUMN full_name TYPE varchar(255);

CREATE TABLE hotel.account_user_links (
    account_id uuid PRIMARY KEY REFERENCES hotel.account(id),
    user_id bigint NOT NULL UNIQUE REFERENCES hotel.users(id)
);

CREATE TABLE hotel.booking_hold_requests (
    customer_id bigint NOT NULL REFERENCES hotel.customers(id),
    request_id uuid NOT NULL,
    reservation_id bigint NOT NULL UNIQUE REFERENCES hotel.reservations(id),
    PRIMARY KEY (customer_id, request_id)
);

INSERT INTO hotel.roles(code, name) VALUES ('GUEST', 'Guest') ON CONFLICT (code) DO NOTHING;
