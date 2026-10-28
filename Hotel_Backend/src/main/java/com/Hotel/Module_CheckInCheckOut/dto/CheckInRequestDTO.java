package com.Hotel.Module_CheckInCheckOut.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CheckInRequestDTO(@Size(max=150) List<@NotNull @Positive Long> roomIds,
                      @Size(max=150) List<@NotNull @Valid RoomSelectionDTO> selections,
                      @Valid GuestRequestDTO guest) {}
