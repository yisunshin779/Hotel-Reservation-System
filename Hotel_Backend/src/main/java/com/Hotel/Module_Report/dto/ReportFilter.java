package com.Hotel.Module_Report.dto;

import java.time.LocalDate;
import java.util.List;

public record ReportFilter(LocalDate from, LocalDate to, List<Long> branchIds,
                           List<Long> roomTypeIds, ReportGroupBy groupBy) {
    public ReportFilter {
        branchIds = branchIds == null ? List.of() : List.copyOf(branchIds);
        roomTypeIds = roomTypeIds == null ? List.of() : List.copyOf(roomTypeIds);
        groupBy = groupBy == null ? ReportGroupBy.DAY : groupBy;
    }
}
