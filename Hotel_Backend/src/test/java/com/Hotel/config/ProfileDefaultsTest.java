package com.Hotel.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.Hotel.Module_DatPhong.service.StayLengthPolicy;

/** Defaults resolved from the real application*.yaml files for each profile. */
class ProfileDefaultsTest {
    private ApplicationContextRunner runner(String profile) {
        var runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withBean(MockPaymentPolicy.class)
                .withBean(StayLengthPolicy.class);
        return profile == null ? runner : runner.withPropertyValues("spring.profiles.active=" + profile);
    }

    @Test
    void productionProfileDisablesSimulatedPaymentsByDefault() {
        runner("production").run(context -> assertFalse(context.getBean(MockPaymentPolicy.class).enabled()));
        runner("production").withPropertyValues("hotel.payment.mock-methods-enabled=true")
                .run(context -> assertTrue(context.getBean(MockPaymentPolicy.class).enabled()));
        for (String profile : new String[] {null, "development", "report-demo"}) {
            runner(profile).run(context -> assertTrue(context.getBean(MockPaymentPolicy.class).enabled(), profile));
        }
        var policy = new MockPaymentPolicy(false);
        assertTrue(policy.allows("CASH"));
        assertFalse(policy.allows("MOCK_QR"));
        assertFalse(policy.allows("MOCK_CARD"));
    }

    @Test
    void smtpTimeoutsAreFiniteInEveryProfile() {
        for (String profile : new String[] {null, "development", "production", "report-demo"}) {
            runner(profile).run(context -> {
                var properties = ((JavaMailSenderImpl) context.getBean(org.springframework.mail.javamail.JavaMailSender.class))
                        .getJavaMailProperties();
                for (String key : List.of("mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout")) {
                    long value = Long.parseLong(properties.getProperty(key));
                    assertTrue(value > 0 && value <= 60_000, profile + " " + key + "=" + value);
                }
            });
        }
    }

    @Test
    void onlyTheReportDemoProfileStartsInReportOnlySchemaMode() {
        for (String profile : new String[] {null, "development", "production"}) {
            runner(profile).run(context -> assertEquals(SchemaCompatibilityCheck.OPERATIONAL,
                    context.getEnvironment().getProperty("hotel.schema.mode"), String.valueOf(profile)));
        }
        runner("report-demo").run(context -> assertEquals(SchemaCompatibilityCheck.REPORT_ONLY,
                context.getEnvironment().getProperty("hotel.schema.mode")));
    }

    @Test
    void stayLimitDefaultsToNinetyNights() {
        runner(null).run(context -> assertEquals(90, context.getBean(StayLengthPolicy.class).maxNights()));
        runner(null).withPropertyValues("hotel.booking.max-nights=0")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
