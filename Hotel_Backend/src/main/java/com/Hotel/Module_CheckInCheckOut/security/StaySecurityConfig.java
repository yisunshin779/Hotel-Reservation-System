package com.Hotel.Module_CheckInCheckOut.security;

import com.Hotel.Module_Report.security.ReportIdentityService;
import com.Hotel.Module_TaiKhoan.security.LinkedHotelIdentity;
import com.Hotel.config.BasicAuthPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/** Authenticate branch staff against the existing hotel.users identity store. */
@Configuration
public class StaySecurityConfig {
    @Bean
    @Order(2)
    SecurityFilterChain staySecurity(HttpSecurity http, ReportIdentityService users,
            @Qualifier("reportPasswordEncoder") PasswordEncoder encoder, LinkedHotelIdentity linkedIdentity,
            BasicAuthPolicy basic) throws Exception {
        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        linkedIdentity.configure(http);
        http.securityMatcher("/api/reservations/**", "/api/v1/housekeeping/rooms/**")
                .authenticationManager(new org.springframework.security.authentication.ProviderManager(provider))
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/housekeeping/rooms/**").hasRole("HOUSEKEEPING")
                    .requestMatchers("/api/reservations/*/checkout/invoice/adjustments",
                                     "/api/reservations/*/checkout/invoice/adjustments/**").hasRole("ADMIN")
                    .anyRequest().hasRole("RECEPTIONIST"))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, error) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Insufficient permissions\"}");
                }));
        basic.apply(http, "Reception", "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication required\"}");
        return http.build();
    }
}
