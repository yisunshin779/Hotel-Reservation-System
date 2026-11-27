package com.Hotel.Module_Payment.service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name="hotel.payment.expiry-enabled",havingValue="true",matchIfMissing=true)
public class PaymentHoldExpiryJob {
    private final JdbcTemplate jdbc;
    public PaymentHoldExpiryJob(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Scheduled(fixedDelayString="${hotel.payment.expiry-delay-ms:60000}",initialDelayString="${hotel.payment.expiry-delay-ms:60000}")
    @Transactional
    public void expire() { jdbc.queryForObject("SELECT hotel.expire_holds()",Integer.class); }
}
