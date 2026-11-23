package com.Hotel.Module_CheckInCheckOut.dto;

import com.Hotel.Module_CheckInCheckOut.entity.Bill;
import java.math.BigDecimal;
import java.util.List;

public record BillResponseDTO(BigDecimal roomAmount, BigDecimal serviceAmount, BigDecimal surchargeAmount,
                              BigDecimal discountAmount, BigDecimal grossAmount, BigDecimal paidAmount,
                              BigDecimal refundedAmount, BigDecimal remainingAmount, BigDecimal refundAmount,
                              List<InvoiceLineDTO> lines, BigDecimal taxRate, BigDecimal taxAmount) {
    public static BillResponseDTO from(Bill bill) {
        return new BillResponseDTO(bill.roomAmount(), bill.serviceAmount(), bill.surchargeAmount(),
                bill.discountAmount(), bill.grossAmount(), bill.paidAmount(), bill.refundedAmount(),
                bill.remainingAmount(), bill.refundAmount(),
                bill.lines().stream().map(InvoiceLineDTO::from).toList(), bill.taxRate(), bill.taxAmount());
    }
}
