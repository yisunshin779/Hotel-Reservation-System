package com.Hotel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * #12 transition switch for HTTP Basic on the hotel.users chains (Payment, Stay, Report).
 * Enabled by default; with HOTEL_BASIC_AUTH_ENABLED=false only linked Bearer tokens authenticate.
 * The 401 response body stays the same in both modes.
 */
@Component
public class BasicAuthPolicy {
    private final boolean enabled;

    public BasicAuthPolicy(@Value("${hotel.security.basic-auth-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void apply(HttpSecurity http, String realm, String body) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, error) -> {
            response.setStatus(401);
            if (enabled) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
            }
            response.setContentType("application/json");
            response.getWriter().write(body);
        };
        http.exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint));
        if (enabled) {
            http.httpBasic(basic -> basic.authenticationEntryPoint(entryPoint));
        }
    }
}
