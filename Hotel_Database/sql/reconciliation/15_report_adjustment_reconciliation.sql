-- #15 Read-only reconciliation of revenue report totals against invoice adjustments.
-- Run on a restored copy: psql -v from=2026-10-01 -v to=2026-11-01 -f 15_report_adjustment_reconciliation.sql
-- The revenue report (ReportRepository.MONEY_EVENTS) counts CLOSED invoices by closed_at and DONE refunds
-- by completed_at, in Asia/Ho_Chi_Minh days. It does not read hotel.invoice_adjustments.
-- Nothing here changes data. The questions to settle before changing the report are listed at the end.
BEGIN READ ONLY;
\if :{?from}
\else
  \set from '2000-01-01'
\endif
\if :{?to}
\else
  \set to '2100-01-01'
\endif

-- 1. Per branch and local day: report total, adjustments issued that day, and adjustments of invoices closed that day.
WITH bounds AS (
  SELECT (:'from'::date)::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh' AS start_at,
         (:'to'::date)::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh' AS end_at
), invoices_in AS (
  SELECT r.branch_id, (i.closed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day, i.id, i.total
  FROM hotel.invoices i JOIN hotel.reservations r ON r.id = i.reservation_id, bounds
  WHERE i.status = 'CLOSED' AND i.closed_at >= bounds.start_at AND i.closed_at < bounds.end_at
), adjustments_issued AS (
  SELECT r.branch_id, (a.issued_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,
         count(*) AS issued_count, sum(a.total) AS issued_delta
  FROM hotel.invoice_adjustments a JOIN hotel.reservations r ON r.id = a.reservation_id, bounds
  WHERE a.issued_at >= bounds.start_at AND a.issued_at < bounds.end_at
  GROUP BY 1, 2
), adjustments_by_close_day AS (
  SELECT i.branch_id, i.day, sum(a.total) AS delta_on_close_day
  FROM invoices_in i JOIN hotel.invoice_adjustments a ON a.invoice_id = i.id
  GROUP BY 1, 2
), report AS (
  SELECT branch_id, day, count(*) AS closed_invoices, sum(total) AS report_invoice_total
  FROM invoices_in GROUP BY 1, 2
)
SELECT coalesce(r.branch_id, x.branch_id) AS branch_id, coalesce(r.day, x.day) AS day,
       coalesce(r.closed_invoices, 0) AS closed_invoices,
       coalesce(r.report_invoice_total, 0) AS report_invoice_total,
       coalesce(x.issued_count, 0) AS adjustments_issued,
       coalesce(x.issued_delta, 0) AS delta_if_recognised_on_issue_day,
       coalesce(c.delta_on_close_day, 0) AS delta_if_recognised_on_close_day
FROM report r
FULL JOIN adjustments_issued x ON x.branch_id = r.branch_id AND x.day = r.day
LEFT JOIN adjustments_by_close_day c ON c.branch_id = coalesce(r.branch_id, x.branch_id) AND c.day = coalesce(r.day, x.day)
ORDER BY 1, 2;

-- 2. Adjusted invoices that also have refunds: candidates for double counting if both reduce revenue.
SELECT a.invoice_id, a.adjustment_number, a.total AS adjustment_delta, a.issued_at,
       f.id AS refund_id, f.amount AS refund_amount, f.status AS refund_status, f.completed_at, f.reason
FROM hotel.invoice_adjustments a
JOIN hotel.refunds f ON f.reservation_id = a.reservation_id
ORDER BY a.invoice_id, a.issued_at, f.id;

-- 3. Adjustments whose issue day falls in another month than the invoice close day (period choice matters).
SELECT a.adjustment_number, a.invoice_id, i.closed_at, a.issued_at, a.total
FROM hotel.invoice_adjustments a JOIN hotel.invoices i ON i.id = a.invoice_id
WHERE date_trunc('month', i.closed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')
   <> date_trunc('month', a.issued_at AT TIME ZONE 'Asia/Ho_Chi_Minh')
ORDER BY a.issued_at;

-- 4. Lines by type, to see which report columns an adjustment would move (room, service, extra, discount, tax).
SELECT line->>'type' AS line_type, count(*) AS lines, sum((line->>'amount')::numeric) AS amount
FROM hotel.invoice_adjustments a, jsonb_array_elements(a.lines) line
GROUP BY 1 ORDER BY 1;
COMMIT;

-- Questions to settle before the report reads adjustments:
--  a. Recognition day: issued_at of the adjustment, or closed_at of the original invoice (restating a closed day)?
--  b. Sign and columns: is a negative EXTRA line a revenue reduction in extrasAmount, or a separate adjustment column?
--  c. Tax: are adjustment amounts tax-inclusive, and must taxAmount be recomputed?
--  d. Branch: always the reservation's branch, also when the adjustment is issued by another branch's Admin?
--  e. Refund interplay: when an adjustment reduces the invoice and a refund returns the same money, which one
--     reduces revenue so the amount is not deducted twice?
