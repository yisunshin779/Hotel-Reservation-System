package com.Hotel.Module_DatPhong.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.Hotel.Module_DatPhong.entity.RoomType;

@Repository
public interface RoomTypeRepository extends JpaRepository<RoomType, String> {
    
    // Truy vấn tối ưu của kiến trúc sỹ: Chỉ lấy đúng cột tongSoPhong thay vì kéo toàn bộ dữ liệu Entity lên RAM
    @Query("SELECT rt.tongSoPhong FROM RoomType rt WHERE rt.id = :maLoai")
    Integer getTotalRoomsByMaLoai(@Param("maLoai") String maLoai);
}