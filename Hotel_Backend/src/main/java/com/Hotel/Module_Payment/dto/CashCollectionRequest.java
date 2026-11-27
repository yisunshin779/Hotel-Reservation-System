package com.Hotel.Module_Payment.dto;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
public record CashCollectionRequest(@NotNull @AssertTrue Boolean cashReceived) {}
