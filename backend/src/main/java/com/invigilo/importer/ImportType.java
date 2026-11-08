package com.invigilo.importer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Importable record types and their CSV columns. Import departments first, then subjects and
 * halls, then faculty (rows refer to existing codes).
 */
public enum ImportType {

    DEPARTMENTS(List.of(
            new Column("code", true, "CSE"),
            new Column("name", true, "Computer Science and Engineering"),
            new Column("active", false, "yes"))),

    SUBJECTS(List.of(
            new Column("code", true, "CS301"),
            new Column("name", true, "Design and Analysis of Algorithms"),
            new Column("department_code", true, "CSE"),
            new Column("programme", false, "B.Tech"),
            new Column("semester", false, "5"),
            new Column("active", false, "yes"))),

    HALLS(List.of(
            new Column("code", true, "H101"),
            new Column("name", true, "Main Block Hall 101"),
            new Column("building", false, "Main Block"),
            new Column("capacity", true, "60"),
            new Column("min_invigilators", false, "2"),
            new Column("lab", false, "no"),
            new Column("active", false, "yes"))),

    FACULTY(List.of(
            new Column("employee_code", true, "F1001"),
            new Column("full_name", true, "Dr. Asha Menon"),
            new Column("department_code", true, "CSE"),
            new Column("email", false, "asha.menon@college.example"),
            new Column("phone", false, "+91 98470 00000"),
            new Column("designation", false, "Associate Professor"),
            new Column("employment_type", false, "REGULAR"),
            new Column("joined_on", false, "2015-06-01"),
            new Column("chief_eligible", false, "yes"),
            new Column("duty_weight", false, "1.00"),
            new Column("subject_codes", false, "CS301;CS302"),
            new Column("active", false, "yes")));

    public record Column(String name, boolean required, String example) {}

    private final List<Column> columns;

    ImportType(List<Column> columns) {
        this.columns = columns;
    }

    public List<Column> columns() {
        return columns;
    }

    public static Optional<ImportType> parse(String value) {
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
