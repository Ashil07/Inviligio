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
        return find().orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }

    /** The caller, or empty outside an authenticated request (e.g. login, background work). */
    public static java.util.Optional<CurrentUser> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return java.util.Optional.of(from(token.getToken()));
        }
        return java.util.Optional.empty();
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
