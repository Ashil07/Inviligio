package com.invigilo.faculty;

import com.invigilo.common.persistence.Codes;
import com.invigilo.common.web.PageResponse;
import com.invigilo.faculty.FacultyService.FacultyDetail;
import com.invigilo.faculty.FacultyService.FacultyInput;
import com.invigilo.faculty.FacultyService.FacultyQuery;
import com.invigilo.faculty.FacultyService.FacultySummary;
import com.invigilo.faculty.FacultyService.RestrictionInput;
import com.invigilo.faculty.FacultyService.RestrictionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Faculty records are exam-cell data: only EXAM_CELL_ADMIN may read or change them here. HOD and
 * faculty views of this data arrive with their dashboards (scoped to department / self).
 */
@RestController
@RequestMapping("/api/v1/faculty")
@PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
public class FacultyController {

    public record FacultyRequest(
            @NotBlank @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String employeeCode,
            @NotBlank @Size(max = 200) String fullName,
            @NotNull UUID departmentId,
            @Email @Size(max = 254) String email,
            @Size(max = 30) String phone,
            @Size(max = 100) String designation,
            @NotNull Faculty.EmploymentType employmentType,
            @PastOrPresent LocalDate joinedOn,
            @NotNull Boolean chiefEligible,
            @NotNull @DecimalMin("0.10") @DecimalMax("2.00") @Digits(integer = 1, fraction = 2) BigDecimal dutyWeight,
            @NotNull Boolean active,
            @Size(max = 100) List<@NotNull UUID> subjectIds,
            Long version) {

        public FacultyInput input() {
            return new FacultyInput(employeeCode, fullName, departmentId, email, phone, designation, employmentType,
                    joinedOn, chiefEligible, dutyWeight, active, subjectIds == null ? List.of() : subjectIds);
        }
    }

    public record RestrictionRequest(
            @NotNull FacultyRestriction.Type type,
            UUID hallId,
            UUID departmentId,
            @Min(0) @Max(200) Integer maxValue,
            @Size(max = 300) String reason) {}

    private final FacultyService faculty;

    public FacultyController(FacultyService faculty) {
        this.faculty = faculty;
    }

    @GetMapping
    public PageResponse<FacultySummary> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) Faculty.EmploymentType employmentType,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return faculty.search(new FacultyQuery(q, departmentId, employmentType, includeInactive, page, size));
    }

    @GetMapping("/{id}")
    public FacultyDetail get(@PathVariable UUID id) {
        return faculty.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FacultyDetail create(@Valid @RequestBody FacultyRequest request) {
        return faculty.create(request.input());
    }

    @PutMapping("/{id}")
    public FacultyDetail update(@PathVariable UUID id, @Valid @RequestBody FacultyRequest request) {
        return faculty.update(id, request.input(), request.version());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        faculty.delete(id);
    }

    @PostMapping("/{id}/restrictions")
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionView addRestriction(@PathVariable UUID id, @Valid @RequestBody RestrictionRequest request) {
        return faculty.addRestriction(id, new RestrictionInput(request.type(), request.hallId(), request.departmentId(),
                request.maxValue(), request.reason()));
    }

    @DeleteMapping("/{id}/restrictions/{restrictionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRestriction(@PathVariable UUID id, @PathVariable UUID restrictionId) {
        faculty.removeRestriction(id, restrictionId);
    }
}
