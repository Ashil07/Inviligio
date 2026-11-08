package com.invigilo.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the {@link TenantContext} from the authenticated token's {@code cid} claim.
 * Registered inside the security chain right after bearer-token authentication (see
 * SecurityConfig) — deliberately not a {@code @Component}, so it is not also registered as a
 * plain servlet filter running before authentication.
 */
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken token) {
            String cid = token.getToken().getClaimAsString(CurrentUser.CLAIM_COLLEGE);
            if (cid == null) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            TenantContext.set(UUID.fromString(cid));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
