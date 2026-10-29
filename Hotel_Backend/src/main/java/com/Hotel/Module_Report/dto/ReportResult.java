package com.Hotel.Module_Report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public record ReportResult(ReportType reportType, ReportView view, ReportFilter filter,
                           boolean hasData, Map<String, BigDecimal> summary,
                           List<Map<String, Object>> items) {
    /** Keep the original revenue summary fields at the top level for existing clients. */
    public Map<String, Object> response() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportType", reportType);
        body.put("from", filter.from()); body.put("to", filter.to());
        body.put("branchIds", filter.branchIds()); body.put("roomTypeIds", filter.roomTypeIds());
        body.put("branchId", filter.branchIds().size() == 1 ? filter.branchIds().get(0) : null);
        body.put("groupBy", filter.groupBy()); body.put("hasData", hasData);
        body.put("summary", summary);
        if (view == ReportView.SUMMARY) body.putAll(summary);
        else body.put("items", items);
        return body;
    }
}
