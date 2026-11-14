INSERT INTO hotel.branches(id,name,address,city,active) OVERRIDING SYSTEM VALUE VALUES
 (91001,'Report A','A','A',true),(91002,'Report B','B','B',false),(91003,'Report Empty','C','C',true);
INSERT INTO hotel.customers(id,full_name) OVERRIDING SYSTEM VALUE VALUES (91001,'Report Guest');
INSERT INTO hotel.reservations(id,code,branch_id,customer_id,check_in,check_out,status)
 OVERRIDING SYSTEM VALUE VALUES
 (91001,'REPORT-1',91001,91001,'2026-10-01','2026-10-02','CHECKED_OUT'),
 (91002,'REPORT-2',91002,91001,'2026-10-01','2026-10-02','CHECKED_OUT'),
 (91003,'REPORT-DRAFT',91001,91001,'2026-10-01','2026-10-02','CHECKED_OUT'),
 (91004,'REPORT-END',91001,91001,'2026-10-01','2026-10-02','CHECKED_OUT'),
 (91005,'REPORT-BEFORE',91001,91001,'2026-10-01','2026-10-02','CHECKED_OUT');
INSERT INTO hotel.invoices(reservation_id,invoice_number,room_amount,service_amount,extras_amount,
 discount_amount,tax_amount,total,status,closed_at) VALUES
 (91001,'REPORT-I1',100.10,20.20,3.30,4.40,5.50,124.70,'CLOSED','2026-09-30T17:00:00Z'),
 (91002,'REPORT-I2',50.05,0,0,0,0,50.05,'CLOSED','2026-10-03T16:59:59Z'),
 (91003,'REPORT-ID',900,0,0,0,0,900,'DRAFT','2026-10-01T00:00:00Z'),
 (91004,'REPORT-IE',800,0,0,0,0,800,'CLOSED','2026-10-03T17:00:00Z'),
 (91005,'REPORT-IB',700,0,0,0,0,700,'CLOSED','2026-09-30T16:59:59Z');

INSERT INTO hotel.roles(id,code,name) OVERRIDING SYSTEM VALUE VALUES
 (91001,'ADMIN','Admin'),(91002,'MANAGER','Manager'),(91003,'GUEST','Guest');
INSERT INTO hotel.users(email,full_name,password_hash,role_id,branch_id) VALUES
 ('admin@test.local','Admin','{noop}test-password',91001,NULL),
 ('manager@test.local','Manager','{noop}test-password',91002,91001),
 ('guest@test.local','Guest','{noop}test-password',91003,NULL),
 ('disabled@test.local','Disabled','{noop}test-password',91001,NULL);
UPDATE hotel.users SET active=false WHERE email='disabled@test.local';
INSERT INTO hotel.room_types(id,branch_id,name,capacity,base_price) OVERRIDING SYSTEM VALUE VALUES
 (91001,91001,'Standard',2,60),(91002,91001,'Deluxe',2,40),(91003,91002,'Standard',2,50);
INSERT INTO hotel.rooms(id,branch_id,room_type_id,room_number) OVERRIDING SYSTEM VALUE VALUES
 (91001,91001,91001,'101'),(91002,91001,91001,'102'),(91003,91001,91002,'201'),(91004,91002,91003,'101');
INSERT INTO hotel.room_maintenance(room_id,starts_on,ends_on,reason) VALUES
 (91001,'2026-10-02','2026-10-03','Test maintenance');
INSERT INTO hotel.reservation_details(id,reservation_id,room_type_id,quantity,nightly_average,subtotal)
 OVERRIDING SYSTEM VALUE VALUES
 (91001,91001,91001,1,30,60),(91002,91001,91002,1,20,40),(91003,91002,91003,1,50.05,50.05);
INSERT INTO hotel.reservation_nights(reservation_detail_id,room_type_id,stay_date,quantity,nightly_price) VALUES
 (91001,91001,'2026-10-01',1,30),(91001,91001,'2026-10-02',1,30),
 (91002,91002,'2026-10-01',1,20),(91002,91002,'2026-10-02',1,20),
 (91003,91003,'2026-10-03',1,50.05);
UPDATE hotel.reservations SET created_at='2026-10-01 00:00:00+07' WHERE code LIKE 'REPORT-%';
INSERT INTO hotel.reservations(id,code,branch_id,customer_id,check_in,check_out,status,created_at)
 OVERRIDING SYSTEM VALUE VALUES
 (91006,'REPORT-CANCELLED',91001,91001,'2026-10-01','2026-10-02','CANCELLED','2026-10-01 00:00:00+07'),
 (91007,'REPORT-NOSHOW',91001,91001,'2026-10-01','2026-10-02','NO_SHOW','2026-10-01 00:00:00+07');
INSERT INTO hotel.reservation_details(id,reservation_id,room_type_id,quantity,nightly_average,subtotal)
 OVERRIDING SYSTEM VALUE VALUES (91006,91006,91001,1,30,30),(91007,91007,91001,1,30,30);
INSERT INTO hotel.reservation_nights(reservation_detail_id,room_type_id,stay_date,quantity,nightly_price) VALUES
 (91006,91001,'2026-10-01',1,30),(91007,91001,'2026-10-01',1,30);
INSERT INTO hotel.promotions(id,code,name,kind,amount,starts_at,ends_at) OVERRIDING SYSTEM VALUE VALUES
 (91001,'REPORT-PROMO','Test promotion','FIXED',10,'2026-01-01','2027-01-01');
UPDATE hotel.reservations SET promotion_id=91001 WHERE id IN (91001,91002);
INSERT INTO hotel.promotion_usages(promotion_id,reservation_id,customer_id,discount_amount,used_at,reversed_at) VALUES
 (91001,91001,91001,10,'2026-10-01 00:00:00+07',NULL),
 (91001,91002,91001,5,'2026-10-01 00:00:00+07','2026-10-02 00:00:00+07');
