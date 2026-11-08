package com.invigilo.college;

import com.invigilo.college.CollegeService.CollegeDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own college. There is deliberately no college id in the path. */
@RestController
@RequestMapping("/api/v1/college")
public class CollegeController {

    public record UpdateCollegeRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 300) String affiliation,
            @NotBlank @Size(max = 500) String address,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 100) String state,
            @Size(max = 20) String postalCode,
            @Email @Size(max = 254) String contactEmail,
            @Size(max = 30) String contactPhone,
            @NotBlank @Pattern(regexp = "^\\d{4}-\\d{2}$", message = "must look like 2026-27") String academicYear,
            @NotBlank @Size(max = 64) String timeZone) {

        College.Profile toProfile() {
            return new College.Profile(name.trim(), affiliation.trim(), address.trim(), city.trim(), state.trim(),
                    blankToNull(postalCode), blankToNull(contactEmail), blankToNull(contactPhone),
                    academicYear, timeZone.trim());
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    private final CollegeService colleges;

    public CollegeController(CollegeService colleges) {
        this.colleges = colleges;
    }

    @GetMapping
    public CollegeDetails current() {
        return colleges.current();
    }

    @PutMapping
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public CollegeDetails update(@Valid @RequestBody UpdateCollegeRequest request) {
        return colleges.updateProfile(request.toProfile());
    }

    @PostMapping("/complete-onboarding")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public CollegeDetails completeOnboarding() {
        return colleges.completeOnboarding();
    }
}
