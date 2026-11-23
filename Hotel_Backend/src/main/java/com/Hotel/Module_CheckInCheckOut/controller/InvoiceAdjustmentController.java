package com.Hotel.Module_CheckInCheckOut.controller;

import com.Hotel.Module_CheckInCheckOut.dto.InvoiceAdjustmentRequestDTO;
import com.Hotel.Module_CheckInCheckOut.entity.InvoiceAdjustment;
import com.Hotel.Module_CheckInCheckOut.entity.StayException;
import com.Hotel.Module_CheckInCheckOut.service.InvoiceAdjustmentService;
import com.Hotel.Module_Report.security.ReportIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations/{id}/checkout/invoice/adjustments")
public class InvoiceAdjustmentController {
    private final InvoiceAdjustmentService service;
    private final ReportIdentityService identities;
    public InvoiceAdjustmentController(InvoiceAdjustmentService service, ReportIdentityService identities) {
        this.service = service; this.identities = identities;
    }
    private ReportIdentityService.Identity current(HttpServletRequest request) {
        var principal = request.getUserPrincipal();
        if (principal == null) throw new StayException(401, "AUTHENTICATION_REQUIRED", "Cần đăng nhập.");
        return identities.find(principal.getName());
    }
    @PostMapping
    public InvoiceAdjustment create(@PathVariable long id, @RequestBody InvoiceAdjustmentRequestDTO body,
                                    HttpServletRequest request) {
        return service.create(id, body, current(request));
    }
    @GetMapping
    public List<InvoiceAdjustment> list(@PathVariable long id, HttpServletRequest request) {
        return service.list(id, current(request));
    }
}
