package com.Hotel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

@SpringBootApplication
public class HotelSystem {
    public static void main(String[] args) { SpringApplication.run(HotelSystem.class, args); }
    @Bean public Clock hotelClock() { return Clock.systemUTC(); }
}
