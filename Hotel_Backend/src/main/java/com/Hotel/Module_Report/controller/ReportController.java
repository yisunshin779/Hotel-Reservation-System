package com.Hotel.Module_Report.controller;

import com.Hotel.Module_Report.dto.*;
import com.Hotel.Module_Report.service.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService service;
    private final ReportExportService exports;
    public ReportController(ReportService service,ReportExportService exports) {this.service=service;this.exports=exports;}
    @GetMapping("/options")
    public Map<String,Object> options() {return service.options();}

    @GetMapping({"/{type:revenue|occupancy|reservations}/{view:summary|by-period|by-branch}",
                 "/{type:promotions}/{view:summary|by-period|by-branch}","/{type:promotions}"})
    public Map<String,Object> report(@PathVariable String type,@PathVariable(required=false) String view,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) Long branchId,
            @RequestParam(required=false) List<Long> branchIds,
            @RequestParam(required=false) List<Long> roomTypeIds,
            @RequestParam(defaultValue="DAY") ReportGroupBy groupBy) {
        return service.report(type(type),view(view),filter(from,to,branchId,branchIds,roomTypeIds,groupBy)).response();
    }
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String reportType,
            @RequestParam(defaultValue="by-period") String view,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) Long branchId,
            @RequestParam(required=false) List<Long> branchIds,
            @RequestParam(required=false) List<Long> roomTypeIds,
            @RequestParam(defaultValue="DAY") ReportGroupBy groupBy) throws IOException {
        ReportType type=type(reportType);
        ReportView selected="promotions".equals(view)?ReportView.PROMOTION:view(view);
        var result=service.report(type,selected,filter(from,to,branchId,branchIds,roomTypeIds,groupBy));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=report-"+type.name().toLowerCase(Locale.ROOT)+"-"+from+".xlsx")
                .body(exports.excel(result));
    }
    private ReportType type(String value) {
        try {return ReportType.valueOf(value.toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException ex) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown report type");}
    }
    private ReportView view(String value) {
        if(value==null) return ReportView.PROMOTION;
        return switch(value) {
            case "summary" -> ReportView.SUMMARY;
            case "by-period" -> ReportView.PERIOD;
            case "by-branch" -> ReportView.BRANCH;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown report view");
        };
    }
    private ReportFilter filter(LocalDate from,LocalDate to,Long branchId,List<Long> branchIds,List<Long> roomTypeIds,ReportGroupBy groupBy) {
        if(branchId!=null && branchIds!=null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use either branchId or branchIds");
        return new ReportFilter(from,to,branchId==null?branchIds:List.of(branchId),roomTypeIds,groupBy);
    }
}
