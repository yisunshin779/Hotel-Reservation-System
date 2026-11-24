package com.Hotel.Module_DatPhong.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Canonical search result. The first four fields keep the JSON names of the legacy RoomType result.
 *
 * @param tongSoPhong  rooms of this type free on every night of the stay
 * @param giaTienCoBan base price per room and night (room_types.base_price)
 * @param soLuongPhong rooms priced in {@code tongGiaKyO}: the requested count, or the minimum that fits the guests
 * @param soDem        nights in the stay
 * @param tongGiaKyO   sum of hotel.nightly_rate for each night times {@code soLuongPhong}, the amount hold quotes
 * @param giaTheoDem   price per room for each night of the stay (OC-05.01)
 */
public record RoomAvailabilityDTO(
        String id,
        String tenLoaiPhong,
        int tongSoPhong,
        BigDecimal giaTienCoBan,
        int sucChua,
        int soLuongPhong,
        int soDem,
        BigDecimal tongGiaKyO,
        List<NightPrice> giaTheoDem) {

    public record NightPrice(LocalDate ngay, BigDecimal gia) {}
}
