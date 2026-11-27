package com.Hotel.Module_CheckInCheckOut.dto;

import com.Hotel.Module_CheckInCheckOut.entity.Room;

public record RoomResponseDTO(long roomId, long branchId, long roomTypeId, String roomNumber,
                              String occupancy, String cleaning, boolean active, boolean maintained,
                              boolean assigned, int floor, String viewDirection) {
    public static RoomResponseDTO from(Room room) {
        return new RoomResponseDTO(room.roomId(), room.branchId(), room.roomTypeId(), room.roomNumber(),
                room.occupancy(), room.cleaning(), room.active(), room.maintained(), room.assigned(),
                room.floor(), room.viewDirection());
    }
}
