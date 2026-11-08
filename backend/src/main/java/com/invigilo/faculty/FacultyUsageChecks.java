package com.invigilo.faculty;

import com.invigilo.common.persistence.UsageCheck;
import com.invigilo.department.Department;
import com.invigilo.hall.Hall;
import com.invigilo.subject.Subject;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Records the faculty feature points at, which therefore cannot be deleted while referenced. */
final class FacultyUsageChecks {

    private FacultyUsageChecks() {}

    @Component
    static class DepartmentMembers implements UsageCheck<Department> {
        private final FacultyRepository faculty;

        DepartmentMembers(FacultyRepository faculty) {
            this.faculty = faculty;
        }

        @Override
        public String usedBy() {
            return "faculty member(s)";
        }

        @Override
        public long countUsages(UUID departmentId) {
            return faculty.countByDepartmentId(departmentId);
        }
    }

    @Component
    static class DepartmentRestrictions implements UsageCheck<Department> {
        private final FacultyRestrictionRepository restrictions;

        DepartmentRestrictions(FacultyRestrictionRepository restrictions) {
            this.restrictions = restrictions;
        }

        @Override
        public String usedBy() {
            return "faculty restriction(s)";
        }

        @Override
        public long countUsages(UUID departmentId) {
            return restrictions.countByDepartmentId(departmentId);
        }
    }

    @Component
    static class SubjectsTaught implements UsageCheck<Subject> {
        private final FacultySubjectRepository facultySubjects;

        SubjectsTaught(FacultySubjectRepository facultySubjects) {
            this.facultySubjects = facultySubjects;
        }

        @Override
        public String usedBy() {
            return "faculty member(s) teaching it";
        }

        @Override
        public long countUsages(UUID subjectId) {
            return facultySubjects.countBySubjectId(subjectId);
        }
    }

    @Component
    static class HallRestrictions implements UsageCheck<Hall> {
        private final FacultyRestrictionRepository restrictions;

        HallRestrictions(FacultyRestrictionRepository restrictions) {
            this.restrictions = restrictions;
        }

        @Override
        public String usedBy() {
            return "faculty restriction(s)";
        }

        @Override
        public long countUsages(UUID hallId) {
            return restrictions.countByHallId(hallId);
        }
    }
}
