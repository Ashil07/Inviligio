package com.invigilo.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.invigilo.college.College;
import com.invigilo.identity.CollegeMembership;
import com.invigilo.identity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of {@link AuthController}. */
public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterCollegeRequest(
            @NotBlank @Size(max = 200) String collegeName,
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$",
                    message = "must be 2-32 letters, digits, '-' or '_'")
            String collegeCode,
            @NotBlank @Size(max = 200) String adminFullName,
            @NotBlank @Email @Size(max = 254) String adminEmail,
            // BCrypt only uses the first 72 bytes of a password.
            @NotBlank @Size(min = 8, max = 72) String password) {}

    /**
     * {@code collegeId} is optional and only chooses among the caller's own verified memberships
     * when they belong to several colleges. It is never used for authorization on its own.
     */
    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password, UUID collegeId) {}

    public record UserInfo(UUID id, String email, String fullName) {}

    public record CollegeInfo(UUID id, String code, String name, College.Status status) {
        static CollegeInfo from(College c) {
            return new CollegeInfo(c.getId(), c.getCode(), c.getName(), c.getStatus());
        }
    }

    /** {@code facultyId} is set when this login belongs to a faculty record. */
    public record SessionInfo(UserInfo user, CollegeInfo college, UUID membershipId, UUID facultyId, List<Role> roles) {
        static SessionInfo from(CollegeMembership m) {
            var u = m.getUser();
            return new SessionInfo(
                    new UserInfo(u.getId(), u.getEmail(), u.getFullName()),
                    CollegeInfo.from(m.getCollege()),
                    m.getId(),
                    m.getFacultyId(),
                    m.getRoles().stream().sorted().toList());
        }
    }

    public record InvitationTokenRequest(@NotBlank @Size(max = 200) String token) {}

    public record AcceptInvitationRequest(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Size(max = 72) String password,
            @Size(max = 200) String fullName) {}

    public record InvitationPreviewResponse(String collegeName, String collegeCode, String facultyName, String email,
                                            List<Role> roles, Instant expiresAt, boolean existingAccount) {}

    public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, SessionInfo session) {
        static AuthResponse from(IssuedSession issued) {
            return new AuthResponse(issued.accessToken(), "Bearer", issued.accessTokenExpiresAt(), issued.session());
        }
    }

    public record CollegeOption(UUID id, String code, String name) {
        static CollegeOption from(CollegeMembership m) {
            return new CollegeOption(m.getCollegeId(), m.getCollege().getCode(), m.getCollege().getName());
        }
    }

    public enum LoginStatus { AUTHENTICATED, COLLEGE_SELECTION_REQUIRED }

    /** Either {@code auth} (AUTHENTICATED) or {@code colleges} (COLLEGE_SELECTION_REQUIRED) is set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoginResponse(LoginStatus status, AuthResponse auth, List<CollegeOption> colleges) {}

    /** Internal result of issuing a session; {@code refreshToken} is null when the cookie must stay unchanged. */
    record IssuedSession(SessionInfo session, String accessToken, Instant accessTokenExpiresAt, String refreshToken) {}
}
