package com.invigilo.faculty;

import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.Codes;
import com.invigilo.common.persistence.UpsertResult;
import com.invigilo.common.persistence.UsageCheck;
import com.invigilo.common.persistence.Versions;
import com.invigilo.common.web.PageResponse;
import com.invigilo.department.Department;
import com.invigilo.department.DepartmentService;
import com.invigilo.hall.Hall;
import com.invigilo.hall.HallService;
import com.invigilo.subject.Subject;
import com.invigilo.subject.SubjectService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Faculty records, the subjects they teach and their per-person restrictions. */
@Service
public class FacultyService {

    public record Ref(UUID id, String code, String name) {}

    public record FacultyInput(String employeeCode, String fullName, UUID departmentId, String email, String phone,
                               String designation, Faculty.EmploymentType employmentType, LocalDate joinedOn,
                               boolean chiefEligible, BigDecimal dutyWeight, boolean active, List<UUID> subjectIds) {}

    public record FacultySummary(UUID id, String employeeCode, String fullName, String email, Ref department,
                                 String designation, Faculty.EmploymentType employmentType, boolean chiefEligible,
                                 BigDecimal dutyWeight, boolean active, long subjectCount, long version) {}

    public record RestrictionView(UUID id, FacultyRestriction.Type type, Ref hall, Ref department, Integer maxValue,
                                  String reason) {
        static RestrictionView of(FacultyRestriction r) {
            return new RestrictionView(r.getId(), r.getType(),
                    r.getHall() == null ? null : new Ref(r.getHall().getId(), r.getHall().getCode(), r.getHall().getName()),
                    r.getDepartment() == null ? null : ref(r.getDepartment()),
                    r.getMaxValue(), r.getReason());
        }
    }

    public record FacultyDetail(UUID id, String employeeCode, String fullName, String email, String phone,
                                Ref department, String designation, Faculty.EmploymentType employmentType,
                                LocalDate joinedOn, boolean chiefEligible, BigDecimal dutyWeight, boolean active,
                                List<Ref> subjects, List<RestrictionView> restrictions, long version) {}

    public record FacultyQuery(String q, UUID departmentId, Faculty.EmploymentType employmentType,
                               boolean includeInactive, int page, int size) {}

    public record RestrictionInput(FacultyRestriction.Type type, UUID hallId, UUID departmentId, Integer maxValue,
                                   String reason) {}

    private final FacultyRepository repository;
    private final FacultySubjectRepository facultySubjects;
    private final FacultyRestrictionRepository restrictions;
    private final DepartmentService departments;
    private final SubjectService subjectService;
    private final HallService halls;
    /** The access feature (linked logins) and later duty assignments block deletion. */
    private final ObjectProvider<UsageCheck<Faculty>> usageChecks;
    private final AuditService audit;

