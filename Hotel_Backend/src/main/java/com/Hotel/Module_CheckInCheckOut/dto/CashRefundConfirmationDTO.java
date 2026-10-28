package com.Hotel.Module_CheckInCheckOut.dto;

import jakarta.validation.constraints.AssertTrue;

public record CashRefundConfirmationDTO(@AssertTrue boolean cashReturned) {}
