package com.invigilo.subject;

import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.Codes;
import com.invigilo.common.persistence.UsageCheck;
import com.invigilo.common.persistence.Versions;
import com.invigilo.common.web.PageResponse;
import com.invigilo.department.Department;
import com.invigilo.department.DepartmentService;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubjectService {

    public record DepartmentRef(UUID id, String code, String name) {}

    public record SubjectView(UUID id, String code, String name, String programme, Integer semester,
                              boolean active, DepartmentRef department, long version) {
        static SubjectView of(Subject s) {
            Department d = s.getDepartment();
            return new SubjectView(s.getId(), s.getCode(), s.getName(), s.getProgramme(), s.getSemester(),
                    s.isActive(), new DepartmentRef(d.getId(), d.getCode(), d.getName()), s.getVersion());
        }
    }

    public record SubjectInput(String code, String name, UUID departmentId, String programme, Integer semester,
                               boolean active) {}

    public record SubjectQuery(String q, UUID departmentId, boolean includeInactive, int page, int size) {}

    private final SubjectRepository repository;
    private final DepartmentService departments;
    private final AuditService audit;

    public SubjectService(SubjectRepository repository, DepartmentService departments, AuditService audit) {
        this.repository = repository;
        this.departments = departments;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<SubjectView> search(SubjectQuery query) {
        Specification<Subject> spec = (root, cq, cb) -> cb.conjunction();
        if (!query.includeInactive()) {
            spec = spec.and((root, cq, cb) -> cb.isTrue(root.get("active")));
        }
        if (query.departmentId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("department").get("id"), query.departmentId()));
        }
        if (query.q() != null && !query.q().isBlank()) {
            String like = "%" + query.q().trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, cq, cb) -> cb.or(
                    cb.like(cb.lower(root.get("code")), like, '\\'),
                    cb.like(cb.lower(root.get("name")), like, '\\')));
        }
        var page = repository.findAll(spec, PageRequest.of(query.page(), query.size(), Sort.by("code")));
        return PageResponse.of(page, SubjectView::of);
    }

    @Transactional(readOnly = true)
    public SubjectView get(UUID id) {
        return SubjectView.of(find(id));
    }

    @Transactional
    public SubjectView create(SubjectInput input) {
        String code = Codes.normalize(input.code());
        if (repository.existsByCode(code)) {
            throw codeTaken(code);
        }
        Department department = departments.requireActive(input.departmentId());
        Subject saved = repository.saveAndFlush(new Subject(department, input));
        SubjectView view = SubjectView.of(saved);
        audit.record("SUBJECT_CREATED", "subject", saved.getId(), null, view, null);
        return view;
    }

    @Transactional
    public SubjectView update(UUID id, SubjectInput input, Long version) {
        Subject subject = find(id);
        Versions.requireUnchanged(subject, version);
        String code = Codes.normalize(input.code());
        if (repository.existsByCodeAndIdNot(code, id)) {
            throw codeTaken(code);
        }
        // Keeping an existing (possibly now inactive) department is fine; moving to one must be active.
        Department department = subject.getDepartment().getId().equals(input.departmentId())
                ? subject.getDepartment()
                : departments.requireActive(input.departmentId());
        SubjectView before = SubjectView.of(subject);
        subject.update(department, input);
        repository.flush();
        SubjectView after = SubjectView.of(subject);
        audit.record("SUBJECT_UPDATED", "subject", id, before, after, null);
        return after;
    }

    @Transactional
    public void delete(UUID id) {
        Subject subject = find(id);
        SubjectView before = SubjectView.of(subject);
        repository.delete(subject);
        audit.record("SUBJECT_DELETED", "subject", id, before, null, null);
    }

    @Transactional(readOnly = true)
    public Subject find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("SUBJECT_NOT_FOUND", "Subject not found"));
    }

    private static ApiException codeTaken(String code) {
        return ApiException.conflict("SUBJECT_CODE_TAKEN", "A subject with code " + code + " already exists");
    }

    /** Departments with subjects cannot be deleted. */
    @Component
    static class DepartmentUsage implements UsageCheck<Department> {
        private final SubjectRepository subjects;

        DepartmentUsage(SubjectRepository subjects) {
            this.subjects = subjects;
        }

        @Override
        public String usedBy() {
            return "subject(s)";
        }

        @Override
        public long countUsages(UUID departmentId) {
            return subjects.countByDepartmentId(departmentId);
        }
    }
}
