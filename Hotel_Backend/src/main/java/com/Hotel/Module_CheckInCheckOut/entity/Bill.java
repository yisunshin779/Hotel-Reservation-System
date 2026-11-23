package com.Hotel.Module_CheckInCheckOut.entity;

import java.math.BigDecimal;
import java.util.List;

/** Amounts are snapshots from the booking/service usage, never current catalogue prices. */
public record Bill(BigDecimal roomAmount, BigDecimal serviceAmount, BigDecimal surchargeAmount,
                   BigDecimal discountAmount, BigDecimal grossAmount, BigDecimal paidAmount,
                   BigDecimal refundedAmount, BigDecimal remainingAmount, BigDecimal refundAmount,
                   List<InvoiceLine> lines, BigDecimal taxRate, BigDecimal taxAmount) {
    public static Bill calculate(BigDecimal room, BigDecimal discount, List<InvoiceLine> services,
                                 List<InvoiceLine> extras, BigDecimal payments, BigDecimal refunds) {
        return calculate(List.of(new InvoiceLine("ROOM", "Tiền phòng theo giá đã chốt", BigDecimal.ONE, room, room)),
                discount, services, extras, payments, refunds, BigDecimal.ZERO);
    }
    public static Bill calculate(List<InvoiceLine> rooms, BigDecimal discount, List<InvoiceLine> services,
                                 List<InvoiceLine> extras, BigDecimal payments, BigDecimal refunds, BigDecimal taxRate) {
        BigDecimal room = sum(rooms), service = sum(services), extra = sum(extras);
        BigDecimal net = room.add(service).add(extra).subtract(discount);
        if (net.signum() < 0) throw StayException.conflict("NEGATIVE_INVOICE_TOTAL", "Các khoản điều chỉnh làm tổng hóa đơn âm; cần kiểm tra lại.");
        if (taxRate == null || taxRate.signum()<0 || taxRate.compareTo(BigDecimal.ONE)>0)
            throw StayException.conflict("INVALID_TAX_POLICY", "Thuế suất cấu hình không hợp lệ.");
        BigDecimal tax = net.multiply(taxRate).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal gross = net.add(tax), balance = gross.subtract(payments).add(refunds);
        var lines = new java.util.ArrayList<>(rooms);
        lines.addAll(services); lines.addAll(extras);
        if (discount.signum() > 0) lines.add(new InvoiceLine("DISCOUNT", "Khuyến mãi đã chốt", BigDecimal.ONE, discount.negate(), discount.negate()));
        if (tax.signum()>0) lines.add(new InvoiceLine("TAX", "Thuế theo chính sách đã chốt", BigDecimal.ONE, tax, tax));
        return new Bill(room, service, extra, discount, gross, payments, refunds, balance,
                balance.negate().max(BigDecimal.ZERO), List.copyOf(lines), taxRate, tax);
    }
    private static BigDecimal sum(List<InvoiceLine> lines) {
        return lines.stream().map(InvoiceLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    public void requireSettled() {
        if (remainingAmount.signum() != 0)
            throw StayException.conflict("UNSETTLED_BALANCE", "Phải thu đủ hoặc hoàn phần dư trước khi chốt hóa đơn.");
    }
}
