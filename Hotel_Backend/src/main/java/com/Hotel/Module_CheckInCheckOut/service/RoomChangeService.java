package com.Hotel.Module_CheckInCheckOut.service;

import com.Hotel.Module_CheckInCheckOut.dto.RoomChangeOptionDTO;
import com.Hotel.Module_CheckInCheckOut.dto.RoomChangeResultDTO;
import com.Hotel.Module_CheckInCheckOut.dto.RoomResponseDTO;
import com.Hotel.Module_CheckInCheckOut.entity.NightlyRate;
import com.Hotel.Module_CheckInCheckOut.entity.Receptionist;
import com.Hotel.Module_CheckInCheckOut.entity.Reservation;
import com.Hotel.Module_CheckInCheckOut.entity.ReservationStatus;
import com.Hotel.Module_CheckInCheckOut.entity.Room;
import com.Hotel.Module_CheckInCheckOut.entity.RoomAssignment;
import com.Hotel.Module_CheckInCheckOut.entity.RoomChangeReason;
import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import com.Hotel.Module_CheckInCheckOut.repository.StayRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UC17: the booked nightly snapshots remain unchanged; differences are extra charges. */
@Service
public class RoomChangeService {
    private final StayRepository repo;
    private final Clock clock;
    private final BillingService billing;
    public RoomChangeService(StayRepository repo, Clock clock, BillingService billing) { this.repo=repo; this.clock=clock; this.billing=billing; }

    private Reservation stay(long id, Receptionist actor, boolean lock) {
        var r=repo.reservation(id,lock); actor.authorize(r.branchId());
        if(r.status()!=ReservationStatus.CHECKED_IN) throw StayException.conflict("INVALID_RESERVATION_STATE","Chỉ đổi phòng khi đang lưu trú.");
        return r;
    }
    private LocalDate from(Reservation r) {
        var day=LocalDate.now(clock.withZone(ZoneId.of(r.timezone())));
        if(day.isBefore(r.checkIn()) || !day.isBefore(r.checkOut()))
            throw StayException.conflict("NO_REMAINING_NIGHTS","Không còn đêm lưu trú hợp lệ để đổi phòng; cần điều chỉnh kỳ ở trước.");
        return day;
    }
    private List<NightlyRate> rates(RoomAssignment old, Room room, LocalDate from, LocalDate to, RoomChangeReason reason) {
        var before=repo.assignmentRates(old.id(),from,to);
        if(before.size()!=ChronoUnit.DAYS.between(from,to))
            throw StayException.conflict("MISSING_RATE_SNAPSHOT","Thiếu snapshot giá các đêm còn lại của phòng cũ.");
        if(old.roomTypeId()==room.roomTypeId()) return before;
        var after=repo.currentRates(room.roomTypeId(),from,to);
        if(after.size()!=before.size()) throw StayException.conflict("MISSING_RATE_SNAPSHOT","Thiếu giá phòng mới.");
        // Hotel-caused upgrades are free; downgrades retain the reduction, night by night.
        if(reason==RoomChangeReason.ROOM_ISSUE) {
            var capped=new ArrayList<NightlyRate>();
            for(int i=0;i<before.size();i++) capped.add(new NightlyRate(after.get(i).date(),after.get(i).price().min(before.get(i).price())));
            return capped;
        }
        return after;
    }
    private BigDecimal difference(RoomAssignment old, List<NightlyRate> after, LocalDate from, LocalDate to) {
        return after.stream().map(NightlyRate::price).reduce(BigDecimal.ZERO,BigDecimal::add)
            .subtract(repo.assignmentRates(old.id(),from,to).stream().map(NightlyRate::price).reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<RoomChangeOptionDTO> options(long id, long oldRoom, RoomChangeReason reason, Receptionist actor) {
        if(reason==null) throw new StayException(400,"INVALID_CHANGE_REASON","Cần lý do đổi phòng.");
        var r=stay(id,actor,false); var from=from(r); var old=repo.assignment(id,oldRoom);
        var candidates=repo.branchRooms(r,from);
        var adjustments=new HashMap<Long,BigDecimal>();
        // Capacity, availability and pricing depend on type, not on each physical room.
        for(var room:candidates) {
            long type=room.roomTypeId();
            if(adjustments.containsKey(type)) continue;
            if(!repo.enoughCapacity(id,old.roomTypeId(),type,false)
                || (type!=old.roomTypeId() && !repo.inventoryAvailable(type,from,r.checkOut(),1))) {
                adjustments.put(type,null);continue;
            }
            adjustments.put(type,difference(old,rates(old,room,from,r.checkOut(),reason),from,r.checkOut()));
        }
        return candidates.stream().filter(room->adjustments.get(room.roomTypeId())!=null)
            .map(room->new RoomChangeOptionDTO(RoomResponseDTO.from(room),adjustments.get(room.roomTypeId()))).toList();
    }
    @Transactional
    public RoomChangeResultDTO change(long id, long oldRoom, long newRoom, RoomChangeReason reason, BigDecimal expectedAdjustment, Receptionist actor) {
        if(oldRoom<=0 || newRoom<=0 || oldRoom==newRoom || reason==null || expectedAdjustment==null)
            throw new StayException(400,"INVALID_ROOM_CHANGE","Phòng, lý do hoặc chênh lệch xác nhận không hợp lệ.");
        var r=stay(id,actor,true); var from=from(r); var old=repo.assignment(id,oldRoom);
        long newType=repo.roomType(newRoom);
        repo.lockTypes(List.of(old.roomTypeId(),newType));
        // Same room lock order as check-in / checkout.
        var locked=new HashMap<Long,Room>();
        for(long room:List.of(oldRoom,newRoom).stream().sorted().toList()) locked.put(room,repo.lockRoom(room,r,from));
        var next=locked.get(newRoom);
        if(!next.ready() || next.branchId()!=r.branchId()) throw StayException.conflict("ROOM_UNAVAILABLE","Phòng mới không sẵn sàng hoặc sai chi nhánh.");
        if(!repo.enoughCapacity(id,old.roomTypeId(),newType,false)) throw StayException.conflict("INSUFFICIENT_CAPACITY","Phòng mới không đủ sức chứa cho khách trong đơn.");
        if(old.roomTypeId()!=newType && !repo.inventoryAvailable(newType,from,r.checkOut(),1))
            throw StayException.conflict("INSUFFICIENT_INVENTORY","Phòng mới không còn đủ tồn cho toàn bộ đêm còn lại.");
        var after=rates(old,next,from,r.checkOut(),reason);
        var adjustment=difference(old,after,from,r.checkOut());
        if(adjustment.compareTo(expectedAdjustment)!=0) throw StayException.conflict("ROOM_CHANGE_PRICE_CHANGED","Chênh lệch đã đổi; cần xem và xác nhận lại.");
        var now=clock.instant();
        repo.changeAssignment(old,next,after,reason.name(),now);
        repo.transferInventory(id,old.roomTypeId(),newType,from,r.checkOut());
        repo.extra(id,"Đổi phòng "+oldRoom+" → "+newRoom+" / "+reason,adjustment,actor.id());
        billing.calculate(r); // Reject adjustments that would make the invoice invalid; rollback the move too.
        repo.audit(actor.id(),id,"ROOM_CHANGE","{\"oldRoomId\":"+oldRoom+",\"newRoomId\":"+newRoom+",\"adjustment\":"+adjustment+",\"reason\":\""+reason+"\"}");
        return new RoomChangeResultDTO(id,oldRoom,newRoom,adjustment,now);
    }
}
