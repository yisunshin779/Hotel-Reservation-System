package com.Hotel.Module_Payment.service;

import com.Hotel.Module_Payment.entity.*;
import com.Hotel.Module_Report.security.ReportIdentityService;
import com.Hotel.Module_Report.security.ReportIdentityService.Identity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class PaymentAuthContext {
    private final ReportIdentityService identities;
    public PaymentAuthContext(ReportIdentityService identities) { this.identities=identities; }
    public Identity current(HttpServletRequest request) {
        if(request.getUserPrincipal()==null) throw new PaymentException(401,"AUTHENTICATION_REQUIRED","Authentication required");
        var actor=identities.find(request.getUserPrincipal().getName());
        if(actor==null || !actor.active() || !actor.unlocked()) throw new PaymentException(403,"FORBIDDEN","Account unavailable");
        return actor;
    }
    public void owner(PaymentReservation reservation,Identity actor) {
        if(!Long.valueOf(actor.id()).equals(reservation.ownerUserId())) throw new PaymentException(403,"FORBIDDEN","Reservation belongs to another customer");
    }
    public void receptionist(PaymentReservation reservation,Identity actor) {
        if(!"RECEPTIONIST".equals(actor.role()) || !Long.valueOf(reservation.branchId()).equals(actor.branchId()))
            throw new PaymentException(403,"FORBIDDEN","Only the branch receptionist may collect cash");
    }
}
