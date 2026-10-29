package com.Hotel.Module_Report.security;

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
    SecurityFilterChain reportSecurity(HttpSecurity http, ReportIdentityService users) throws Exception {
        return http.securityMatcher("/api/reports/**").userDetailsService(users)
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAnyRole("ADMIN", "MANAGER"))
                .httpBasic(basic -> basic.authenticationEntryPoint((request,response,error) -> {
                    response.setStatus(401); response.setHeader("WWW-Authenticate", "Basic realm=\"Reports\"");
                    response.setContentType("application/json");
                    response.getWriter().write("{\"status\":401,\"message\":\"Authentication required\"}");
                }))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request,response,error) -> {
                    response.setStatus(403); response.setContentType("application/json");
                    response.getWriter().write("{\"status\":403,\"message\":\"Report access denied\"}");
                })).build();
    }
    // No changes to the access rules of modules outside /api/reports.
    @Bean @Order(2)
    SecurityFilterChain otherModules(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }
}
