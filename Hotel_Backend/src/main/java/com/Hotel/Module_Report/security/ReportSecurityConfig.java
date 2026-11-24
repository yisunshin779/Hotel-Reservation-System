package com.Hotel.Module_Report.security;

import com.Hotel.Module_TaiKhoan.security.LinkedHotelIdentity;
import com.Hotel.config.BasicAuthPolicy;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ReportSecurityConfig {
    @Bean PasswordEncoder reportPasswordEncoder() {
        DelegatingPasswordEncoder encoder = (DelegatingPasswordEncoder) PasswordEncoderFactories.createDelegatingPasswordEncoder();
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }
    @Bean @Order(1)
    SecurityFilterChain reportSecurity(HttpSecurity http, ReportIdentityService users, LinkedHotelIdentity linkedIdentity,
                                       BasicAuthPolicy basic) throws Exception {
        var provider = new org.springframework.security.authentication.dao.DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(reportPasswordEncoder());
        linkedIdentity.configure(http);
        http.securityMatcher("/api/reports/**").authenticationManager(new org.springframework.security.authentication.ProviderManager(provider))
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAnyRole("ADMIN", "MANAGER"))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request,response,error) -> {
                    response.setStatus(403); response.setContentType("application/json");
                    response.getWriter().write("{\"status\":403,\"message\":\"Report access denied\"}");
                }));
        basic.apply(http, "Reports", "{\"status\":401,\"message\":\"Authentication required\"}");
        return http.build();
    }
}
