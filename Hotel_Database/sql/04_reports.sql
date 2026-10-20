SET search_path=hotel,public;
-- Available room types on an intended hotel-local stay date.
-- Supply :branch_id, :check_in, :check_out using your SQL client.
SELECT rt.id,rt.name,min(hotel.available_count(rt.id,d::date)) AS min_available_nightly
FROM hotel.room_types rt CROSS JOIN LATERAL generate_series(date '2026-12-01',date '2026-12-03',interval '1 day') d
WHERE rt.branch_id=1 AND rt.active
GROUP BY rt.id,rt.name ORDER BY rt.name;

-- Reservations of a customer
SELECT b.code,b.check_in,b.check_out,b.status,b.quoted_total
FROM hotel.reservations b WHERE b.customer_id=1 ORDER BY b.created_at DESC;

-- Revenue by branch (closed invoices only, minus completed refunds)
SELECT br.name,coalesce(sum(i.total),0) - coalesce((SELECT sum(f.amount) FROM hotel.refunds f
 JOIN hotel.reservations r ON r.id=f.reservation_id WHERE r.branch_id=br.id AND f.status='DONE'),0) AS net_revenue
FROM hotel.branches br LEFT JOIN hotel.reservations b ON b.branch_id=br.id
LEFT JOIN hotel.invoices i ON i.reservation_id=b.id AND i.status='CLOSED'
GROUP BY br.id,br.name ORDER BY br.id;

-- Folio before checkout
SELECT * FROM hotel.folio_preview(1,0);
