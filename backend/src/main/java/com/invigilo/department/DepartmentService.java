package com.invigilo.department;

import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.Codes;
import com.invigilo.common.persistence.UsageCheck;
import com.invigilo.common.persistence.Versions;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepartmentService {

    public record DepartmentView(UUID id, String code, String name, boolean active, long version) {
        public static DepartmentView of(Department d) {
            return new DepartmentView(d.getId(), d.getCode(), d.getName(), d.isActive(), d.getVersion());
        }
    }

    public record DepartmentInput(String code, String name, boolean active) {}

    private final DepartmentRepository repository;
    private final ObjectProvider<UsageCheck<Department>> usageChecks;
    private final AuditService audit;

    public DepartmentService(DepartmentRepository repository, ObjectProvider<UsageCheck<Department>> usageChecks,
                             AuditService audit) {
        this.repository = repository;
        this.usageChecks = usageChecks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DepartmentView> list(boolean includeInactive) {
        var rows = includeInactive ? repository.findAllByOrderByCodeAsc() : repository.findByActiveTrueOrderByCodeAsc();
        return rows.stream().map(DepartmentView::of).toList();
    }

    @Transactional(readOnly = true)
    public DepartmentView get(UUID id) {
        return DepartmentView.of(find(id));
    }

    @Transactional
    public DepartmentView create(DepartmentInput input) {
        String code = Codes.normalize(input.code());
        if (repository.existsByCode(code)) {
            throw codeTaken(code);
        }
        Department saved = repository.saveAndFlush(new Department(code, input.name(), input.active()));
        DepartmentView view = DepartmentView.of(saved);
        audit.record("DEPARTMENT_CREATED", "department", saved.getId(), null, view, null);
        return view;
    }

    @Transactional
    public DepartmentView update(UUID id, DepartmentInput input, Long version) {
        Department department = find(id);
        Versions.requireUnchanged(department, version);
        String code = Codes.normalize(input.code());
        if (repository.existsByCodeAndIdNot(code, id)) {
            throw codeTaken(code);
        }
        DepartmentView before = DepartmentView.of(department);
        department.update(code, input.name(), input.active());
        repository.flush();
        DepartmentView after = DepartmentView.of(department);
        audit.record("DEPARTMENT_UPDATED", "department", id, before, after, null);
        return after;
    }

    /** Deletes an unused department. A department that anything refers to must be deactivated instead. */
    @Transactional
    public void delete(UUID id) {
        Department department = find(id);
        for (UsageCheck<Department> check : usageChecks.orderedStream().toList()) {
            long count = check.countUsages(id);
            if (count > 0) {
                throw ApiException.conflict("DEPARTMENT_IN_USE",
                        "This department has " + count + " " + check.usedBy() + ". Deactivate it instead of deleting.");
            }
        }
        DepartmentView before = DepartmentView.of(department);
        repository.delete(department);
        audit.record("DEPARTMENT_DELETED", "department", id, before, null, null);
    }

    /** For other features: an active department of the current college, or 404/422. */
    @Transactional(readOnly = true)
    public Department requireActive(UUID id) {
        Department department = find(id);
        if (!department.isActive()) {
            throw ApiException.unprocessable("DEPARTMENT_INACTIVE", "Department " + department.getCode() + " is inactive");
        }
        return department;
    }

    /** Tenant-filtered lookup: another college's department is simply not found. */
    @Transactional(readOnly = true)
    public Department find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("DEPARTMENT_NOT_FOUND", "Department not found"));
    }

    private static ApiException codeTaken(String code) {
        return ApiException.conflict("DEPARTMENT_CODE_TAKEN", "A department with code " + code + " already exists");
    }
}