    public FacultyService(FacultyRepository repository, FacultySubjectRepository facultySubjects,
                          FacultyRestrictionRepository restrictions, DepartmentService departments,
                          SubjectService subjectService, HallService halls,
                          ObjectProvider<UsageCheck<Faculty>> usageChecks, AuditService audit) {
        this.repository = repository;
        this.facultySubjects = facultySubjects;
        this.restrictions = restrictions;
        this.departments = departments;
        this.subjectService = subjectService;
        this.halls = halls;
        this.usageChecks = usageChecks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<FacultySummary> search(FacultyQuery query) {
        Specification<Faculty> spec = (root, cq, cb) -> cb.conjunction();
        if (!query.includeInactive()) {
            spec = spec.and((root, cq, cb) -> cb.isTrue(root.get("active")));
        }
        if (query.departmentId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("department").get("id"), query.departmentId()));
        }
        if (query.employmentType() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("employmentType"), query.employmentType()));
        }
        if (query.q() != null && !query.q().isBlank()) {
            String like = "%" + query.q().trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, cq, cb) -> cb.or(
                    cb.like(cb.lower(root.get("employeeCode")), like, '\\'),
                    cb.like(cb.lower(root.get("fullName")), like, '\\'),
                    cb.like(cb.lower(root.get("email")), like, '\\')));
        }
        var page = repository.findAll(spec, PageRequest.of(query.page(), query.size(), Sort.by("fullName", "employeeCode")));
        Map<UUID, Long> subjectCounts = new HashMap<>();
        if (!page.isEmpty()) {
            for (Object[] row : facultySubjects.countByFaculty(page.map(Faculty::getId).getContent())) {
                subjectCounts.put((UUID) row[0], (Long) row[1]);
            }
        }
        return PageResponse.of(page, f -> new FacultySummary(f.getId(), f.getEmployeeCode(), f.getFullName(), f.getEmail(),
                ref(f.getDepartment()), f.getDesignation(), f.getEmploymentType(), f.isChiefEligible(), f.getDutyWeight(),
                f.isActive(), subjectCounts.getOrDefault(f.getId(), 0L), f.getVersion()));
    }

    @Transactional(readOnly = true)
    public FacultyDetail get(UUID id) {
        return detail(findWithSubjects(id));
    }

    @Transactional
    public FacultyDetail create(FacultyInput input) {
        String code = Codes.normalize(input.employeeCode());
        if (repository.existsByEmployeeCode(code)) {
            throw codeTaken(code);
        }
        String email = normalizeEmail(input.email());
        if (email != null && repository.existsByEmail(email)) {
            throw emailTaken(email);
        }
        Faculty faculty = new Faculty(departments.requireActive(input.departmentId()), input);
        faculty.replaceSubjects(resolveSubjects(input.subjectIds()));
        repository.saveAndFlush(faculty);
        FacultyDetail view = detail(faculty);
        audit.record("FACULTY_CREATED", "faculty", faculty.getId(), null, view, null);
        return view;
    }

    @Transactional
    public FacultyDetail update(UUID id, FacultyInput input, Long version) {
        Faculty faculty = findWithSubjects(id);
        Versions.requireUnchanged(faculty, version);
        return applyUpdate(faculty, input);
    }

    /** Create or update by employee code, for CSV import (no stale-edit check: the file is the source). */
    @Transactional
    public UpsertResult upsert(FacultyInput input) {
        var existing = repository.findByEmployeeCode(Codes.normalize(input.employeeCode()));
        if (existing.isEmpty()) {
            create(input);
            return UpsertResult.CREATED;
        }
        Faculty faculty = findWithSubjects(existing.get().getId());
        FacultyDetail before = detail(faculty);
        FacultyDetail after = applyUpdate(faculty, input);
        return sameContent(before, after) ? UpsertResult.UNCHANGED : UpsertResult.UPDATED;
    }

    private FacultyDetail applyUpdate(Faculty faculty, FacultyInput input) {
        String code = Codes.normalize(input.employeeCode());
        if (repository.existsByEmployeeCodeAndIdNot(code, faculty.getId())) {
            throw codeTaken(code);
        }
        String email = normalizeEmail(input.email());
        if (email != null && repository.existsByEmailAndIdNot(email, faculty.getId())) {
            throw emailTaken(email);
        }
        Department department = faculty.getDepartment().getId().equals(input.departmentId())
                ? faculty.getDepartment()
                : departments.requireActive(input.departmentId());
        FacultyDetail before = detail(faculty);
        faculty.update(department, input);
        faculty.replaceSubjects(resolveSubjects(input.subjectIds()));
        repository.flush();
        FacultyDetail after = detail(faculty);
        if (!sameContent(before, after)) {
            audit.record("FACULTY_UPDATED", "faculty", faculty.getId(), before, after, null);
        }
        return after;
    }

    @Transactional
    public void delete(UUID id) {
        Faculty faculty = findWithSubjects(id);
        for (UsageCheck<Faculty> check : usageChecks.orderedStream().toList()) {
            long count = check.countUsages(id);
            if (count > 0) {
                throw ApiException.conflict("FACULTY_IN_USE",
                        "This faculty member has " + count + " " + check.usedBy() + ". Deactivate them instead of deleting.");
            }
        }
        FacultyDetail before = detail(faculty);
        repository.delete(faculty);
        audit.record("FACULTY_DELETED", "faculty", id, before, null, null);
    }

    // ---- restrictions ----

    @Transactional
    public RestrictionView addRestriction(UUID facultyId, RestrictionInput input) {
        Faculty faculty = find(facultyId);
        Hall hall = null;
        Department department = null;
        Integer max = null;
        switch (input.type()) {
            case EXCLUDE_HALL -> {
                requireOnly(input, true, false, false);
                hall = halls.find(input.hallId());
            }
            case EXCLUDE_DEPARTMENT_EXAMS -> {
                requireOnly(input, false, true, false);
                department = departments.find(input.departmentId());
            }
            case NOT_CHIEF -> requireOnly(input, false, false, false);
            case MAX_DUTIES_PER_DAY, MAX_DUTIES_PER_PERIOD -> {
                requireOnly(input, false, false, true);
                max = input.maxValue();
            }
        }
        for (FacultyRestriction existing : restrictions.findByFacultyIdOrderByCreatedAtAsc(facultyId)) {
            boolean duplicate = existing.getType() == input.type() && switch (input.type()) {
                case EXCLUDE_HALL -> Objects.equals(idOf(existing.getHall()), input.hallId());
                case EXCLUDE_DEPARTMENT_EXAMS -> Objects.equals(idOf(existing.getDepartment()), input.departmentId());
                // At most one of each of these per person.
                case NOT_CHIEF, MAX_DUTIES_PER_DAY, MAX_DUTIES_PER_PERIOD -> true;
            };
            if (duplicate) {
                throw ApiException.conflict("RESTRICTION_EXISTS",
                        "This faculty member already has this restriction. Remove it first to change it.");
            }
        }
        FacultyRestriction saved = restrictions.saveAndFlush(
                new FacultyRestriction(faculty, input.type(), hall, department, max, input.reason()));
        RestrictionView view = RestrictionView.of(saved);
        audit.record("FACULTY_RESTRICTION_ADDED", "faculty", facultyId, null, view, input.reason());
        return view;
    }

    @Transactional
    public void removeRestriction(UUID facultyId, UUID restrictionId) {
        FacultyRestriction restriction = restrictions.findById(restrictionId)
                .filter(r -> r.getFaculty().getId().equals(facultyId))
                .orElseThrow(() -> ApiException.notFound("RESTRICTION_NOT_FOUND", "Restriction not found"));
        RestrictionView before = RestrictionView.of(restriction);
        restrictions.delete(restriction);
        audit.record("FACULTY_RESTRICTION_REMOVED", "faculty", facultyId, before, null, null);
    }

    // ---- lookups for other features ----

    /** Employee code → id and email → employee code of every faculty record (tenant), for CSV import checks. */
    public record ExistingKeys(Map<String, UUID> idByCode, Map<String, String> codeByEmail) {}

    @Transactional(readOnly = true)
    public ExistingKeys existingKeys() {
        Map<String, UUID> byCode = new HashMap<>();
        Map<String, String> byEmail = new HashMap<>();
        for (Faculty f : repository.findAll()) {
            byCode.put(f.getEmployeeCode(), f.getId());
            if (f.getEmail() != null) byEmail.put(f.getEmail(), f.getEmployeeCode());
        }
        return new ExistingKeys(byCode, byEmail);
    }

    /** Tenant-filtered: another college's faculty is not found. */
    @Transactional(readOnly = true)
    public Faculty find(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("FACULTY_NOT_FOUND", "Faculty member not found"));
    }

    private Faculty findWithSubjects(UUID id) {
        return repository.findWithSubjectsById(id)
                .orElseThrow(() -> ApiException.notFound("FACULTY_NOT_FOUND", "Faculty member not found"));
    }

    private List<Subject> resolveSubjects(List<UUID> ids) {
        if (ids == null) return List.of();
        return new LinkedHashSet<>(ids).stream().map(subjectService::find).toList();
    }

    private FacultyDetail detail(Faculty f) {
        List<Ref> subjects = f.getSubjects().stream()
                .map(FacultySubject::getSubject)
                .sorted(Comparator.comparing(Subject::getCode))
                .map(s -> new Ref(s.getId(), s.getCode(), s.getName()))
                .toList();
        List<RestrictionView> rs = f.getId() == null ? List.of()
                : restrictions.findByFacultyIdOrderByCreatedAtAsc(f.getId()).stream().map(RestrictionView::of).toList();
        return new FacultyDetail(f.getId(), f.getEmployeeCode(), f.getFullName(), f.getEmail(), f.getPhone(),
                ref(f.getDepartment()), f.getDesignation(), f.getEmploymentType(), f.getJoinedOn(), f.isChiefEligible(),
                f.getDutyWeight(), f.isActive(), subjects, rs, f.getVersion() == null ? 0 : f.getVersion());
    }

    /** Equal apart from the version counter. */
    private static boolean sameContent(FacultyDetail a, FacultyDetail b) {
        return a.equals(new FacultyDetail(b.id(), b.employeeCode(), b.fullName(), b.email(), b.phone(), b.department(),
                b.designation(), b.employmentType(), b.joinedOn(), b.chiefEligible(), b.dutyWeight(), b.active(),
                b.subjects(), b.restrictions(), a.version()));
    }

    private static void requireOnly(RestrictionInput in, boolean hall, boolean department, boolean max) {
        boolean ok = (in.hallId() != null) == hall && (in.departmentId() != null) == department
                && (in.maxValue() != null) == max;
        if (!ok) {
            throw ApiException.unprocessable("INVALID_RESTRICTION", switch (in.type()) {
                case EXCLUDE_HALL -> "Choose the hall to exclude";
                case EXCLUDE_DEPARTMENT_EXAMS -> "Choose the department whose exams to exclude";
                case NOT_CHIEF -> "This restriction takes no hall, department or number";
                case MAX_DUTIES_PER_DAY, MAX_DUTIES_PER_PERIOD -> "Enter the maximum number of duties";
            });
        }
    }

    private static UUID idOf(Object entity) {
        if (entity instanceof Hall h) return h.getId();
        if (entity instanceof Department d) return d.getId();
        return null;
    }

    static Ref ref(Department d) {
        return new Ref(d.getId(), d.getCode(), d.getName());
    }

    private static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException codeTaken(String code) {
        return ApiException.conflict("FACULTY_CODE_TAKEN", "A faculty member with employee code " + code + " already exists");
    }

    private static ApiException emailTaken(String email) {
        return ApiException.conflict("FACULTY_EMAIL_TAKEN", "Another faculty member already uses " + email);
    }
}
