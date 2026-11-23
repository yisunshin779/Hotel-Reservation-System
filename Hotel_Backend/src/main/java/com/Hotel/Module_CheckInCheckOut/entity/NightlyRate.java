package com.Hotel.Module_CheckInCheckOut.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record NightlyRate(LocalDate date, BigDecimal price) {}
