package com.invigilo.importer;

import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.UpsertResult;
import com.invigilo.department.DepartmentService;
import com.invigilo.department.DepartmentService.DepartmentInput;
import com.invigilo.faculty.FacultyService;
import com.invigilo.faculty.FacultyService.FacultyInput;
import com.invigilo.hall.HallService;
import com.invigilo.hall.HallService.HallInput;
import com.invigilo.subject.SubjectService;
import com.invigilo.subject.SubjectService.SubjectInput;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies validated rows in ONE transaction through the normal services (same rules, same audit
 * entries). Any failure rolls everything back; the failing line is reported.
 */
@Component
class ImportApplier {

    static class RowFailed extends RuntimeException {
        private final int line;

        RowFailed(int line, String message) {
            super(message);
            this.line = line;
        }

        int line() {
            return line;
        }
    }

    private final DepartmentService departments;
    private final SubjectService subjects;
    private final HallService halls;
    private final FacultyService faculty;
    private final AuditService audit;

    ImportApplier(DepartmentService departments, SubjectService subjects, HallService halls, FacultyService faculty,
                  AuditService audit) {
        this.departments = departments;
        this.subjects = subjects;
        this.halls = halls;
        this.faculty = faculty;
        this.audit = audit;
    }

    @Transactional
    Map<String, Integer> apply(ImportType type, List<ImportService.Row> rows) {
        Map<UpsertResult, Integer> counts = new EnumMap<>(UpsertResult.class);
        for (ImportService.Row row : rows) {
            UpsertResult result;
            try {
                result = switch (type) {
                    case DEPARTMENTS -> departments.upsert((DepartmentInput) row.input());
                    case SUBJECTS -> subjects.upsert((SubjectInput) row.input());
                    case HALLS -> halls.upsert((HallInput) row.input());
                    case FACULTY -> faculty.upsert((FacultyInput) row.input());
                };
            } catch (ApiException e) {
                throw new RowFailed(row.line(), e.getMessage());
            }
            counts.merge(result, 1, Integer::sum);
        }
        Map<String, Integer> named = new HashMap<>();
        counts.forEach((k, v) -> named.put(k.name(), v));
        audit.record("IMPORT_COMPLETED", "import", null, null, Map.of("type", type, "counts", named), null);
        return named;
    }
}
