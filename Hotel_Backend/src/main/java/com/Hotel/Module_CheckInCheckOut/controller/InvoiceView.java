package com.Hotel.Module_CheckInCheckOut.controller;

import com.Hotel.Module_CheckInCheckOut.dto.InvoiceResponseDTO;
import org.springframework.web.util.HtmlUtils;

/** A printable export of the closed invoice snapshot, without guest identity information. */
final class InvoiceView {
    private InvoiceView() {}
    static String html(InvoiceResponseDTO invoice) {
        StringBuilder rows=new StringBuilder();
        for(var line:invoice.lines()) rows.append("<tr><td>").append(HtmlUtils.htmlEscape(line.description()))
            .append("</td><td>").append(line.quantity().toPlainString()).append("</td><td>")
            .append(line.unitPrice().toPlainString()).append("</td><td>").append(line.amount().toPlainString()).append("</td></tr>");
        return """
            <!doctype html><html lang="vi"><meta charset="utf-8"><title>Hóa đơn lưu trú</title>
            <style>body{font:16px system-ui;margin:40px;color:#222}table{border-collapse:collapse;width:100%%}
            th,td{border-bottom:1px solid #ccc;padding:10px;text-align:right}th:first-child,td:first-child{text-align:left}
            @media print{body{margin:0}}</style>
            <h1>Hóa đơn lưu trú</h1><p>Số hóa đơn: %s</p><p>Mã đơn: %d · Trạng thái: CLOSED</p>
            <p>Ngày lập (UTC): %s</p><table><thead><tr><th>Nội dung</th><th>Số lượng</th><th>Đơn giá (VND)</th><th>Thành tiền (VND)</th></tr></thead>
            <tbody>%s</tbody></table><h2>Tổng cộng: %s VND</h2><p>Bản kê lưu trú của hệ thống mô phỏng.</p></html>
            """.formatted(HtmlUtils.htmlEscape(invoice.number()),invoice.reservationId(),invoice.issuedAt(),rows,invoice.total().toPlainString());
    }
}
