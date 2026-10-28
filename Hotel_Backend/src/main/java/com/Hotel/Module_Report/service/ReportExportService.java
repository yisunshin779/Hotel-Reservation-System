package com.Hotel.Module_Report.service;

import com.Hotel.Module_Report.dto.*;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ReportExportService {
    public byte[] excel(ReportResult result) throws IOException {
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var dateStyle=workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            var numberStyle=workbook.createCellStyle();
            numberStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            var meta=workbook.createSheet("Report info");
            Object[][] metadata={
                {"Report",result.reportType().name()},{"View",result.view().name()},
                {"From",result.filter().from()},{"To (inclusive)",result.filter().to()},
                {"Branch IDs",result.filter().branchIds().toString()},
                {"Room type IDs",result.filter().roomTypeIds().toString()},
                {"Group by",result.filter().groupBy().name()},{"Timezone","Asia/Ho_Chi_Minh"},
                {"Has data",result.hasData()},{"Currency","VND"}
            };
            for(int i=0;i<metadata.length;i++) {
                var row=meta.createRow(i);cell(row,0,metadata[i][0],workbook);cell(row,1,metadata[i][1],workbook);
            }
            var summary=workbook.createSheet("Summary");
            int index=0;
            for(var entry:result.summary().entrySet()) {
                var row=summary.createRow(index++);cell(row,0,entry.getKey(),workbook);cell(row,1,entry.getValue(),workbook);
            }
            var sheet=workbook.createSheet("Data");
            List<Map<String,Object>> items=result.items();
            if(result.view()==ReportView.SUMMARY) items=List.of(new LinkedHashMap<>(result.summary()));
            List<String> keys=items.isEmpty()?new ArrayList<>(result.summary().keySet()):new ArrayList<>(items.get(0).keySet());
            var header=sheet.createRow(0);
            var headerStyle=workbook.createCellStyle();var font=workbook.createFont();font.setBold(true);headerStyle.setFont(font);
            for(int col=0;col<keys.size();col++) {var c=header.createCell(col);c.setCellValue(keys.get(col));c.setCellStyle(headerStyle);}
            for(int i=0;i<items.size();i++) {
                var row=sheet.createRow(i+1);
                for(int col=0;col<keys.size();col++) cell(row,col,items.get(i).get(keys.get(col)),workbook);
            }
            sheet.createFreezePane(0,1);
            if(!keys.isEmpty()) sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,items.size(),0,keys.size()-1));
            for(var s:workbook) for(int col=0;col<(s==sheet?keys.size():2);col++) s.setColumnWidth(col,24*256);
            workbook.write(output);return output.toByteArray();
        }
    }
    private void cell(Row row,int column,Object value,Workbook workbook) {
        var cell=row.createCell(column);
        if(value==null) return;
        if(value instanceof LocalDate date) {
            cell.setCellValue(date);cell.setCellStyle(workbook.getCellStyleAt(1));
        } else if(value instanceof Number number) {
            cell.setCellValue(number.doubleValue());cell.setCellStyle(workbook.getCellStyleAt(2));
        } else if(value instanceof Boolean bool) cell.setCellValue(bool);
        else cell.setCellValue(value.toString());
    }
}
