package com.Hotel.Module_CheckInCheckOut.dto;

import java.time.Instant;
import java.util.List;

public record CheckInResultDTO(long reservationId, String reservationStatus, List<RoomResponseDTO> rooms, Instant actualCheckInTime) {}
