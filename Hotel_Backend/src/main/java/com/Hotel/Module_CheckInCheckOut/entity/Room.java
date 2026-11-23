package com.Hotel.Module_CheckInCheckOut.entity;

public record Room(long roomId, long branchId, long roomTypeId, String roomNumber, String occupancy,
                   String cleaning, boolean active, boolean maintained, boolean assigned, int floor, String viewDirection) {
    public long id() { return roomId; }
    public String number() { return roomNumber; }
    public boolean ready() {
        return active && "VACANT".equals(occupancy) && "READY".equals(cleaning) && !maintained && !assigned;
    }
}
