package com.Hotel.Module_Payment.controller;
import com.Hotel.Module_Payment.dto.*;
import com.Hotel.Module_Payment.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations/{id}")
public class PaymentController {
    private final PaymentService payments;
    private final PaymentAuthContext auth;
    public PaymentController(PaymentService payments,PaymentAuthContext auth) { this.payments=payments; this.auth=auth; }
    @GetMapping("/payments") public PaymentSummary options(@PathVariable String id,HttpServletRequest request) { return payments.options(payments.resolveReservationId(id),auth.current(request)); }
    @PostMapping("/payments") public PaymentSummary select(@PathVariable String id,@Valid @RequestBody SelectPaymentRequest body,HttpServletRequest request) {
        return payments.select(payments.resolveReservationId(id),body,auth.current(request));
    }
    @PostMapping("/payments/{intent}/confirm-qr") public PaymentSummary confirm(@PathVariable String id,@PathVariable UUID intent,HttpServletRequest request) {
        return payments.confirmQr(payments.resolveReservationId(id),intent,auth.current(request));
    }
    @PostMapping("/payments/{intent}/cancel") public PaymentSummary cancel(@PathVariable String id,@PathVariable UUID intent,HttpServletRequest request) {
        return payments.cancelQr(payments.resolveReservationId(id),intent,auth.current(request));
    }
    @PostMapping("/payments/{intent}/collect-cash") public PaymentSummary collect(@PathVariable String id,@PathVariable UUID intent,
            @Valid @RequestBody CashCollectionRequest body,HttpServletRequest request) {
        return payments.collectCash(payments.resolveReservationId(id),intent,body,auth.current(request));
    }

    @PostMapping("/cancel")
    public PaymentSummary cancelHold(@PathVariable String id, HttpServletRequest request) {
        return payments.cancelUnpaidHold(payments.resolveReservationId(id), auth.current(request));
    }
}
