package com.Hotel.Module_CheckInCheckOut.controller;

import com.Hotel.Module_CheckInCheckOut.dto.CheckInRequestDTO;
import com.Hotel.Module_CheckInCheckOut.dto.CheckInResultDTO;
import com.Hotel.Module_CheckInCheckOut.dto.GuestRequestDTO;
import com.Hotel.Module_CheckInCheckOut.dto.RoomResponseDTO;
import com.Hotel.Module_CheckInCheckOut.service.CheckInService;
import com.Hotel.Module_CheckInCheckOut.service.ReceptionistContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations/{id}/checkin")
public class CheckInController {
    private final CheckInService service;
    private final ReceptionistContext auth;
    public CheckInController(CheckInService service, ReceptionistContext auth) { this.service=service; this.auth=auth; }

    @PostMapping("/identity")
    public List<RoomResponseDTO> identity(@PathVariable long id, @Valid @RequestBody GuestRequestDTO body, HttpServletRequest request) {
        return service.verifyGuest(id,body.toEntity(),auth.current(request)).stream().map(RoomResponseDTO::from).toList();
    }
    @GetMapping("/available-rooms")
    public List<RoomResponseDTO> available(@PathVariable long id, @RequestParam(required=false) Integer floor,
                                    @RequestParam(required=false) String viewDirection, HttpServletRequest request) {
        return service.available(id,auth.current(request),floor,viewDirection).stream().map(RoomResponseDTO::from).toList();
    }
    @PostMapping
    public CheckInResultDTO confirm(@PathVariable long id, @Valid @RequestBody CheckInRequestDTO body, HttpServletRequest request) {
        return service.confirm(id,body.roomIds(),body.selections(),body.guest()==null?null:body.guest().toEntity(),auth.current(request));
    }
}
