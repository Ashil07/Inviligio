package com.invigilo.access;

import com.invigilo.audit.AuditService;
import com.invigilo.auth.SessionRevoker;
import com.invigilo.college.College;
import com.invigilo.college.CollegeRepository;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.UsageCheck;
import com.invigilo.common.security.Tokens;
import com.invigilo.faculty.Faculty;
import com.invigilo.faculty.FacultyService;
import com.invigilo.identity.AppUser;
import com.invigilo.identity.AppUserRepository;
import com.invigilo.identity.CollegeMembership;
import com.invigilo.identity.CollegeMembershipRepository;
import com.invigilo.identity.Role;
import com.invigilo.tenant.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logins for faculty members: invitations, accepting them, and the exam cell's control over who
 * may sign in with which roles.
 *
 * <p>Invariants: every faculty-linked login holds FACULTY; the exam cell can never remove the
 * last active EXAM_CELL_ADMIN or change its own access (no self-lock-out).
 */
@Service
public class AccessService {

    public static final Duration INVITATION_TTL = Duration.ofDays(7);

    public enum Status { NONE, INVITED, ACTIVE, DISABLED }

    public record InvitationInfo(String email, List<Role> roles, Instant createdAt, Instant expiresAt) {}

    public record AccessDetail(Status status, List<Role> roles, String loginEmail, InvitationInfo invitation,
                               boolean currentUser) {}

    /** The raw token is returned exactly once, when the invitation is created. */
    public record IssuedInvitation(String token, Instant expiresAt, AccessDetail access) {}

    /** What an invitee sees before accepting. {@code existingPasswordHash} never leaves the backend. */
    public record InvitationPreview(UUID collegeId, String collegeName, String collegeCode, String facultyName,
                                    String email, List<Role> roles, Instant expiresAt, boolean existingAccount,
                                    String existingPasswordHash) {}

    private final InvitationRepository invitations;
    private final CollegeMembershipRepository memberships;
    private final AppUserRepository users;
    private final CollegeRepository colleges;
    private final FacultyService faculty;
    private final SessionRevoker sessions;
    private final AuditService audit;
    private final Clock clock;

    public AccessService(InvitationRepository invitations, CollegeMembershipRepository memberships,
                         AppUserRepository users, CollegeRepository colleges, FacultyService faculty,
                         SessionRevoker sessions, AuditService audit, Clock clock) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.users = users;
        this.colleges = colleges;
        this.faculty = faculty;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- exam cell (tenant context) ----

    @Transactional(readOnly = true)
    public AccessDetail access(UUID facultyId, CurrentUser viewer) {
        faculty.find(facultyId);
        return detail(facultyId, viewer);
    }

    @Transactional
    public IssuedInvitation invite(UUID facultyId, Set<Role> requestedRoles, CurrentUser actor) {
        Faculty f = faculty.find(facultyId);
        if (!f.isActive()) {
            throw ApiException.unprocessable("FACULTY_INACTIVE", "Activate this faculty member before inviting them");
        }
        if (f.getEmail() == null) {
            throw ApiException.unprocessable("FACULTY_EMAIL_REQUIRED", "Add an email address before sending an invitation");
        }
        if (memberships.findByFacultyId(facultyId).isPresent()) {
            throw ApiException.conflict("ALREADY_HAS_ACCESS", "This faculty member already has a login");
        }
        Instant now = clock.instant();
        // A new invitation replaces any earlier one still pending.
        invitations.findByFacultyIdAndAcceptedAtIsNullAndRevokedAtIsNull(facultyId).forEach(i -> i.revoke(now));
        Set<Role> roles = withFaculty(requestedRoles);
        String token = Tokens.newToken();
        Invitation invitation = invitations.saveAndFlush(new Invitation(f, f.getEmail(), roles, Tokens.sha256Hex(token),
                now.plus(INVITATION_TTL), actor.userId()));
        audit.record("INVITATION_CREATED", "faculty", facultyId, null,
                new InvitationInfo(f.getEmail(), sorted(roles), now, invitation.getExpiresAt()), null);
        return new IssuedInvitation(token, invitation.getExpiresAt(), detail(facultyId, actor));
    }

    @Transactional
    public AccessDetail revokeInvitation(UUID facultyId, CurrentUser actor) {
        faculty.find(facultyId);
        Instant now = clock.instant();
        var pending = invitations.findByFacultyIdAndAcceptedAtIsNullAndRevokedAtIsNull(facultyId);
        if (pending.isEmpty()) {
            throw ApiException.notFound("INVITATION_NOT_FOUND", "There is no pending invitation");
        }
        pending.forEach(i -> i.revoke(now));
        audit.record("INVITATION_REVOKED", "faculty", facultyId, null, null, null);
        return detail(facultyId, actor);
    }

    @Transactional
    public AccessDetail updateAccess(UUID facultyId, Set<Role> requestedRoles, boolean active, CurrentUser actor) {
        faculty.find(facultyId);
        CollegeMembership m = memberships.findByFacultyId(facultyId)
                .orElseThrow(() -> ApiException.notFound("NO_LOGIN", "This faculty member has no login yet"));
        Set<Role> roles = withFaculty(requestedRoles);
        boolean wasActiveAdmin = m.isActive() && m.getRoles().contains(Role.EXAM_CELL_ADMIN);
        boolean staysActiveAdmin = active && roles.contains(Role.EXAM_CELL_ADMIN);
        if (m.getId().equals(actor.membershipId()) && (!staysActiveAdmin || !roles.equals(m.getRoles()) || !active)) {
            throw ApiException.unprocessable("CANNOT_CHANGE_OWN_ACCESS",
                    "You cannot change your own access. Ask another exam cell administrator.");
        }
        if (wasActiveAdmin && !staysActiveAdmin && memberships.countActiveAdmins() <= 1) {
            throw ApiException.unprocessable("LAST_ADMIN", "The college must keep at least one active exam cell administrator");
        }
        AccessDetail before = detail(facultyId, actor);
        m.updateAccess(roles, active);
        if (!active) {
            sessions.revokeAll(m.getId());
        }
        memberships.flush();
        AccessDetail after = detail(facultyId, actor);
        audit.record("ACCESS_UPDATED", "faculty", facultyId, before, after, null);
        return after;
    }

