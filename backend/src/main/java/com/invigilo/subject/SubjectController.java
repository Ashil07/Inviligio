package com.invigilo.subject;

import com.invigilo.common.persistence.Codes;
import com.invigilo.common.web.PageResponse;
import com.invigilo.subject.SubjectService.SubjectInput;
import com.invigilo.subject.SubjectService.SubjectQuery;
import com.invigilo.subject.SubjectService.SubjectView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectController {

    public record SubjectRequest(
            @NotBlank @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull UUID departmentId,
            @Size(max = 100) String programme,
            @Min(1) @Max(12) Integer semester,
            @NotNull Boolean active,
            Long version) {

        SubjectInput input() {
            return new SubjectInput(code, name, departmentId, programme, semester, active);
        }
    }

    private final SubjectService subjects;

    public SubjectController(SubjectService subjects) {
        this.subjects = subjects;
    }

    @GetMapping
    public PageResponse<SubjectView> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return subjects.search(new SubjectQuery(q, departmentId, includeInactive, page, size));
    }

    @GetMapping("/{id}")
    public SubjectView get(@PathVariable UUID id) {
        return subjects.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public SubjectView create(@Valid @RequestBody SubjectRequest request) {
        return subjects.create(request.input());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public SubjectView update(@PathVariable UUID id, @Valid @RequestBody SubjectRequest request) {
        return subjects.update(id, request.input(), request.version());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public void delete(@PathVariable UUID id) {
        subjects.delete(id);
    }
}
