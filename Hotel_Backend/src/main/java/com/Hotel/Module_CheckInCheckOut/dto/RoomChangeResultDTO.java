package com.Hotel.Module_CheckInCheckOut.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record RoomChangeResultDTO(long reservationId, long oldRoomId, long newRoomId, BigDecimal adjustment, Instant changedAt) {}
