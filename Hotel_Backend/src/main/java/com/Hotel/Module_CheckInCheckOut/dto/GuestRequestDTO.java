package com.Hotel.Module_CheckInCheckOut.dto;

import com.Hotel.Module_CheckInCheckOut.entity.StayGuest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record GuestRequestDTO(@NotBlank @Size(max=160) String fullName,
                           @NotBlank @Size(max=80) String identificationInfo, @Positive Long customerId) {
    public StayGuest toEntity() { return new StayGuest(fullName,identificationInfo,customerId); }
}
