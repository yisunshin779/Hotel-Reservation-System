package com.Hotel.Module_CheckInCheckOut.controller;

import com.Hotel.Module_CheckInCheckOut.dto.BillResponseDTO;
import com.Hotel.Module_CheckInCheckOut.dto.CashRefundConfirmationDTO;
import com.Hotel.Module_CheckInCheckOut.dto.CheckOutResultDTO;
import com.Hotel.Module_CheckInCheckOut.dto.InvoiceResponseDTO;
import com.Hotel.Module_CheckInCheckOut.dto.PaymentRequestDTO;
import com.Hotel.Module_CheckInCheckOut.dto.RefundRequestDTO;
import com.Hotel.Module_CheckInCheckOut.dto.RefundResponseDTO;
import com.Hotel.Module_CheckInCheckOut.service.CheckOutService;
import com.Hotel.Module_CheckInCheckOut.service.InvoiceService;
import com.Hotel.Module_CheckInCheckOut.service.ReceptionistContext;
import com.Hotel.Module_CheckInCheckOut.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations/{id}/checkout")
public class CheckOutController {
    private final CheckOutService service;
    private final ReceptionistContext auth;
    private final InvoiceService invoices;
    private final RefundService refunds;
    public CheckOutController(CheckOutService service, ReceptionistContext auth, InvoiceService invoices,
                              RefundService refunds) {
        this.service=service; this.auth=auth; this.invoices=invoices; this.refunds=refunds;
    }
    @GetMapping("/preview")
    public BillResponseDTO preview(@PathVariable long id, HttpServletRequest request) { return BillResponseDTO.from(service.preview(id,auth.current(request))); }
    @PostMapping("/payment")
    public BillResponseDTO payment(@PathVariable long id, @Valid @RequestBody PaymentRequestDTO body, HttpServletRequest request) {
        return BillResponseDTO.from(service.payment(id,body.amount(),body.method(),body.obligationKey(),auth.current(request)));
    }

    @PostMapping("/refunds")
    public RefundResponseDTO refund(@PathVariable long id, @Valid @RequestBody RefundRequestDTO body, HttpServletRequest request) {
        return RefundResponseDTO.from(refunds.request(id,body.paymentId(),body.amount(),body.method(),body.idempotencyKey(),body.reason(),auth.current(request)));
    }
    @PostMapping("/refunds/{refundId}/confirm-cash")
    public RefundResponseDTO confirmCash(@PathVariable long id, @PathVariable long refundId,
                                  @Valid @RequestBody CashRefundConfirmationDTO body, HttpServletRequest request) {
        return RefundResponseDTO.from(refunds.confirmCash(id,refundId,body.cashReturned(),auth.current(request)));
    }
    @PostMapping
    public CheckOutResultDTO confirm(@PathVariable long id, HttpServletRequest request) { return service.confirm(id,auth.current(request)); }
    @GetMapping("/invoice")
    public InvoiceResponseDTO invoice(@PathVariable long id, HttpServletRequest request) { return InvoiceResponseDTO.from(invoices.get(id,auth.current(request))); }
    @GetMapping("/invoice/export")
    public ResponseEntity<byte[]> export(@PathVariable long id, HttpServletRequest request) {
        var invoice=InvoiceResponseDTO.from(invoices.get(id,auth.current(request)));
        return ResponseEntity.ok().contentType(new MediaType("text","html",StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=invoice-"+invoice.id()+".html")
            .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; sandbox")
            .cacheControl(CacheControl.noStore()).body(InvoiceView.html(invoice).getBytes(StandardCharsets.UTF_8));
    }
}
