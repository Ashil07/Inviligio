package com.invigilo.auth;

import com.invigilo.auth.AuthDtos.CollegeOption;
import com.invigilo.auth.AuthDtos.IssuedSession;
import com.invigilo.auth.AuthDtos.LoginRequest;
import com.invigilo.auth.AuthDtos.RegisterCollegeRequest;
import com.invigilo.auth.AuthDtos.SessionInfo;
import com.invigilo.college.College;
import com.invigilo.college.CollegeRepository;
import com.invigilo.common.error.ApiException;
import com.invigilo.identity.AppUser;
import com.invigilo.identity.AppUserRepository;
import com.invigilo.identity.CollegeMembership;
import com.invigilo.identity.CollegeMembershipRepository;
import com.invigilo.identity.Role;
import com.invigilo.tenant.CurrentUser;
import com.invigilo.tenant.SystemTransactions;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, refresh and logout.
 *
 * <p>These flows run before a tenant is known, so they use {@link SystemTransactions}
 * (no class-level {@code @Transactional} here on purpose). BCrypt hashing runs outside
 * transactions to keep connections free during the deliberately slow hash.
 */
@Service
public class AuthService {

    /** A sealed outcome of {@link #login}: either a session or the list of colleges to pick from. */
    public sealed interface LoginResult {
        record Authenticated(IssuedSession session) implements LoginResult {}
        record CollegeSelectionRequired(List<CollegeOption> colleges) implements LoginResult {}
    }

    private final SystemTransactions system;
    private final CollegeRepository colleges;
    private final AppUserRepository users;
    private final CollegeMembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final RefreshTokenService refreshTokens;
    private final Clock clock;
    /** Compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    public AuthService(SystemTransactions system, CollegeRepository colleges, AppUserRepository users,
                       CollegeMembershipRepository memberships, PasswordEncoder passwordEncoder,
                       TokenService tokens, RefreshTokenService refreshTokens, Clock clock) {
        this.system = system;
        this.colleges = colleges;
        this.users = users;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("invigilo-timing-equalizer");
    }

    /** Creates a college in ONBOARDING state with the registrant as its first exam-cell admin, and signs them in. */
    public IssuedSession registerCollege(RegisterCollegeRequest request) {
        String passwordHash = passwordEncoder.encode(request.password());
        return system.execute(() -> {
            String code = College.normalizeCode(request.collegeCode());
            if (colleges.existsByCode(code)) {
                throw ApiException.conflict("COLLEGE_CODE_TAKEN", "A college with this code is already registered");
            }
            String email = AppUser.normalizeEmail(request.adminEmail());
            if (users.existsByEmail(email)) {
                throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists. Sign in instead.");
            }
            College college = colleges.save(new College(code, request.collegeName()));
            AppUser admin = users.save(new AppUser(email, passwordHash, request.adminFullName()));
            CollegeMembership membership = memberships.save(
                    CollegeMembership.forCollege(college, admin, EnumSet.of(Role.EXAM_CELL_ADMIN)));
            admin.recordLogin(clock.instant());
            return issue(membership, refreshTokens.create(membership));
        });
    }

    public LoginResult login(LoginRequest request) {
        String email = AppUser.normalizeEmail(request.email());
        Optional<AppUser> user = system.execute(() -> users.findByEmail(email));
        boolean passwordMatches = passwordEncoder.matches(
                request.password(), user.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !passwordMatches) {
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Incorrect email or password");
        }
        if (!user.get().isActive()) {
            throw ApiException.forbidden("ACCOUNT_DISABLED", "This account has been disabled");
        }

        return system.execute(() -> {
            List<CollegeMembership> usable = memberships.findActiveByUserId(user.get().getId()).stream()
                    .filter(m -> m.getCollege().allowsSignIn())
                    .toList();
            if (usable.isEmpty()) {
                throw ApiException.forbidden("NO_ACTIVE_MEMBERSHIP", "This account has no active college access");
            }
            CollegeMembership chosen;
            if (request.collegeId() != null) {
                chosen = usable.stream()
                        .filter(m -> m.getCollegeId().equals(request.collegeId()))
                        .findFirst()
                        .orElseThrow(() -> ApiException.forbidden(
                                "NO_ACTIVE_MEMBERSHIP", "This account has no active access to that college"));
            } else if (usable.size() == 1) {
                chosen = usable.getFirst();
            } else {
                return new LoginResult.CollegeSelectionRequired(usable.stream().map(CollegeOption::from).toList());
            }
            chosen.getUser().recordLogin(clock.instant());
            return new LoginResult.Authenticated(issue(chosen, refreshTokens.create(chosen)));
        });
    }

    /**
     * Exchanges a refresh token for a new access token (and a rotated refresh token).
     * Revocations made while rejecting a token must be committed, so rejection is signalled by an
     * empty result inside the transaction and only turned into an exception after it.
     */
    public IssuedSession refresh(String rawRefreshToken) {
        Optional<IssuedSession> issued = system.execute(() -> {
            RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
            if (rotation == null) {
                return Optional.<IssuedSession>empty();
            }
            CollegeMembership m = rotation.membership();
            if (!m.isActive() || !m.getUser().isActive() || !m.getCollege().allowsSignIn()) {
                refreshTokens.revokeAll(m);
                return Optional.<IssuedSession>empty();
            }
            return Optional.of(issue(m, rotation.newToken()));
        });
        return issued.orElseThrow(() ->
                ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Session expired. Please sign in again."));
    }

    public void logout(String rawRefreshToken) {
        system.run(() -> refreshTokens.revoke(rawRefreshToken));
    }

    /** Current session as stored now (roles may have changed since the token was issued). Tenant-filtered. */
    @Transactional(readOnly = true)
    public SessionInfo currentSession(CurrentUser currentUser) {
        return memberships.findWithDetailsById(currentUser.membershipId())
                .filter(CollegeMembership::isActive)
                .map(SessionInfo::from)
                .orElseThrow(() -> ApiException.unauthorized("SESSION_INVALID", "Session is no longer valid"));
    }

    private IssuedSession issue(CollegeMembership membership, String refreshToken) {
        TokenService.AccessToken access = tokens.issue(membership);
        return new IssuedSession(SessionInfo.from(membership), access.value(), access.expiresAt(), refreshToken);
    }
}
