package com.Hotel.Module_CheckInCheckOut.controller;

import com.Hotel.Module_CheckInCheckOut.dto.RoomChangeOptionDTO;
import com.Hotel.Module_CheckInCheckOut.dto.RoomChangeRequestDTO;
import com.Hotel.Module_CheckInCheckOut.dto.RoomChangeResultDTO;
import com.Hotel.Module_CheckInCheckOut.entity.RoomChangeReason;
import com.Hotel.Module_CheckInCheckOut.service.ReceptionistContext;
import com.Hotel.Module_CheckInCheckOut.service.RoomChangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations/{id}/room-change")
public class RoomChangeController {
    private final RoomChangeService service;
    private final ReceptionistContext auth;
    public RoomChangeController(RoomChangeService service, ReceptionistContext auth) { this.service=service;this.auth=auth; }

    @GetMapping("/available-rooms")
    public List<RoomChangeOptionDTO> options(@PathVariable long id, @RequestParam long oldRoomId,
                                                @RequestParam RoomChangeReason reason, HttpServletRequest request) {
        return service.options(id,oldRoomId,reason,auth.current(request));
    }
    @PostMapping
    public RoomChangeResultDTO change(@PathVariable long id, @Valid @RequestBody RoomChangeRequestDTO body, HttpServletRequest request) {
        return service.change(id,body.oldRoomId(),body.newRoomId(),body.reason(),body.expectedAdjustment(),auth.current(request));
    }
}
