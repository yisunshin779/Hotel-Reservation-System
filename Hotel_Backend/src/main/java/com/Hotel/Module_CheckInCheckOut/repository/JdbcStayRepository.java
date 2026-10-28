package com.Hotel.Module_CheckInCheckOut.repository;

import com.Hotel.Module_CheckInCheckOut.entity.Bill;
import com.Hotel.Module_CheckInCheckOut.entity.BillingPolicy;
import com.Hotel.Module_CheckInCheckOut.entity.Invoice;
import com.Hotel.Module_CheckInCheckOut.entity.InvoiceLine;
import com.Hotel.Module_CheckInCheckOut.entity.NightlyRate;
import com.Hotel.Module_CheckInCheckOut.entity.Payment;
import com.Hotel.Module_CheckInCheckOut.entity.Receptionist;
import com.Hotel.Module_CheckInCheckOut.entity.Refund;
import com.Hotel.Module_CheckInCheckOut.entity.Reservation;
import com.Hotel.Module_CheckInCheckOut.entity.ReservationDetail;
import com.Hotel.Module_CheckInCheckOut.entity.ReservationStatus;
import com.Hotel.Module_CheckInCheckOut.entity.Room;
import com.Hotel.Module_CheckInCheckOut.entity.RoomAssignment;
import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import com.Hotel.Module_CheckInCheckOut.entity.StayGuest;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStayRepository implements StayRepository {
    private final JdbcTemplate jdbc;
    public JdbcStayRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp t = rs.getTimestamp(name); return t == null ? null : t.toInstant();
    }
    private Timestamp ts(Instant t) { return Timestamp.from(t); }
    private static final String ROOM_FIELDS = """
        SELECT r.id,r.branch_id,r.room_type_id,r.room_number,r.occupancy,r.cleaning,r.floor,r.view_direction,
          (r.active AND r.deleted_at IS NULL AND rt.active AND rt.deleted_at IS NULL) AS active,
          EXISTS(SELECT 1 FROM hotel.room_maintenance m WHERE m.room_id=r.id AND m.active
                 AND m.starts_on < ? AND m.ends_on > ?) AS maintained,
          EXISTS(SELECT 1 FROM hotel.room_assignments a WHERE a.room_id=r.id AND a.released_at IS NULL) AS assigned
        FROM hotel.rooms r JOIN hotel.room_types rt ON rt.id=r.room_type_id
        """;
    private final RowMapper<Room> roomMapper = (rs, row) -> new Room(rs.getLong("id"), rs.getLong("branch_id"),
        rs.getLong("room_type_id"), rs.getString("room_number"), rs.getString("occupancy"), rs.getString("cleaning"),
        rs.getBoolean("active"), rs.getBoolean("maintained"), rs.getBoolean("assigned"),rs.getInt("floor"),rs.getString("view_direction"));
    @Override public Optional<Receptionist> actorByEmail(String email) {
        return jdbc.query("""
            SELECT u.id,u.branch_id,r.code FROM hotel.users u JOIN hotel.roles r ON r.id=u.role_id
            JOIN hotel.branches b ON b.id=u.branch_id
            WHERE u.email=? AND u.active AND u.deleted_at IS NULL AND b.active AND b.deleted_at IS NULL
              AND (u.locked_until IS NULL OR u.locked_until <= clock_timestamp())
            """, (rs, row) -> new Receptionist(rs.getLong(1), rs.getLong(2), rs.getString(3)), email).stream().findFirst();
    }
    @Override public Reservation reservation(long id, boolean lock) {
        return jdbc.query("""
            SELECT r.*,b.timezone FROM hotel.reservations r JOIN hotel.branches b ON b.id=r.branch_id
            WHERE r.id=?
            """ + (lock ? " FOR UPDATE OF r" : ""), (rs, row) -> new Reservation(rs.getLong("id"),
            rs.getLong("branch_id"), rs.getLong("customer_id"), rs.getString("code"), ReservationStatus.valueOf(rs.getString("status")),
            rs.getDate("check_in").toLocalDate(), rs.getDate("check_out").toLocalDate(), rs.getString("timezone"),
            rs.getBigDecimal("room_subtotal"), rs.getBigDecimal("discount_amount"), rs.getBigDecimal("quoted_total"),
            rs.getTimestamp("hold_expires_at") != null, instant(rs,"actual_checkin"), instant(rs,"actual_checkout"),
            rs.getLong("version")), id).stream().findFirst().orElseThrow(() -> new StayException(404,"RESERVATION_NOT_FOUND","Không tìm thấy đơn."));
    }
    @Override public List<ReservationDetail> details(long id) {
        return jdbc.query("SELECT id,room_type_id,quantity FROM hotel.reservation_details WHERE reservation_id=? ORDER BY room_type_id",
            (rs, row) -> new ReservationDetail(rs.getLong(1),rs.getLong(2),rs.getInt(3)), id);
    }
    @Override public Room lockRoom(long id, Reservation r) {
        return lockRoom(id,r,r.checkIn());
    }
    @Override public Room lockRoom(long id, Reservation r, LocalDate from) {
        // Separate statements: after waiting for a row lock, the next READ COMMITTED statement sees
        // the committed assignment/maintenance. Check-in also holds the room-type advisory lock.
        var rows = jdbc.queryForList("SELECT id FROM hotel.rooms WHERE id=? FOR UPDATE", Long.class, id);
        if (rows.isEmpty()) throw new StayException(404,"ROOM_NOT_FOUND","Không tìm thấy phòng.");
        return jdbc.queryForObject(ROOM_FIELDS + " WHERE r.id=?",roomMapper,r.checkOut(),from,id);
    }
    @Override public List<Long> assignedRoomIds(long id) {
        return jdbc.queryForList("""
            SELECT a.room_id FROM hotel.room_assignments a JOIN hotel.reservation_details d ON d.id=a.reservation_detail_id
            WHERE d.reservation_id=? AND a.released_at IS NULL ORDER BY a.room_id
            """,Long.class,id);
    }
    @Override public BigDecimal paid(long id) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM hotel.payments WHERE reservation_id=? AND status='PAID'",BigDecimal.class,id);
    }
    @Override public BigDecimal refunded(long id) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM hotel.refunds WHERE reservation_id=? AND status='DONE'",BigDecimal.class,id);
    }
    @Override public List<InvoiceLine> serviceLines(long id) {
        return jdbc.query("""
            SELECT s.name,u.quantity,u.unit_price,u.quantity*u.unit_price AS amount
            FROM hotel.service_usages u JOIN hotel.services s ON s.id=u.service_id
            WHERE u.reservation_id=? AND u.voided_at IS NULL ORDER BY u.id
            """,(rs,row) -> new InvoiceLine("SERVICE",rs.getString(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getBigDecimal(4)),id);
    }
    @Override public List<InvoiceLine> extraLines(long id) {
        return jdbc.query("SELECT description,amount FROM hotel.extra_charges WHERE reservation_id=? ORDER BY id",
            (rs,row) -> new InvoiceLine("EXTRA",rs.getString(1),BigDecimal.ONE,rs.getBigDecimal(2),rs.getBigDecimal(2)),id);
    }
    @Override public void assign(long detailId, Room room, Instant now) {
        Long assignmentId=jdbc.queryForObject("""
            INSERT INTO hotel.room_assignments(reservation_detail_id,room_type_id,room_id,assigned_at,arrival_at)
            VALUES(?,?,?,?,?) RETURNING id
            """,Long.class,detailId,room.roomTypeId(),room.id(),ts(now),ts(now));
        jdbc.update("""
            INSERT INTO hotel.assignment_nightly_rates(assignment_id,stay_date,nightly_price)
            SELECT ?,stay_date,nightly_price FROM hotel.reservation_nights WHERE reservation_detail_id=?
            """,assignmentId,detailId);
        jdbc.update("UPDATE hotel.rooms SET occupancy='OCCUPIED',version=version+1 WHERE id=?",room.id());
    }
    @Override public void markCheckedIn(long id, Instant now) {
        jdbc.update("UPDATE hotel.reservations SET status='CHECKED_IN',actual_checkin=?,updated_at=?,version=version+1 WHERE id=?",ts(now),ts(now),id);
    }
    @Override public void audit(long actor, long id, String action, String data) {
        jdbc.update("INSERT INTO hotel.audit_logs(actor_id,action,entity_name,entity_id,after_data) VALUES(?,?,'reservations',?,?::jsonb)",actor,action,id,data);
    }
    @Override public boolean recordPayment(long id, BigDecimal amount, String method, String key, long actor, Instant now) {
        // A globally unique obligation key is shared by UC08/UC15/UC19. The reservation row is
        // already locked. Pending/unpaid obligations can be settled once, paid ones replay safely.
        var existing = jdbc.queryForList("SELECT * FROM hotel.payments WHERE obligation_key=? FOR UPDATE",key);
        if (!existing.isEmpty()) {
            var p = existing.get(0);
            if (((Number)p.get("reservation_id")).longValue() != id
                    || ((BigDecimal)p.get("amount")).compareTo(amount) != 0 || !p.get("method").toString().equals(method))
                throw StayException.conflict("PAYMENT_KEY_CONFLICT","Khóa đã thuộc khoản thanh toán khác.");
            String status = p.get("status").toString();
            if (status.equals("PAID")) return false;
            if (!Set.of("UNPAID","PENDING").contains(status))
                throw StayException.conflict("PAYMENT_STATE_CONFLICT","Khoản thanh toán không thể ghi nhận lại.");
            jdbc.update("UPDATE hotel.payments SET status='PAID',paid_at=?,recorded_by=? WHERE obligation_key=?",ts(now),actor,key);
            return true;
        }
        jdbc.update("""
            INSERT INTO hotel.payments(reservation_id,amount,method,status,obligation_key,paid_at,recorded_by)
            VALUES(?,?,?::hotel.payment_method,'PAID',?,?,?)
            """,id,amount,method,key,ts(now),actor);
        return true;
    }
    @Override public Invoice closeInvoice(Reservation r, Bill bill, String number, Instant now) {
        var drafts=jdbc.queryForList("SELECT id,invoice_number,status::text FROM hotel.invoices WHERE reservation_id=? FOR UPDATE",r.id());
        Long invoiceId;
        if (drafts.isEmpty()) {
            invoiceId = jdbc.queryForObject("""
            INSERT INTO hotel.invoices(reservation_id,invoice_number,room_amount,service_amount,extras_amount,
                discount_amount,tax_amount,total,status,issued_at,tax_rate)
            VALUES(?,?,?,?,?,?,?,?,'DRAFT',?,?) RETURNING id
            """,Long.class,r.id(),number,bill.roomAmount(),bill.serviceAmount(),bill.surchargeAmount(),bill.discountAmount(),bill.taxAmount(),bill.grossAmount(),ts(now),bill.taxRate());
        } else {
            var draft=drafts.get(0);
            if (!"DRAFT".equals(draft.get("status"))) throw StayException.conflict("INVOICE_STATE_CONFLICT","Hóa đơn hiện tại không thể đóng lại.");
            invoiceId=((Number)draft.get("id")).longValue();
            number=draft.get("invoice_number").toString();
            jdbc.update("""
                UPDATE hotel.invoices SET room_amount=?,service_amount=?,extras_amount=?,discount_amount=?,
                  tax_amount=?,tax_rate=?,total=?,issued_at=? WHERE id=?
                """,bill.roomAmount(),bill.serviceAmount(),bill.surchargeAmount(),bill.discountAmount(),bill.taxAmount(),bill.taxRate(),bill.grossAmount(),ts(now),invoiceId);
            jdbc.update("DELETE FROM hotel.invoice_lines WHERE invoice_id=?",invoiceId);
        }
        for (var line : bill.lines()) jdbc.update("""
            INSERT INTO hotel.invoice_lines(invoice_id,line_type,description,quantity,unit_price,amount) VALUES(?,?,?,?,?,?)
            """,invoiceId,line.type(),line.description(),line.quantity(),line.unitPrice(),line.amount());
        jdbc.update("UPDATE hotel.payments SET invoice_id=? WHERE reservation_id=? AND invoice_id IS NULL",invoiceId,r.id());
        jdbc.update("UPDATE hotel.invoices SET status='CLOSED',closed_at=? WHERE id=?",ts(now),invoiceId);
        return new Invoice(invoiceId,r.id(),number,"CLOSED",now,bill.grossAmount(),bill.lines());
    }
    @Override public void release(long id, Instant now) {
        var roomIds = assignedRoomIds(id);
        for (long room : roomIds) jdbc.queryForList("SELECT id FROM hotel.rooms WHERE id=? FOR UPDATE",Long.class,room);
        for (long room : roomIds) jdbc.update("UPDATE hotel.rooms SET occupancy='VACANT',cleaning='DIRTY',version=version+1 WHERE id=?",room);
        jdbc.update("""
            UPDATE hotel.room_assignments SET released_at=?,departure_at=? WHERE released_at IS NULL
            AND reservation_detail_id IN (SELECT id FROM hotel.reservation_details WHERE reservation_id=?)
            """,ts(now),ts(now),id);
        jdbc.update("UPDATE hotel.reservations SET status='CHECKED_OUT',actual_checkout=?,updated_at=?,version=version+1 WHERE id=?",ts(now),ts(now),id);
    }
    @Override public Invoice invoice(long id) {
        var values = jdbc.query("SELECT * FROM hotel.invoices WHERE reservation_id=? AND status='CLOSED'",(rs,row) ->
            new Invoice(rs.getLong("id"),id,rs.getString("invoice_number"),rs.getString("status"),instant(rs,"issued_at"),
                rs.getBigDecimal("total"),List.of()),id);
        if (values.isEmpty()) throw new StayException(404,"INVOICE_NOT_FOUND","Chưa có hóa đơn đã đóng.");
        var value = values.get(0);
        var lines = jdbc.query("SELECT * FROM hotel.invoice_lines WHERE invoice_id=? ORDER BY id",(rs,row) ->
            new InvoiceLine(rs.getString("line_type"),rs.getString("description"),rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"),rs.getBigDecimal("amount")),value.id());
        return new Invoice(value.id(),id,value.number(),value.status(),value.issuedAt(),value.total(),lines);
    }

    @Override public void saveGuest(long id, StayGuest guest, long actor, Instant now) {
        if (guest.customerId()!=null) {
            Long booker=jdbc.queryForObject("SELECT customer_id FROM hotel.reservations WHERE id=?",Long.class,id);
            if (!guest.customerId().equals(booker)) throw StayException.conflict("INVALID_GUEST_LINK","Chỉ liên kết người đặt khi đã xác nhận cùng người ở chính.");
            jdbc.update("UPDATE hotel.customers SET identity_document=? WHERE id=?",guest.identificationInfo(),guest.customerId());
        }
        jdbc.update("""
            INSERT INTO hotel.stay_guests(reservation_id,full_name,identity_document,customer_id,verified_by,verified_at)
            VALUES(?,?,?,?,?,?) ON CONFLICT(reservation_id) DO UPDATE SET full_name=excluded.full_name,
              identity_document=excluded.identity_document,customer_id=excluded.customer_id,
              verified_by=excluded.verified_by,verified_at=excluded.verified_at
            """,id,guest.fullName(),guest.identificationInfo(),guest.customerId(),actor,ts(now));
    }
    @Override public boolean hasGuest(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM hotel.stay_guests WHERE reservation_id=?)",Boolean.class,id));
    }
    @Override public List<Room> branchRooms(Reservation r, LocalDate from) {
        return jdbc.query("SELECT * FROM ("+ROOM_FIELDS+"""
            WHERE r.branch_id=?) candidate WHERE active AND occupancy='VACANT' AND cleaning='READY'
              AND NOT maintained AND NOT assigned ORDER BY room_type_id,room_number,id
            """,roomMapper,r.checkOut(),from,r.branchId());
    }
    @Override public void lockTypes(List<Long> types) {
        types.stream().distinct().sorted().forEach(type -> jdbc.query("SELECT pg_advisory_xact_lock(7101,?::integer)",rs->{while(rs.next()){}},type));
    }
    @Override public long roomType(long id) {
        return jdbc.queryForList("SELECT room_type_id FROM hotel.rooms WHERE id=?",Long.class,id).stream().findFirst()
            .orElseThrow(()->new StayException(404,"ROOM_NOT_FOUND","Không tìm thấy phòng."));
    }
    @Override public boolean freeUpgrade(long booked, long physical) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM hotel.room_types b JOIN hotel.room_types p ON p.branch_id=b.branch_id
              WHERE b.id=? AND p.id=? AND p.upgrade_rank>b.upgrade_rank AND p.capacity>=b.capacity)
            """,Boolean.class,booked,physical));
    }
    @Override public boolean enoughCapacity(long id, long oldType, long newType, boolean checkin) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT n.capacity>=CASE WHEN ? THEN o.capacity ELSE greatest(1,
              r.guest_count-coalesce((SELECT sum(t.capacity) FROM hotel.room_assignments a
                JOIN hotel.reservation_details d ON d.id=a.reservation_detail_id
                JOIN hotel.room_types t ON t.id=a.room_type_id
                WHERE d.reservation_id=r.id AND a.released_at IS NULL),0)+o.capacity) END
            FROM hotel.reservations r CROSS JOIN hotel.room_types o CROSS JOIN hotel.room_types n
            WHERE r.id=? AND o.id=? AND n.id=?
            """,Boolean.class,checkin,id,oldType,newType));
    }
    @Override public boolean inventoryAvailable(long type, LocalDate from, LocalDate to, int quantity) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT EXISTS(SELECT 1 FROM generate_series(?::date,(?::date-1)::timestamp,interval '1 day') d
              WHERE hotel.available_count(?,d::date)<?)
            """,Boolean.class,from,to,type,quantity));
    }
    @Override public void transferInventory(long id, long oldType, long newType, LocalDate from, LocalDate to) {
        if(oldType==newType) return;
        for(var e:Map.of(oldType,-1,newType,1).entrySet()) jdbc.update("""
            INSERT INTO hotel.stay_inventory_movements(reservation_id,room_type_id,stay_date,quantity_delta)
            SELECT ?,?,d::date,? FROM generate_series(?::date,(?::date-1)::timestamp,interval '1 day') d
            ON CONFLICT(reservation_id,room_type_id,stay_date) DO UPDATE
              SET quantity_delta=hotel.stay_inventory_movements.quantity_delta+excluded.quantity_delta
            """,id,e.getKey(),e.getValue(),from,to);
    }
    @Override public RoomAssignment assignment(long id, long room) {
        return jdbc.query("""
            SELECT a.id,a.reservation_detail_id,a.room_id,a.room_type_id FROM hotel.room_assignments a
            JOIN hotel.reservation_details d ON d.id=a.reservation_detail_id
            WHERE d.reservation_id=? AND a.room_id=? AND a.released_at IS NULL
            """,(rs,row)->new RoomAssignment(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)),id,room)
            .stream().findFirst().orElseThrow(()->StayException.conflict("ASSIGNMENT_NOT_FOUND","Phòng cũ không thuộc đơn đang lưu trú."));
    }
    @Override public List<NightlyRate> assignmentRates(long id, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT stay_date,nightly_price FROM hotel.assignment_nightly_rates WHERE assignment_id=? AND stay_date>=? AND stay_date<? ORDER BY stay_date",
            (rs,row)->new NightlyRate(rs.getDate(1).toLocalDate(),rs.getBigDecimal(2)),id,from,to);
    }
    @Override public List<NightlyRate> currentRates(long type, LocalDate from, LocalDate to) {
        return jdbc.query("""
            SELECT d::date,hotel.nightly_rate(?,d::date) FROM generate_series(?::date,(?::date-1)::timestamp,interval '1 day') d
            ORDER BY d
            """,(rs,row)->new NightlyRate(rs.getDate(1).toLocalDate(),rs.getBigDecimal(2)),type,from,to);
    }
    @Override public void changeAssignment(RoomAssignment old, Room room, List<NightlyRate> rates, String reason, Instant now) {
        jdbc.update("UPDATE hotel.room_assignments SET released_at=?,departure_at=?,reason=? WHERE id=?",ts(now),ts(now),reason,old.id());
        jdbc.update("UPDATE hotel.rooms SET occupancy='VACANT',cleaning='DIRTY',version=version+1 WHERE id=?",old.roomId());
        Long next=jdbc.queryForObject("""
            INSERT INTO hotel.room_assignments(reservation_detail_id,room_type_id,room_id,assigned_at,arrival_at,reason)
            VALUES(?,?,?,?,?,?) RETURNING id
            """,Long.class,old.detailId(),room.roomTypeId(),room.id(),ts(now),ts(now),reason);
        if(!rates.isEmpty()) jdbc.batchUpdate("INSERT INTO hotel.assignment_nightly_rates VALUES(?,?,?)",rates,rates.size(),
            (ps,rate)->{ ps.setLong(1,next);ps.setObject(2,rate.date());ps.setBigDecimal(3,rate.price()); });
        jdbc.update("UPDATE hotel.rooms SET occupancy='OCCUPIED',version=version+1 WHERE id=?",room.id());
    }
    @Override public void extra(long id, String description, BigDecimal amount, long actor) {
        if(amount.signum()!=0) jdbc.update("INSERT INTO hotel.extra_charges(reservation_id,description,amount,created_by) VALUES(?,?,?,?)",id,description,amount,actor);
    }
    @Override public List<InvoiceLine> roomLines(long id) {
        return jdbc.query("""
            SELECT t.name,n.stay_date,n.quantity,n.nightly_price,n.quantity*n.nightly_price
            FROM hotel.reservation_nights n JOIN hotel.reservation_details d ON d.id=n.reservation_detail_id
            JOIN hotel.room_types t ON t.id=n.room_type_id WHERE d.reservation_id=? ORDER BY n.stay_date,n.room_type_id
            """,(rs,row)->new InvoiceLine("ROOM",rs.getString(1)+" / "+rs.getDate(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getBigDecimal(5)),id);
    }
    @Override public BillingPolicy policy(Reservation r) {
        return jdbc.queryForObject("""
            SELECT coalesce(r.stay_tax_rate,b.tax_rate),b.late_checkout_fee,b.checkout_time
            FROM hotel.reservations r JOIN hotel.branches b ON b.id=r.branch_id WHERE r.id=?
            """,(rs,row)->new BillingPolicy(rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getTime(3).toLocalTime()),r.id());
    }
    @Override public void freezeTax(long id) {
        jdbc.update("UPDATE hotel.reservations r SET stay_tax_rate=b.tax_rate FROM hotel.branches b WHERE b.id=r.branch_id AND r.id=? AND r.stay_tax_rate IS NULL",id);
    }
    private final RowMapper<Refund> refundMapper=(rs,row)->new Refund(rs.getLong("id"),rs.getLong("reservation_id"),
        rs.getLong("payment_id"),rs.getBigDecimal("amount"),rs.getString("method"),rs.getString("status"),rs.getString("idempotency_key"),rs.getString("reason"),instant(rs,"completed_at"));
    private static final String REFUND_SELECT="SELECT f.*,p.method FROM hotel.refunds f JOIN hotel.payments p ON p.id=f.payment_id ";
    @Override public Optional<Refund> refundByKey(String key) {
        return jdbc.query(REFUND_SELECT+"WHERE f.idempotency_key=?",refundMapper,key).stream().findFirst();
    }
    @Override public Refund refund(long id, long refundId) {
        return jdbc.query(REFUND_SELECT+"WHERE f.reservation_id=? AND f.id=?",refundMapper,id,refundId).stream().findFirst()
            .orElseThrow(()->new StayException(404,"REFUND_NOT_FOUND","Không tìm thấy khoản hoàn của đơn."));
    }
    @Override public Payment payment(long id) {
        return jdbc.query("SELECT id,reservation_id,amount,method,status FROM hotel.payments WHERE id=?",
            (rs,row)->new Payment(rs.getLong(1),rs.getLong(2),rs.getBigDecimal(3),rs.getString(4),rs.getString(5)),id)
            .stream().findFirst().orElseThrow(()->new StayException(404,"PAYMENT_NOT_FOUND","Không tìm thấy khoản thu."));
    }
    @Override public BigDecimal allocatedRefunds(long id, Long paymentId, Long excludingId) {
        return jdbc.queryForObject("""
            SELECT coalesce(sum(amount),0) FROM hotel.refunds WHERE reservation_id=? AND status IN ('PENDING','DONE')
              AND (?::bigint IS NULL OR payment_id=?) AND (?::bigint IS NULL OR id<>?)
            """,BigDecimal.class,id,paymentId,paymentId,excludingId,excludingId);
    }
    @Override public Refund createRefund(long id, long paymentId, BigDecimal amount, String key, String reason, boolean done, long actor, Instant now) {
        Long refundId=jdbc.queryForObject("""
            INSERT INTO hotel.refunds(reservation_id,payment_id,amount,status,idempotency_key,reason,recorded_by,completed_at)
            VALUES(?,?,?,?,?,?,?,?) RETURNING id
            """,Long.class,id,paymentId,amount,done?"DONE":"PENDING",key,reason,actor,done?ts(now):null);
        return refund(id,refundId);
    }
    @Override public void completeRefund(long id, long actor, Instant now) {
        jdbc.update("UPDATE hotel.refunds SET status='DONE',completed_at=?,recorded_by=? WHERE id=? AND status='PENDING'",ts(now),actor,id);
    }
    @Override public void requestInvoiceDelivery(long invoiceId, Instant now) {
        jdbc.update("INSERT INTO hotel.invoice_delivery_requests(invoice_id,requested_at) VALUES(?,?) ON CONFLICT DO NOTHING",invoiceId,ts(now));
    }
}
