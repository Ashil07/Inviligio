package com.invigilo.access;

import com.invigilo.access.AccessService.AccessDetail;
import com.invigilo.access.AccessService.IssuedInvitation;
import com.invigilo.identity.Role;
import com.invigilo.tenant.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Exam-cell management of a faculty member's login. FACULTY is always included in the roles. */
@RestController
@RequestMapping("/api/v1/faculty/{facultyId}/access")
@PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
public class AccessController {

    public record InviteRequest(Set<Role> roles) {}

    public record UpdateAccessRequest(@NotNull Set<Role> roles, @NotNull Boolean active) {}

    private final AccessService access;

    public AccessController(AccessService access) {
        this.access = access;
    }

    @GetMapping
    public AccessDetail get(@PathVariable UUID facultyId) {
        return access.access(facultyId, CurrentUser.require());
    }

    @PostMapping("/invitation")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedInvitation invite(@PathVariable UUID facultyId, @RequestBody(required = false) InviteRequest request) {
        return access.invite(facultyId, request == null ? Set.of() : request.roles(), CurrentUser.require());
    }

    @DeleteMapping("/invitation")
    public AccessDetail revoke(@PathVariable UUID facultyId) {
        return access.revokeInvitation(facultyId, CurrentUser.require());
    }

    @PutMapping
    public AccessDetail update(@PathVariable UUID facultyId, @Valid @RequestBody UpdateAccessRequest request) {
        return access.updateAccess(facultyId, request.roles(), request.active(), CurrentUser.require());
    }
}
