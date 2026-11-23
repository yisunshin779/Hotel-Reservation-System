package com.Hotel.Module_CheckInCheckOut.security;

import com.Hotel.Module_Report.security.ReportIdentityService;
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
            @Qualifier("reportPasswordEncoder") PasswordEncoder encoder) throws Exception {
        var provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        return http.securityMatcher("/api/reservations/**")
                .authenticationManager(new org.springframework.security.authentication.ProviderManager(provider))
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/reservations/*/checkout/invoice/adjustments",
                                     "/api/reservations/*/checkout/invoice/adjustments/**").hasRole("ADMIN")
                    .anyRequest().hasRole("RECEPTIONIST"))
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, error) -> {
                    response.setStatus(401);
                    response.setHeader("WWW-Authenticate", "Basic realm=\"Reception\"");
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication required\"}");
                }))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, error) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Insufficient permissions\"}");
                })).build();
    }
}
