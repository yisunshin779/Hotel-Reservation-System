package com.Hotel.Module_Report.service;

import com.Hotel.Module_Report.dto.*;
import com.Hotel.Module_Report.repository.ReportRepository;
import com.Hotel.Module_Report.security.ReportAccessService;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly=true)
public class ReportService {
    private final ReportRepository repository;
    private final ReportAccessService access;
    public ReportService(ReportRepository repository,ReportAccessService access) {
        this.repository=repository; this.access=access;
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    public ReportFilter resolve(ReportFilter input) {
        if (input.from()==null || input.to()==null || input.from().getYear()<1 || input.to().getYear()>9999
                || input.from().isAfter(input.to()) || input.to().isAfter(input.from().plusYears(1)))
            throw bad("Dates must be valid, ordered and no more than one calendar year apart");
        if (input.branchIds().stream().anyMatch(id -> id<=0) || input.roomTypeIds().stream().anyMatch(id -> id<=0))
            throw bad("Branch and room type IDs must be positive");
        var all=repository.branches().stream().map(row -> ((Number)row.get("id")).longValue()).toList();
        var scoped=access.scope(input.branchIds().stream().distinct().sorted().toList(),all);
        if (!all.containsAll(scoped)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Branch does not exist");
        var types=input.roomTypeIds().stream().distinct().sorted().toList();
        var availableTypes=repository.roomTypes(scoped).stream().map(row -> ((Number)row.get("id")).longValue()).toList();
        if (!availableTypes.containsAll(types)) throw bad("Room type does not exist in the selected branch scope");
        return new ReportFilter(input.from(),input.to(),scoped,types,input.groupBy());
    }
    public Map<String,Object> options() {
        var all=repository.branches();
        var allowed=access.scope(List.of(),all.stream().map(row -> ((Number)row.get("id")).longValue()).toList());
        return Map.of("role",access.current().role(),"branches",all.stream()
                .filter(row -> allowed.contains(((Number)row.get("id")).longValue())).toList(),
                "roomTypes",repository.roomTypes(allowed));
    }
    public ReportResult report(ReportType type,ReportView view,ReportFilter input) {
        if (view==ReportView.PROMOTION && type!=ReportType.PROMOTIONS) throw bad("Promotion view is only available for promotions");
        ReportFilter filter=resolve(input);
        List<ReportFact> facts=switch(type) {
            case REVENUE -> repository.revenue(filter);
            case OCCUPANCY -> repository.occupancy(filter);
            case RESERVATIONS -> repository.reservations(filter);
            case PROMOTIONS -> repository.promotions(filter);
        };
        Map<String,BigDecimal> summary=aggregate(type,facts);
        List<Map<String,Object>> items=new ArrayList<>();
        if (view==ReportView.PERIOD) {
            var groups=new TreeMap<LocalDate,List<ReportFact>>();
            for (var fact:facts) groups.computeIfAbsent(filter.groupBy().floor(fact.day()),k -> new ArrayList<>()).add(fact);
            for (LocalDate day=filter.groupBy().floor(filter.from());!day.isAfter(filter.to());day=filter.groupBy().next(day)) {
                var metrics=aggregate(type,groups.getOrDefault(day,List.of()));
                var row=new LinkedHashMap<String,Object>();row.put("periodStart",day);row.putAll(metrics);
                row.put("hasData",hasData(type,metrics));items.add(row);
            }
        } else if(view==ReportView.BRANCH) {
            for(var branch:repository.branches()) {
                long id=((Number)branch.get("id")).longValue();
                if(!filter.branchIds().contains(id)) continue;
                var metrics=aggregate(type,facts.stream().filter(f -> f.branchId()==id).toList());
                var row=new LinkedHashMap<String,Object>();row.put("branchId",id);row.put("branchName",branch.get("name"));
                row.putAll(metrics);row.put("hasData",hasData(type,metrics));items.add(row);
            }
        } else if(view==ReportView.PROMOTION) {
            var groups=new TreeMap<Long,List<ReportFact>>();
            for(var fact:facts) if(fact.promotionId()!=null) groups.computeIfAbsent(fact.promotionId(),k -> new ArrayList<>()).add(fact);
            for(var entry:groups.entrySet()) {
                var first=entry.getValue().get(0);var metrics=aggregate(type,entry.getValue());
                var row=new LinkedHashMap<String,Object>();row.put("promotionId",entry.getKey());
                row.put("code",first.promotionCode());row.put("name",first.promotionName());row.putAll(metrics);
                row.put("hasData",hasData(type,metrics));items.add(row);
            }
        }
        return new ReportResult(type,view,filter,hasData(type,summary),summary,List.copyOf(items));
    }
    private List<String> metricNames(ReportType type) {
        return switch(type) {
            case REVENUE -> ReportRepository.REVENUE_METRICS;
            case OCCUPANCY -> ReportRepository.OCCUPANCY_METRICS;
            case RESERVATIONS -> ReportRepository.RESERVATION_METRICS;
            case PROMOTIONS -> ReportRepository.PROMOTION_METRICS;
        };
    }
    private Map<String,BigDecimal> aggregate(ReportType type,List<ReportFact> facts) {
        Map<String,BigDecimal> values=new LinkedHashMap<>();
        for(String key:metricNames(type)) values.put(key,BigDecimal.ZERO);
        for(var fact:facts) fact.metrics().forEach((key,value) -> values.merge(key,value,BigDecimal::add));
        if(type==ReportType.REVENUE || type==ReportType.PROMOTIONS)
            values.put("netRevenue",values.get("invoiceTotal").subtract(values.get("refundTotal")));
        if(type==ReportType.OCCUPANCY)
            values.put("occupancyRate",percent(values.get("soldRoomNights"),values.get("availableRoomNights")));
        if(type==ReportType.RESERVATIONS) {
            values.put("cancellationRate",percent(values.get("cancelledCount"),values.get("totalReservations")));
            values.put("noShowRate",percent(values.get("noShowCount"),values.get("totalReservations")));
        }
        return values;
    }
    private BigDecimal percent(BigDecimal numerator,BigDecimal denominator) {
        return denominator.signum()==0 ? null : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator,2,RoundingMode.HALF_UP);
    }
    private boolean hasData(ReportType type,Map<String,BigDecimal> values) {
        return switch(type) {
            case REVENUE -> values.get("closedInvoiceCount").add(values.get("refundCount")).signum()>0;
            case OCCUPANCY -> values.get("soldRoomNights").add(values.get("availableRoomNights")).signum()>0;
            case RESERVATIONS -> values.get("totalReservations").signum()>0;
            case PROMOTIONS -> values.get("usageCount").add(values.get("reversedUsageCount"))
                    .add(values.get("closedInvoiceCount")).add(values.get("refundCount")).signum()>0;
        };
    }
}
