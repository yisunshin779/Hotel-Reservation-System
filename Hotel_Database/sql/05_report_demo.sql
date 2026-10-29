-- Demo data only: run in the dedicated hotel_report_demo database after 01_schema and 03_seed.
-- Historical invoice snapshots for exercising the report APIs; not production bookings.
BEGIN;
SET search_path=hotel,public;
INSERT INTO reservations(code,branch_id,customer_id,check_in,check_out,status,room_subtotal,discount_amount,quoted_total)
SELECT v.code,b.id,1,v.arrival,v.arrival+2,v.status::reservation_status,v.room,v.discount,v.room-v.discount
FROM (VALUES
 ('REPORT-DEMO-HN-1','Hà Nội Central',DATE '2026-09-29','CHECKED_OUT',2700000,100000),
 ('REPORT-DEMO-HN-2','Hà Nội Central',DATE '2026-10-03','CHECKED_OUT',4500000,250000),
 ('REPORT-DEMO-DN-1','Đà Nẵng Riverside',DATE '2026-10-08','CHECKED_OUT',1800000,0),
 ('REPORT-DEMO-DN-2','Đà Nẵng Riverside',DATE '2026-10-13','CHECKED_OUT',3000000,150000),
 ('REPORT-DEMO-SG-1','Sài Gòn Grand',DATE '2026-10-18','CHECKED_OUT',5800000,400000),
 ('REPORT-DEMO-SG-2','Sài Gòn Grand',DATE '2026-10-25','CHECKED_OUT',2900000,100000),
 ('REPORT-DEMO-DRAFT','Hà Nội Central',DATE '2026-10-26','CONFIRMED',100000000,0),
 ('REPORT-DEMO-SEPT','Hà Nội Central',DATE '2026-09-28','CHECKED_OUT',900000,0)
) AS v(code,branch_name,arrival,status,room,discount)
JOIN branches b ON b.name=v.branch_name;

INSERT INTO invoices(reservation_id,invoice_number,room_amount,service_amount,extras_amount,
 discount_amount,tax_amount,total,status,closed_at)
SELECT r.id,v.code,v.room,v.service,v.extra,v.discount,0,
 v.room+v.service+v.extra-v.discount,v.status::invoice_status,v.closed_at
FROM (VALUES
 ('REPORT-DEMO-HN-1',2700000,150000,0,100000,'CLOSED',TIMESTAMPTZ '2026-10-01 12:00:00+07'),
 ('REPORT-DEMO-HN-2',4500000,300000,200000,250000,'CLOSED',TIMESTAMPTZ '2026-10-05 12:00:00+07'),
 ('REPORT-DEMO-DN-1',1800000,100000,0,0,'CLOSED',TIMESTAMPTZ '2026-10-10 12:00:00+07'),
 ('REPORT-DEMO-DN-2',3000000,250000,50000,150000,'CLOSED',TIMESTAMPTZ '2026-10-15 12:00:00+07'),
 ('REPORT-DEMO-SG-1',5800000,500000,100000,400000,'CLOSED',TIMESTAMPTZ '2026-10-20 12:00:00+07'),
 ('REPORT-DEMO-SG-2',2900000,200000,0,100000,'CLOSED',TIMESTAMPTZ '2026-10-27 12:00:00+07'),
 ('REPORT-DEMO-DRAFT',100000000,0,0,0,'DRAFT',NULL),
 ('REPORT-DEMO-SEPT',900000,0,0,0,'CLOSED',TIMESTAMPTZ '2026-09-30 12:00:00+07')
) AS v(code,room,service,extra,discount,status,closed_at)
JOIN reservations r ON r.code=v.code;
COMMIT;
