package com.Hotel.Module_CheckInCheckOut.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public record PaymentRequestDTO(@NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal amount,
                             @NotBlank @Pattern(regexp="CASH|MOCK_QR|MOCK_CARD") String method,
                             @NotBlank @Pattern(regexp="[A-Za-z0-9:_-]{8,100}") String obligationKey) {}
