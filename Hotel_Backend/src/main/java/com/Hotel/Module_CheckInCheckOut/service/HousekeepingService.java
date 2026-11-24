package com.Hotel.Module_CheckInCheckOut.service;

import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import com.Hotel.Module_Report.security.ReportIdentityService;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OC-21.01: cleaning follows DIRTY -> CLEANING -> READY and never overwrites a newer step.
 *
 * <p>A request names the status and the {@code hotel.rooms.version} the caller read. Check-in, check-out,
 * room changes and each cleaning step increase the version, so a request prepared during an earlier stay of
 * the same room is rejected even when the room is DIRTY again.
 */
@Service
public class HousekeepingService {
    public enum CleaningStatus {
        DIRTY, CLEANING, READY;

        CleaningStatus next() {
            return switch (this) {
                case DIRTY -> CLEANING;
                case CLEANING -> READY;
                case READY -> null;
            };
        }
    }
    private record RoomState(String occupancy, CleaningStatus cleaning, int version) {}
    /** Room as housekeeping reads it; {@code version} is sent back as {@code expectedVersion}. */
    public record RoomView(long roomId, String roomNumber, int floor, String occupancy,
                           CleaningStatus cleaningStatus, int version) {}
    /** {@code changed=false} means this exact step was already applied (safe retry). */
    public record Result(CleaningStatus cleaningStatus, int version, boolean changed) {}

    private static final String ACTION = "ROOM_CLEANING_STATUS_CHANGED";
    private final JdbcTemplate jdbc;
    private final ReportIdentityService identities;

    public HousekeepingService(JdbcTemplate jdbc, ReportIdentityService identities) {
        this.jdbc = jdbc;
        this.identities = identities;
    }

    private ReportIdentityService.Identity housekeeper(String email) {
        var actor = identities.find(email);
        if (actor == null || !actor.active() || !actor.unlocked()
                || !"HOUSEKEEPING".equals(actor.role()) || actor.branchId() == null) {
            throw new StayException(403, "FORBIDDEN", "Housekeeping access is required");
        }
        return actor;
    }

    private static final String ROOMS = """
            SELECT r.id, r.room_number, r.floor, r.occupancy::text AS occupancy, r.cleaning::text AS cleaning, r.version
            FROM hotel.rooms r
            JOIN hotel.branches b ON b.id = r.branch_id AND b.active AND b.deleted_at IS NULL
            WHERE r.branch_id = ? AND r.active AND r.deleted_at IS NULL
            """;

    private static RoomView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RoomView(rs.getLong("id"), rs.getString("room_number"), rs.getInt("floor"), rs.getString("occupancy"),
                CleaningStatus.valueOf(rs.getString("cleaning")), rs.getInt("version"));
    }

    /** Rooms of the caller's branch, optionally only those with {@code status}. */
    @Transactional(readOnly = true)
    public List<RoomView> rooms(String email, CleaningStatus status) {
        var actor = housekeeper(email);
        return status == null
                ? jdbc.query(ROOMS + " ORDER BY r.floor, r.room_number", (rs, row) -> view(rs), actor.branchId())
                : jdbc.query(ROOMS + " AND r.cleaning = ?::hotel.cleaning_status ORDER BY r.floor, r.room_number",
                        (rs, row) -> view(rs), actor.branchId(), status.name());
    }

    @Transactional(readOnly = true)
    public RoomView room(long roomId, String email) {
        var actor = housekeeper(email);
        return jdbc.query(ROOMS + " AND r.id = ?", (rs, row) -> view(rs), actor.branchId(), roomId).stream()
                .findFirst().orElseThrow(() -> new StayException(404, "ROOM_NOT_FOUND", "Room not found"));
    }

    @Transactional
    public Result update(long roomId, CleaningStatus expected, int expectedVersion, CleaningStatus target, String email) {
        var actor = housekeeper(email);
        if (expected.next() != target) {
            throw StayException.conflict("INVALID_CLEANING_TRANSITION",
                    "Cleaning must follow DIRTY -> CLEANING -> READY");
        }
        // Every condition below is evaluated on the locked row.
        var room = jdbc.query("""
                SELECT r.occupancy, r.cleaning, r.version FROM hotel.rooms r
                JOIN hotel.branches b ON b.id = r.branch_id AND b.active AND b.deleted_at IS NULL
                WHERE r.id = ? AND r.branch_id = ? AND r.active AND r.deleted_at IS NULL
                FOR UPDATE OF r
                """, (rs, row) -> new RoomState(rs.getString("occupancy"),
                        CleaningStatus.valueOf(rs.getString("cleaning")), rs.getInt("version")), roomId, actor.branchId())
                .stream().findFirst().orElseThrow(() -> new StayException(404, "ROOM_NOT_FOUND", "Room not found"));
        if (!"VACANT".equals(room.occupancy())) {
            throw StayException.conflict("ROOM_OCCUPIED", "Cleaning status can only be updated for a vacant room");
        }
        if (room.version() == expectedVersion && room.cleaning() == expected) {
            int version = expectedVersion + 1;
            jdbc.update("UPDATE hotel.rooms SET cleaning = ?::hotel.cleaning_status, version = ? WHERE id = ?",
                    target.name(), version, roomId);
            jdbc.update("""
                    INSERT INTO hotel.audit_logs(actor_id, action, entity_name, entity_id, before_data, after_data)
                    VALUES (?, ?, 'rooms', ?, jsonb_build_object('cleaning', ?::text, 'version', ?::integer),
                            jsonb_build_object('cleaning', ?::text, 'version', ?::integer))
                    """, actor.id(), ACTION, roomId, expected.name(), expectedVersion, target.name(), version);
            return new Result(target, version, true);
        }
        if (room.version() == expectedVersion + 1 && room.cleaning() == target && appliedStep(roomId, expected,
                expectedVersion, target)) {
            // A retry of the step that produced the current version. Nothing is written or audited twice.
            return new Result(target, room.version(), false);
        }
        throw StayException.conflict("STALE_CLEANING_STATUS", "Room cleaning status is " + room.cleaning()
                + " at version " + room.version() + ", not " + expected + " at version " + expectedVersion);
    }

    /** The version step {@code expectedVersion -> expectedVersion+1} was this cleaning transition. */
    private boolean appliedStep(long roomId, CleaningStatus expected, int expectedVersion, CleaningStatus target) {
        Boolean applied = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM hotel.audit_logs
                               WHERE action = ? AND entity_name = 'rooms' AND entity_id = ?
                                 AND before_data->>'cleaning' = ? AND (before_data->>'version')::integer = ?
                                 AND after_data->>'cleaning' = ? AND (after_data->>'version')::integer = ?)
                """, Boolean.class, ACTION, roomId, expected.name(), expectedVersion, target.name(), expectedVersion + 1);
        return Boolean.TRUE.equals(applied);
    }
}
