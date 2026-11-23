package com.Hotel.Module_Payment.security;
import com.Hotel.Module_Report.security.ReportIdentityService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class PaymentSecurityConfig {
    @Bean @Order(0)
    SecurityFilterChain paymentSecurity(HttpSecurity http,ReportIdentityService users,
            @Qualifier("reportPasswordEncoder") PasswordEncoder encoder) throws Exception {
        var provider=new DaoAuthenticationProvider(); provider.setUserDetailsService(users); provider.setPasswordEncoder(encoder);
        return http.securityMatcher("/api/reservations/*/payments","/api/reservations/*/payments/**")
            .authenticationManager(new ProviderManager(provider))
            .csrf(csrf->csrf.disable()).requestCache(cache->cache.disable())
            .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth->auth.anyRequest().authenticated())
            .httpBasic(basic->basic.authenticationEntryPoint((request,response,error)->{
                response.setStatus(401); response.setHeader("WWW-Authenticate","Basic realm=\"Payments\"");
                response.setContentType("application/json"); response.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication required\"}");
            })).build();
    }
}
