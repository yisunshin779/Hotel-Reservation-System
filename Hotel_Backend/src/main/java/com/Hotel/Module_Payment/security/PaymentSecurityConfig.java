package com.Hotel.Module_Payment.security;
import com.Hotel.Module_Report.security.ReportIdentityService;
import com.Hotel.Module_TaiKhoan.security.LinkedHotelIdentity;
import com.Hotel.config.BasicAuthPolicy;
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
            @Qualifier("reportPasswordEncoder") PasswordEncoder encoder, LinkedHotelIdentity linkedIdentity,
            BasicAuthPolicy basic) throws Exception {
        var provider=new DaoAuthenticationProvider(); provider.setUserDetailsService(users); provider.setPasswordEncoder(encoder);
        linkedIdentity.configure(http);
        http.securityMatcher("/api/reservations/*/payments","/api/reservations/*/payments/**",
                "/api/reservations/*/cancel")
            .authenticationManager(new ProviderManager(provider))
            .csrf(csrf->csrf.disable()).requestCache(cache->cache.disable())
            .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth->auth.anyRequest().authenticated());
        basic.apply(http,"Payments","{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication required\"}");
        return http.build();
    }
}
