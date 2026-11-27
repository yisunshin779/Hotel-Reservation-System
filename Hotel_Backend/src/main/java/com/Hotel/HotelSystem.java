package com.Hotel;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HotelSystem {
    public static void main(String[] args) {
        SpringApplication.run(HotelSystem.class, args);
    }

    @Bean
    public Clock hotelClock() { return Clock.systemUTC(); }
}
