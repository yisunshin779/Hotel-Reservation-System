package com.Hotel.Module_Report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** One daily fact, with additive metrics only. Ratios are calculated after aggregation. */
public record ReportFact(LocalDate day, long branchId, String branchName,
                         Long promotionId, String promotionCode, String promotionName,
                         Map<String, BigDecimal> metrics) {}