    // ---- invitee (system context, called by AuthService) ----

    @Transactional(propagation = Propagation.MANDATORY)
    public InvitationPreview preview(String rawToken) {
        Invitation inv = usable(rawToken);
        College college = colleges.findById(inv.getCollegeId()).orElseThrow(AccessService::invalid);
        Optional<AppUser> existing = users.findByEmail(inv.getEmail());
        return new InvitationPreview(college.getId(), college.getName(), college.getCode(), inv.getFaculty().getFullName(),
                inv.getEmail(), sorted(inv.getRoles()), inv.getExpiresAt(), existing.isPresent(),
                existing.map(AppUser::getPasswordHash).orElse(null));
    }

    /**
     * Consumes the invitation and returns the (new or existing) membership linked to the faculty
     * record. {@code newUserPasswordHash} is used only when no account exists for the email yet.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CollegeMembership accept(String rawToken, String newUserPasswordHash, String fullName) {
        Invitation inv = usable(rawToken);
        Faculty f = inv.getFaculty();
        if (!f.isActive() || memberships.countByFacultyId(f.getId()) > 0) {
            throw invalid();
        }
        College college = colleges.findById(inv.getCollegeId()).orElseThrow(AccessService::invalid);
        AppUser user = users.findByEmail(inv.getEmail()).orElseGet(() -> {
            if (newUserPasswordHash == null) throw invalid();
            String name = fullName == null || fullName.isBlank() ? f.getFullName() : fullName;
            return users.save(new AppUser(inv.getEmail(), newUserPasswordHash, name));
        });
        CollegeMembership membership = memberships.findByUserIdAndCollegeId(user.getId(), college.getId())
                .map(existing -> {
                    if (!existing.isActive()) {
                        throw ApiException.forbidden("ACCOUNT_DISABLED",
                                "Your access to this college is disabled. Contact the examination cell.");
                    }
                    if (existing.getFacultyId() != null) throw invalid();
                    existing.linkFaculty(f.getId(), inv.getRoles());
                    return existing;
                })
                .orElseGet(() -> {
                    CollegeMembership m = CollegeMembership.forCollege(college, user, inv.getRoles());
                    m.linkFaculty(f.getId(), inv.getRoles());
                    return memberships.save(m);
                });
        inv.accept(clock.instant());
        memberships.flush();
        audit.recordForCollege(college.getId(), user.getId(), "INVITATION_ACCEPTED", "faculty", f.getId(), null,
                sorted(membership.getRoles()), null);
        return membership;
    }

    // ---- helpers ----

    private Invitation usable(String rawToken) {
        return invitations.findByTokenHash(Tokens.sha256Hex(rawToken))
                .filter(i -> i.isPending(clock.instant()))
                .orElseThrow(AccessService::invalid);
    }

    private AccessDetail detail(UUID facultyId, CurrentUser viewer) {
        var membership = memberships.findByFacultyId(facultyId);
        if (membership.isPresent()) {
            CollegeMembership m = membership.get();
            return new AccessDetail(m.isActive() ? Status.ACTIVE : Status.DISABLED, sorted(m.getRoles()),
                    m.getUser().getEmail(), null, viewer != null && m.getId().equals(viewer.membershipId()));
        }
        Instant now = clock.instant();
        return invitations.findByFacultyIdAndAcceptedAtIsNullAndRevokedAtIsNull(facultyId).stream()
                .filter(i -> i.isPending(now))
                .max(Comparator.comparing(Invitation::getCreatedAt))
                .map(i -> new AccessDetail(Status.INVITED, sorted(i.getRoles()), null,
                        new InvitationInfo(i.getEmail(), sorted(i.getRoles()), i.getCreatedAt(), i.getExpiresAt()), false))
                .orElse(new AccessDetail(Status.NONE, List.of(), null, null, false));
    }

    private static Set<Role> withFaculty(Set<Role> roles) {
        Set<Role> all = EnumSet.of(Role.FACULTY);
        if (roles != null) all.addAll(roles);
        return all;
    }

    private static List<Role> sorted(Set<Role> roles) {
        return roles.stream().sorted().toList();
    }

    private static ApiException invalid() {
        return ApiException.notFound("INVITATION_INVALID",
                "This invitation link is invalid or has expired. Ask your examination cell for a new one.");
    }

    /** A faculty member with a login cannot be deleted (deactivate them instead). */
    @Component
    static class LoginUsage implements UsageCheck<Faculty> {
        private final CollegeMembershipRepository memberships;

        LoginUsage(CollegeMembershipRepository memberships) {
            this.memberships = memberships;
        }

        @Override
        public String usedBy() {
            return "login account(s)";
        }

        @Override
        public long countUsages(UUID facultyId) {
            return memberships.countByFacultyId(facultyId);
        }
    }
}
