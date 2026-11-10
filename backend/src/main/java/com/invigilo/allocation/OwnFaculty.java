package com.invigilo.allocation;

import com.invigilo.common.error.ApiException;
import com.invigilo.department.Department;
import com.invigilo.faculty.FacultyService;
import com.invigilo.identity.CollegeMembership;
import com.invigilo.identity.CollegeMembershipRepository;
import com.invigilo.tenant.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The caller's own faculty record and, for a HOD, their department (the department of that record). */
@Component
class OwnFaculty {

    private final CollegeMembershipRepository memberships;
    private final FacultyService faculty;

    OwnFaculty(CollegeMembershipRepository memberships, FacultyService faculty) {
        this.memberships = memberships;
        this.faculty = faculty;
    }

    Optional<UUID> id(CurrentUser user) {
        return memberships.findById(user.membershipId()).map(CollegeMembership::getFacultyId);
    }

    UUID requireId(CurrentUser user) {
        return id(user).orElseThrow(() -> ApiException.forbidden("NOT_A_FACULTY_MEMBER", "Your login is not linked to a faculty record"));
    }

    Department requireHodDepartment(CurrentUser user) {
        UUID facultyId = id(user).orElseThrow(() -> ApiException.forbidden("NO_DEPARTMENT_SCOPE",
                "Only a head of department linked to a faculty record has a department"));
        return faculty.find(facultyId).getDepartment();
    }
}
