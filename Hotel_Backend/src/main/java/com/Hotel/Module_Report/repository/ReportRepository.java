package com.Hotel.Module_Report.repository;

import com.Hotel.Module_Report.dto.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class ReportRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public ReportRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String,Object>> branches() {
        return jdbc.queryForList("SELECT id,name FROM hotel.branches ORDER BY id", Map.of());
    }
    public List<Map<String,Object>> roomTypes(List<Long> branches) {
        if (branches.isEmpty()) return List.of();
        return jdbc.queryForList("SELECT id,branch_id AS \"branchId\",name FROM hotel.room_types WHERE branch_id IN (:branches) ORDER BY branch_id,id",
                Map.of("branches",branches));
    }
    private MapSqlParameterSource params(ReportFilter f) {
        return new MapSqlParameterSource().addValue("branches",f.branchIds())
                .addValue("types", f.roomTypeIds().isEmpty() ? List.of(-1L) : f.roomTypeIds())
                .addValue("filterTypes",!f.roomTypeIds().isEmpty())
                .addValue("from",f.from()).addValue("to",f.to())
                .addValue("start",f.from().atStartOfDay(ZONE).toOffsetDateTime())
                .addValue("end",f.to().plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
    }
    // One row per reservation. Selected room types receive a share of booking-level money.
    private static final String SCOPED = """
            WITH scoped AS (
              SELECT r.*,coalesce(r.promotion_id,pu.promotion_id) AS report_promotion_id,
                (NOT :filterTypes OR coalesce(w.selected_qty,0)>0) AS matches_type,
                CASE WHEN NOT :filterTypes THEN 1::numeric
                     WHEN w.total_price>0 THEN w.selected_price/w.total_price
                     WHEN w.total_qty>0 THEN w.selected_qty/w.total_qty ELSE 0::numeric END AS allocation
              FROM hotel.reservations r
              LEFT JOIN hotel.promotion_usages pu ON pu.reservation_id=r.id
              LEFT JOIN LATERAL (
                SELECT sum(d.subtotal) AS total_price,
                       coalesce(sum(d.subtotal) FILTER (WHERE d.room_type_id IN (:types)),0) AS selected_price,
                       sum(d.quantity)::numeric AS total_qty,
                       coalesce(sum(d.quantity) FILTER (WHERE d.room_type_id IN (:types)),0)::numeric AS selected_qty
                FROM hotel.reservation_details d WHERE d.reservation_id=r.id
              ) w ON true WHERE r.branch_id IN (:branches)
            )
            """;
    private static final String MONEY_EVENTS = """
            , money_events AS (
              SELECT (i.closed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,r.branch_id,r.report_promotion_id,
                1::numeric AS "closedInvoiceCount",0::numeric AS "refundCount",
                round(i.room_amount*r.allocation,2) AS "roomAmount",
                round(i.service_amount*r.allocation,2) AS "serviceAmount",
                round(i.extras_amount*r.allocation,2) AS "extrasAmount",
                round(i.discount_amount*r.allocation,2) AS "discountAmount",
                round(i.tax_amount*r.allocation,2) AS "taxAmount",
                round(i.total*r.allocation,2) AS "invoiceTotal",0::numeric AS "refundTotal"
              FROM hotel.invoices i JOIN scoped r ON r.id=i.reservation_id
              WHERE i.status='CLOSED' AND i.closed_at>=:start AND i.closed_at<:end AND r.matches_type
              UNION ALL
              SELECT (f.completed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date,r.branch_id,r.report_promotion_id,
                0,1,0,0,0,0,0,0,round(f.amount*r.allocation,2)
              FROM hotel.refunds f JOIN scoped r ON r.id=f.reservation_id
              WHERE f.status='DONE' AND f.completed_at>=:start AND f.completed_at<:end AND r.matches_type
            )
            """;
    public static final List<String> REVENUE_METRICS = List.of("closedInvoiceCount","refundCount","roomAmount","serviceAmount",
            "extrasAmount","discountAmount","taxAmount","invoiceTotal","refundTotal");
    public static final List<String> RESERVATION_METRICS = List.of("totalReservations","pendingPaymentCount","confirmedCount",
            "checkedInCount","checkedOutCount","cancelledCount","noShowCount");
    public static final List<String> OCCUPANCY_METRICS = List.of("soldRoomNights","availableRoomNights");
    public static final List<String> PROMOTION_METRICS = List.of("usageCount","reversedUsageCount","discountTotal",
            "closedInvoiceCount","refundCount","invoiceTotal","refundTotal");

    private List<ReportFact> facts(String sql, ReportFilter f, List<String> metrics) {
        if (f.branchIds().isEmpty()) return List.of();
        return jdbc.query(sql,params(f),(rs,n) -> fact(rs,metrics));
    }
    private ReportFact fact(ResultSet rs,List<String> keys) throws SQLException {
        Map<String,BigDecimal> values = new LinkedHashMap<>();
        for (String key:keys) values.put(key,rs.getBigDecimal(key));
        return new ReportFact(rs.getDate("day").toLocalDate(),rs.getLong("branch_id"),rs.getString("branch_name"),
                rs.getObject("promotion_id",Long.class),rs.getString("promotion_code"),rs.getString("promotion_name"),values);
    }
    private static final String DIMENSIONS = """
             JOIN hotel.branches b ON b.id=e.branch_id
             LEFT JOIN hotel.promotions p ON p.id=e.report_promotion_id
            """;
    public List<ReportFact> revenue(ReportFilter f) {
        return facts(SCOPED+MONEY_EVENTS+"""
                SELECT e.*,b.name AS branch_name,p.id AS promotion_id,p.code AS promotion_code,p.name AS promotion_name
                FROM money_events e
                """+DIMENSIONS,f,REVENUE_METRICS);
    }
    public List<ReportFact> reservations(ReportFilter f) {
        return facts(SCOPED+"""
                SELECT (r.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,r.branch_id,b.name AS branch_name,
                  NULL::bigint AS promotion_id,NULL::text AS promotion_code,NULL::text AS promotion_name,
                  count(*)::numeric AS "totalReservations",
                  count(*) FILTER(WHERE r.status='PENDING_PAYMENT')::numeric AS "pendingPaymentCount",
                  count(*) FILTER(WHERE r.status='CONFIRMED')::numeric AS "confirmedCount",
                  count(*) FILTER(WHERE r.status='CHECKED_IN')::numeric AS "checkedInCount",
                  count(*) FILTER(WHERE r.status='CHECKED_OUT')::numeric AS "checkedOutCount",
                  count(*) FILTER(WHERE r.status='CANCELLED')::numeric AS "cancelledCount",
                  count(*) FILTER(WHERE r.status='NO_SHOW')::numeric AS "noShowCount"
                FROM scoped r JOIN hotel.branches b ON b.id=r.branch_id
                WHERE r.created_at>=:start AND r.created_at<:end AND r.matches_type
                GROUP BY 1,2,3
                """,f,RESERVATION_METRICS);
    }
    public List<ReportFact> occupancy(ReportFilter f) {
        return facts("""
                WITH days AS (
                  SELECT generate_series(CAST(:from AS timestamp),CAST(:to AS timestamp),interval '1 day')::date AS day
                ), capacity AS (
                  SELECT d.day,r.branch_id,count(*)::numeric AS available
                  FROM days d CROSS JOIN hotel.rooms r
                  WHERE r.branch_id IN (:branches) AND r.active
                    AND (r.deleted_at IS NULL OR (r.deleted_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date>d.day)
                    AND (NOT :filterTypes OR r.room_type_id IN (:types))
                    AND NOT EXISTS(SELECT 1 FROM hotel.room_maintenance m WHERE m.room_id=r.id
                      AND (m.active OR m.closed_at IS NOT NULL) AND m.starts_on<=d.day AND m.ends_on>d.day)
                  GROUP BY 1,2
                ), sold AS (
                  SELECT n.stay_date AS day,r.branch_id,sum(n.quantity)::numeric AS sold
                  FROM hotel.reservation_nights n JOIN hotel.reservation_details rd ON rd.id=n.reservation_detail_id
                  JOIN hotel.reservations r ON r.id=rd.reservation_id
                  WHERE r.branch_id IN (:branches) AND n.stay_date BETWEEN :from AND :to
                    AND r.status IN ('CONFIRMED','CHECKED_IN','CHECKED_OUT')
                    AND (NOT :filterTypes OR n.room_type_id IN (:types)) GROUP BY 1,2
                )
                SELECT coalesce(c.day,s.day) AS day,coalesce(c.branch_id,s.branch_id) AS branch_id,b.name AS branch_name,
                  NULL::bigint AS promotion_id,NULL::text AS promotion_code,NULL::text AS promotion_name,
                  coalesce(s.sold,0) AS "soldRoomNights",coalesce(c.available,0) AS "availableRoomNights"
                FROM capacity c FULL OUTER JOIN sold s ON s.day=c.day AND s.branch_id=c.branch_id
                JOIN hotel.branches b ON b.id=coalesce(c.branch_id,s.branch_id)
                """,f,OCCUPANCY_METRICS);
    }
    public List<ReportFact> promotions(ReportFilter f) {
        return facts(SCOPED+MONEY_EVENTS+"""
                , promotion_events AS (
                  SELECT (u.used_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,r.branch_id,u.promotion_id AS report_promotion_id,
                    CASE WHEN u.reversed_at IS NULL THEN 1 ELSE 0 END::numeric AS "usageCount",
                    CASE WHEN u.reversed_at IS NOT NULL THEN 1 ELSE 0 END::numeric AS "reversedUsageCount",
                    CASE WHEN u.reversed_at IS NULL THEN round(u.discount_amount*r.allocation,2) ELSE 0 END AS "discountTotal",
                    0::numeric AS "closedInvoiceCount",0::numeric AS "refundCount",0::numeric AS "invoiceTotal",0::numeric AS "refundTotal"
                  FROM hotel.promotion_usages u JOIN scoped r ON r.id=u.reservation_id
                  WHERE u.used_at>=:start AND u.used_at<:end AND r.matches_type
                  UNION ALL
                  SELECT day,branch_id,report_promotion_id,0,0,0,"closedInvoiceCount","refundCount","invoiceTotal","refundTotal"
                  FROM money_events WHERE report_promotion_id IS NOT NULL
                )
                SELECT e.*,b.name AS branch_name,p.id AS promotion_id,p.code AS promotion_code,p.name AS promotion_name
                FROM promotion_events e
                """+DIMENSIONS,f,PROMOTION_METRICS);
    }
}
