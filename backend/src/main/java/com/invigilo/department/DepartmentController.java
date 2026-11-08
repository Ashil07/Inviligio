package com.invigilo.department;

import com.invigilo.common.persistence.Codes;
import com.invigilo.department.DepartmentService.DepartmentInput;
import com.invigilo.department.DepartmentService.DepartmentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {

    /** {@code version} is required when updating (the version the form was loaded with). */
    public record DepartmentRequest(
            @NotBlank @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull Boolean active,
            Long version) {

        DepartmentInput input() {
            return new DepartmentInput(code, name, active);
        }
    }

    private final DepartmentService departments;

    public DepartmentController(DepartmentService departments) {
        this.departments = departments;
    }

    @GetMapping
    public List<DepartmentView> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return departments.list(includeInactive);
    }

    @GetMapping("/{id}")
    public DepartmentView get(@PathVariable UUID id) {
        return departments.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public DepartmentView create(@Valid @RequestBody DepartmentRequest request) {
        return departments.create(request.input());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public DepartmentView update(@PathVariable UUID id, @Valid @RequestBody DepartmentRequest request) {
        return departments.update(id, request.input(), request.version());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public void delete(@PathVariable UUID id) {
        departments.delete(id);
    }
}
