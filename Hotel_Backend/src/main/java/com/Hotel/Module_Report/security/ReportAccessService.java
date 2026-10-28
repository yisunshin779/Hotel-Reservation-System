package com.Hotel.Module_Report.security;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportAccessService {
    private final ReportIdentityService identities;
    public ReportAccessService(ReportIdentityService identities) { this.identities = identities; }
    public ReportIdentityService.Identity current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        var user = identities.find(auth.getName());
        if (user == null || !user.active() || !user.unlocked()
                || !(user.role().equals("ADMIN") || user.role().equals("MANAGER")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Report access denied");
        return user;
    }
    public List<Long> scope(List<Long> requested, List<Long> available) {
        var user = current();
        if (user.role().equals("MANAGER")) {
            if (user.branchId() == null || (!requested.isEmpty() && requested.stream().anyMatch(id -> !id.equals(user.branchId()))))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Branch is outside your report scope");
            return List.of(user.branchId());
        }
        return requested.isEmpty() ? List.copyOf(available) : requested;
    }
}
