package com.Hotel.Module_CheckInCheckOut.controller;

import com.Hotel.Module_CheckInCheckOut.service.HousekeepingService;
import com.Hotel.Module_CheckInCheckOut.service.HousekeepingService.CleaningStatus;
import com.Hotel.Module_CheckInCheckOut.service.HousekeepingService.RoomView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/housekeeping/rooms")
public class HousekeepingController {
    private final HousekeepingService housekeeping;

    public HousekeepingController(HousekeepingService housekeeping) {
        this.housekeeping = housekeeping;
    }

    /**
     * {@code expectedStatus} and {@code expectedVersion} are the status and version the caller read from
     * {@code GET /api/v1/housekeeping/rooms} (OC-21.01 trangThaiHienTai).
     */
    public record CleaningRequest(@NotNull CleaningStatus expectedStatus, @NotNull @PositiveOrZero Integer expectedVersion,
                                  @NotNull CleaningStatus cleaningStatus) {}
    public record CleaningResponse(long roomId, CleaningStatus cleaningStatus, int version, boolean changed) {}

    /** Rooms of the caller's branch with their current version; {@code cleaningStatus} filters. */
    @GetMapping
    public List<RoomView> rooms(@RequestParam(required = false) CleaningStatus cleaningStatus, Authentication authentication) {
        return housekeeping.rooms(authentication.getName(), cleaningStatus);
    }

    @GetMapping("/{id}")
    public RoomView room(@PathVariable long id, Authentication authentication) {
        return housekeeping.room(id, authentication.getName());
    }

    @PutMapping("/{id}/cleaning-status")
    public CleaningResponse update(@PathVariable long id, @Valid @RequestBody CleaningRequest request,
                                   Authentication authentication) {
        var result = housekeeping.update(id, request.expectedStatus(), request.expectedVersion(),
                request.cleaningStatus(), authentication.getName());
        return new CleaningResponse(id, result.cleaningStatus(), result.version(), result.changed());
    }
}
