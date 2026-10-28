-- Optional UC28 demo extension. Safe to re-run in the dedicated demo database only.
BEGIN;
DO $$ BEGIN
 IF current_database()<>'hotel_report_demo' THEN RAISE EXCEPTION 'Use hotel_report_demo only'; END IF;
END $$;
SET search_path=hotel,public;
INSERT INTO reservations(code,branch_id,customer_id,check_in,check_out,status,hold_expires_at,room_subtotal,quoted_total)
SELECT v.code,b.id,1,DATE '2026-10-22',DATE '2026-10-24',v.status::reservation_status,
 CASE WHEN v.status='PENDING_PAYMENT' THEN TIMESTAMPTZ '2026-10-20 12:15+07' ELSE NULL END,1800000,1800000
FROM (VALUES ('REPORT-DEMO-CANCELLED','CANCELLED'),('REPORT-DEMO-NOSHOW','NO_SHOW'),
 ('REPORT-DEMO-PENDING','PENDING_PAYMENT')) v(code,status)
JOIN branches b ON b.name='Hà Nội Central' ON CONFLICT(code) DO NOTHING;
UPDATE reservations SET created_at=(check_in-3)::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh'
 WHERE code LIKE 'REPORT-DEMO-%';
INSERT INTO reservation_details(reservation_id,room_type_id,quantity,nightly_average,subtotal)
SELECT r.id,t.id,1,r.room_subtotal/(r.check_out-r.check_in),r.room_subtotal
FROM reservations r JOIN room_types t ON t.branch_id=r.branch_id AND t.name=
 CASE WHEN r.code LIKE 'REPORT-DEMO-SG-%' THEN 'Suite' ELSE 'Standard' END
WHERE r.code LIKE 'REPORT-DEMO-%' ON CONFLICT(reservation_id,room_type_id) DO NOTHING;
INSERT INTO reservation_nights(reservation_detail_id,room_type_id,stay_date,quantity,nightly_price)
SELECT d.id,d.room_type_id,day::date,d.quantity,d.nightly_average
FROM reservation_details d JOIN reservations r ON r.id=d.reservation_id
CROSS JOIN LATERAL generate_series(r.check_in::timestamp,(r.check_out-1)::timestamp,interval '1 day') day
WHERE r.code LIKE 'REPORT-DEMO-%' ON CONFLICT DO NOTHING;
UPDATE reservations SET promotion_id=(SELECT id FROM promotions WHERE code='WELCOME10')
 WHERE code LIKE 'REPORT-DEMO-%' AND discount_amount>0;
INSERT INTO promotion_usages(promotion_id,reservation_id,customer_id,discount_amount,used_at)
SELECT promotion_id,id,customer_id,discount_amount,created_at FROM reservations
WHERE code LIKE 'REPORT-DEMO-%' AND promotion_id IS NOT NULL ON CONFLICT(reservation_id) DO NOTHING;
INSERT INTO payments(reservation_id,invoice_id,amount,method,status,obligation_key,paid_at)
SELECT r.id,i.id,i.total,'CASH','PAID','REPORT-DEMO-PAY-'||r.code,i.closed_at
FROM reservations r JOIN invoices i ON i.reservation_id=r.id
WHERE r.code LIKE 'REPORT-DEMO-%' AND i.status='CLOSED' ON CONFLICT(obligation_key) DO NOTHING;
INSERT INTO refunds(payment_id,reservation_id,amount,status,idempotency_key,completed_at)
SELECT p.id,p.reservation_id,v.amount,'DONE',v.key,v.done
FROM (VALUES ('REPORT-DEMO-REFUND-1',150000,TIMESTAMPTZ '2026-10-12 12:00+07'),
 ('REPORT-DEMO-REFUND-2',50000,TIMESTAMPTZ '2026-10-13 12:00+07')) v(key,amount,done)
JOIN payments p ON p.obligation_key='REPORT-DEMO-PAY-REPORT-DEMO-HN-1'
ON CONFLICT(idempotency_key) DO NOTHING;
INSERT INTO room_maintenance(room_id,starts_on,ends_on,reason)
SELECT min(id),DATE '2026-10-08',DATE '2026-10-11','REPORT-DEMO-MAINTENANCE'
FROM rooms WHERE branch_id=(SELECT id FROM branches WHERE name='Hà Nội Central')
HAVING NOT EXISTS(SELECT 1 FROM room_maintenance WHERE reason='REPORT-DEMO-MAINTENANCE');
COMMIT;
