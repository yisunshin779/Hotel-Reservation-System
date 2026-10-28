package com.Hotel.Module_CheckInCheckOut.service;

import com.Hotel.Module_CheckInCheckOut.dto.CheckInResultDTO;
import com.Hotel.Module_CheckInCheckOut.dto.RoomResponseDTO;
import com.Hotel.Module_CheckInCheckOut.dto.RoomSelectionDTO;
import com.Hotel.Module_CheckInCheckOut.entity.Receptionist;
import com.Hotel.Module_CheckInCheckOut.entity.Reservation;
import com.Hotel.Module_CheckInCheckOut.entity.ReservationDetail;
import com.Hotel.Module_CheckInCheckOut.entity.Room;
import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import com.Hotel.Module_CheckInCheckOut.entity.StayGuest;
import com.Hotel.Module_CheckInCheckOut.repository.StayRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckInService {
    private final StayRepository repo;
    private final CheckInValidator validator;
    private final Clock clock;
    public CheckInService(StayRepository repo, CheckInValidator validator, Clock clock) {
        this.repo = repo; this.validator = validator; this.clock = clock;
    }

    @Transactional
    public List<Room> verifyGuest(long id, StayGuest guest, Receptionist actor) {
        var r=eligible(id,actor,true);
        var checked=validateGuest(guest);
        var rooms=candidates(r);
        repo.saveGuest(id,checked,actor.id(),clock.instant());
        repo.audit(actor.id(),id,"VERIFY_STAY_GUEST","{}");
        return rooms;
    }
    private StayGuest validateGuest(StayGuest guest) {
        if(guest==null || guest.fullName()==null || guest.fullName().strip().isEmpty() || guest.fullName().strip().length()>160)
            throw new StayException(400,"INVALID_GUEST","Cần họ tên người ở chính đã đối chiếu giấy tờ.");
        return new StayGuest(guest.fullName().strip(),validator.identity(guest.identificationInfo()),guest.customerId());
    }
    private List<Room> candidates(Reservation r) {
        var details=repo.details(r.id());
        var all=repo.branchRooms(r,r.checkIn());
        var upgrades=new HashMap<String,Boolean>();
        var inventory=new HashMap<Long,Boolean>();
        var readyCounts=all.stream().collect(java.util.stream.Collectors.groupingBy(Room::roomTypeId,java.util.stream.Collectors.counting()));
        return all.stream().filter(room -> details.stream().anyMatch(d -> d.roomTypeId()==room.roomTypeId()
            || (readyCounts.getOrDefault(d.roomTypeId(),0L)<d.quantity()
                && upgrades.computeIfAbsent(d.roomTypeId()+":"+room.roomTypeId(),key->repo.freeUpgrade(d.roomTypeId(),room.roomTypeId()))
                && inventory.computeIfAbsent(room.roomTypeId(),type->repo.inventoryAvailable(type,r.checkIn(),r.checkOut(),1))))).toList();
    }
    @Transactional(readOnly = true)
    public List<Room> available(long id, Receptionist actor) {
        return available(id,actor,null,null);
    }
    @Transactional(readOnly = true)
    public List<Room> available(long id, Receptionist actor, Integer floor, String viewDirection) {
        var r = eligible(id, actor, false);
        var rooms = candidates(r).stream().filter(room->floor==null || room.floor()==floor)
            .filter(room->viewDirection==null || viewDirection.equalsIgnoreCase(room.viewDirection())).toList();
        if (rooms.isEmpty()) throw StayException.conflict("NO_AVAILABLE_ROOM", "Không còn phòng phù hợp sẵn sàng.");
        return rooms;
    }
    private Reservation eligible(long id, Receptionist actor, boolean lock) {
        var r = repo.reservation(id, lock); actor.authorize(r.branchId());
        validator.validate(r, repo.paid(id).subtract(repo.refunded(id)), clock);
        return r;
    }
    @Transactional
    public CheckInResultDTO confirm(long id, List<Long> roomIds, List<RoomSelectionDTO> selections, StayGuest guest, Receptionist actor) {
        var r=eligible(id,actor,true);
        return perform(r,roomIds,selections,guest==null?null:validateGuest(guest),actor);
    }
    private CheckInResultDTO perform(Reservation r, List<Long> roomIds, List<RoomSelectionDTO> selections, StayGuest guest, Receptionist actor) {
        long id=r.id();
        if(guest==null && !repo.hasGuest(id)) throw StayException.conflict("GUEST_NOT_VERIFIED","Cần xác nhận giấy tờ người ở chính trước khi gán phòng.");
        var details = repo.details(id);
        int required = details.stream().mapToInt(ReservationDetail::quantity).sum();
        if(selections!=null) {
            if(roomIds!=null || selections.stream().anyMatch(Objects::isNull))
                throw new StayException(400,"INVALID_ROOM_SELECTION","Chỉ gửi một cách chọn phòng.");
            roomIds=selections.stream().map(RoomSelectionDTO::roomId).toList();
        }
        if (required == 0 || roomIds == null || roomIds.size() != required || roomIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(roomIds).size() != required)
            throw new StayException(400, "INVALID_ROOM_SELECTION", "Phải chọn đủ số phòng đã đặt, không được trùng phòng.");
        if (!repo.assignedRoomIds(id).isEmpty()) throw StayException.conflict("ALREADY_ASSIGNED", "Đơn đã có phòng được gán.");
        var types=new ArrayList<Long>(details.stream().map(ReservationDetail::roomTypeId).toList());
        types.addAll(roomIds.stream().map(repo::roomType).toList());
        repo.lockTypes(types); // Same advisory locks as booking / maintenance, in ascending order.
        var rooms = roomIds.stream().sorted().map(roomId -> repo.lockRoom(roomId, r)).toList();
        var mapped=new HashMap<Long,ReservationDetail>();
        for(var room:rooms) {
            long detailId=selections==null?details.stream().filter(d->d.roomTypeId()==room.roomTypeId()).mapToLong(ReservationDetail::id).findFirst().orElse(-1):
                selections.stream().filter(s->s.roomId()==room.id()).findFirst().orElseThrow().detailId();
            var detail=details.stream().filter(d->d.id()==detailId).findFirst()
                .orElseThrow(()->StayException.conflict("WRONG_ROOM_TYPE","Phòng phải được ánh xạ tới loại phòng đã đặt."));
            mapped.put(room.id(),detail);
        }
        var ready=repo.branchRooms(r,r.checkIn());
        for (var room : rooms) {
            var detail=mapped.get(room.id());
            if (room.branchId() != r.branchId())
                throw StayException.conflict("WRONG_ROOM_TYPE", "Phòng không thuộc loại đã đặt hoặc sai chi nhánh.");
            if (!room.ready()) throw StayException.conflict("ROOM_UNAVAILABLE", "Phòng vừa được sử dụng, chưa sạch, ngừng hoạt động hoặc đang bảo trì.");
            if(room.roomTypeId()!=detail.roomTypeId()) {
                long upgrades=rooms.stream().filter(x->mapped.get(x.id()).id()==detail.id() && x.roomTypeId()!=detail.roomTypeId()).count();
                long shortage=Math.max(0,detail.quantity()-ready.stream().filter(x->x.roomTypeId()==detail.roomTypeId()).count());
                if(!repo.freeUpgrade(detail.roomTypeId(),room.roomTypeId()) || upgrades>shortage)
                    throw StayException.conflict("INVALID_FREE_UPGRADE","Chỉ nâng hạng miễn phí khi thiếu phòng sẵn sàng của loại đã đặt và có cấu hình hạng phù hợp.");
                int incoming=(int)rooms.stream().filter(x->x.roomTypeId()==room.roomTypeId() && mapped.get(x.id()).roomTypeId()!=x.roomTypeId()).count();
                if(!repo.inventoryAvailable(room.roomTypeId(),r.checkIn(),r.checkOut(),incoming))
                    throw StayException.conflict("INSUFFICIENT_INVENTORY","Nâng hạng sẽ làm thiếu phòng cho đơn đã đặt.");
            }
        }
        if (details.stream().anyMatch(d->mapped.values().stream().filter(x->x.id()==d.id()).count()!=d.quantity()))
            throw StayException.conflict("WRONG_ROOM_QUANTITY", "Số phòng phải khớp từng loại trong đơn.");
        Instant now = clock.instant();
        if(guest!=null) repo.saveGuest(id,guest,actor.id(),now);
        for (var room : rooms) {
            var detail=mapped.get(room.id());
            repo.assign(detail.id(), room, now);
            repo.transferInventory(id,detail.roomTypeId(),room.roomTypeId(),r.checkIn(),r.checkOut());
        }
        repo.freezeTax(id);
        repo.markCheckedIn(id, now);
        repo.audit(actor.id(), id, "CHECK_IN", "{}"); // Never log identity documents.
        var occupied = rooms.stream().map(room -> new Room(room.id(), room.branchId(), room.roomTypeId(),
                room.number(), "OCCUPIED", room.cleaning(), true, false, true,room.floor(),room.viewDirection())).toList();
        return new CheckInResultDTO(id, "CHECKED_IN", occupied.stream().map(RoomResponseDTO::from).toList(), now);
    }
}
