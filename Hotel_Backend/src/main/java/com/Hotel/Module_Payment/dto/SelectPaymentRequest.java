package com.Hotel.Module_Payment.dto;
import com.Hotel.Module_Payment.entity.*;
import jakarta.validation.constraints.NotNull;
public record SelectPaymentRequest(@NotNull PaymentMethod method, @NotNull PaymentPlan plan) {}
