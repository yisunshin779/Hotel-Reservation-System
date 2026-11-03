package com.Hotel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // Bắt buộc phải có để đánh thức luồng chạy ngầm
public class HotelSystem {
    public static void main(String[] args) {
        SpringApplication.run(HotelSystem.class, args);
        System.out.println("====== HỆ THỐNG HRS ĐÃ KHỞI ĐỘNG THÀNH CÔNG ======");
    }
}