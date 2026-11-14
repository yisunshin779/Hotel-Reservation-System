package com.Hotel.Module_DatPhong.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private String redisPort;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        
        // Cú pháp bắt buộc của Redisson là redis://host:port
        String redisAddress = String.format("redis://%s:%s", redisHost, redisPort);
        
        config.useSingleServer()
              .setAddress(redisAddress)
              .setConnectionMinimumIdleSize(10) // Duy trì sẵn 10 kết nối để phản hồi tức thì
              .setConnectionPoolSize(50);       // Giới hạn pool tối đa chống quá tải

        return Redisson.create(config);
    }
}