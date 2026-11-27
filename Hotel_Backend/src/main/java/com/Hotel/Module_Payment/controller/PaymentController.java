package com.Hotel.Module_Payment.controller;
import com.Hotel.Module_Payment.dto.*;
import com.Hotel.Module_Payment.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations/{id}/payments")
public class PaymentController {
    private final PaymentService payments;
    private final PaymentAuthContext auth;
    public PaymentController(PaymentService payments,PaymentAuthContext auth) { this.payments=payments; this.auth=auth; }
    @GetMapping public PaymentSummary options(@PathVariable long id,HttpServletRequest request) { return payments.options(id,auth.current(request)); }
    @PostMapping public PaymentSummary select(@PathVariable long id,@Valid @RequestBody SelectPaymentRequest body,HttpServletRequest request) {
        return payments.select(id,body,auth.current(request));
    }
    @PostMapping("/{intent}/confirm-qr") public PaymentSummary confirm(@PathVariable long id,@PathVariable UUID intent,HttpServletRequest request) {
        return payments.confirmQr(id,intent,auth.current(request));
    }
    @PostMapping("/{intent}/cancel") public PaymentSummary cancel(@PathVariable long id,@PathVariable UUID intent,HttpServletRequest request) {
        return payments.cancelQr(id,intent,auth.current(request));
    }
    @PostMapping("/{intent}/collect-cash") public PaymentSummary collect(@PathVariable long id,@PathVariable UUID intent,
            @Valid @RequestBody CashCollectionRequest body,HttpServletRequest request) {
        return payments.collectCash(id,intent,body,auth.current(request));
    }
}
