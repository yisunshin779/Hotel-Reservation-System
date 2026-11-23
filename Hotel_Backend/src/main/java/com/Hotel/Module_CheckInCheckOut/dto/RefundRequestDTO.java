package com.Hotel.Module_CheckInCheckOut.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record RefundRequestDTO(@Positive long paymentId, @NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal amount,
                            @NotBlank @Pattern(regexp="CASH|MOCK_QR|MOCK_CARD") String method,
                            @NotBlank @Pattern(regexp="[A-Za-z0-9:_-]{8,100}") String idempotencyKey,
                            @NotBlank @Size(max=500) String reason) {}
