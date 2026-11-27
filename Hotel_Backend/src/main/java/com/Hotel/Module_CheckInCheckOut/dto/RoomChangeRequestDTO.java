package com.Hotel.Module_CheckInCheckOut.dto;

import com.Hotel.Module_CheckInCheckOut.entity.RoomChangeReason;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record RoomChangeRequestDTO(@Positive long oldRoomId, @Positive long newRoomId, @NotNull RoomChangeReason reason,
                      @NotNull @Digits(integer=12,fraction=2) BigDecimal expectedAdjustment) {}
