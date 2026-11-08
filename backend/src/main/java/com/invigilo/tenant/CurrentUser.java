package com.invigilo.tenant;

import com.invigilo.identity.Role;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The authenticated caller, read from the validated JWT. */
public record CurrentUser(UUID userId, UUID collegeId, UUID membershipId, Set<Role> roles) {

    public static final String CLAIM_COLLEGE = "cid";
    public static final String CLAIM_MEMBERSHIP = "mid";
    public static final String CLAIM_ROLES = "roles";

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    /** @throws IllegalStateException when called outside an authenticated request */
    public static CurrentUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return from(token.getToken());
        }
        throw new IllegalStateException("No authenticated user");
    }

    static CurrentUser from(Jwt jwt) {
        List<String> roleNames = jwt.getClaimAsStringList(CLAIM_ROLES);
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (roleNames != null) {
            roleNames.forEach(r -> roles.add(Role.valueOf(r)));
        }
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString(CLAIM_COLLEGE)),
                UUID.fromString(jwt.getClaimAsString(CLAIM_MEMBERSHIP)),
                Set.copyOf(roles));
    }
}
