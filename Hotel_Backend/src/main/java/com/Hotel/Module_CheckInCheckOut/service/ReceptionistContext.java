package com.Hotel.Module_CheckInCheckOut.service;

import com.Hotel.Module_CheckInCheckOut.entity.Receptionist;
import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import com.Hotel.Module_CheckInCheckOut.repository.StayRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Boundary for the shared authentication module: requires a server-verified principal (email).
 * Never reads user IDs, roles or branch IDs from client headers or request bodies. */
@Component
public class ReceptionistContext {
    private final StayRepository repo;
    public ReceptionistContext(StayRepository repo) { this.repo = repo; }
    public Receptionist current(HttpServletRequest request) {
        var principal = request.getUserPrincipal();
        if (principal == null) throw new StayException(401,"AUTHENTICATION_REQUIRED","Cần đăng nhập qua module xác thực chung.");
        return repo.actorByEmail(principal.getName()).orElseThrow(() -> new StayException(403,"FORBIDDEN","Tài khoản không có quyền vận hành chi nhánh."));
    }
}
