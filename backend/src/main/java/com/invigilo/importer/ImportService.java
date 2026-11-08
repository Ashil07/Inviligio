package com.invigilo.importer;

import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.Codes;
import com.invigilo.department.Department;
import com.invigilo.department.DepartmentController.DepartmentRequest;
import com.invigilo.department.DepartmentService;
import com.invigilo.department.DepartmentService.DepartmentInput;
import com.invigilo.faculty.Faculty;
import com.invigilo.faculty.FacultyController.FacultyRequest;
import com.invigilo.faculty.FacultyService;
import com.invigilo.hall.HallController.HallRequest;
import com.invigilo.hall.HallService.HallInput;
import com.invigilo.subject.Subject;
import com.invigilo.subject.SubjectController.SubjectRequest;
import com.invigilo.subject.SubjectService;
import com.invigilo.subject.SubjectService.SubjectInput;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

/**
 * CSV import of departments, subjects, halls and faculty.
 *
 * <p>All-or-nothing: the whole file is validated first (same Bean Validation rules as the API,
 * plus code references and duplicates), errors are reported per line and column, and only a file
 * without errors is applied — in one transaction. A dry run validates and predicts without saving.
 * Rows are matched by code: existing codes are updated, new codes created; nothing is deleted.
 */
@Service
public class ImportService {

    public static final int MAX_ROWS = 5000;

    public record RowError(int line, String column, String message) {}

    public record ImportReport(ImportType type, boolean dryRun, boolean applied, int rows, int toCreate, int toUpdate,
                               int created, int updated, int unchanged, List<RowError> errors) {}

    /** A validated row ready to apply. */
    record Row(int line, String code, Object input) {}

    private final Validator validator;
    private final DepartmentService departments;
    private final SubjectService subjects;
    private final com.invigilo.hall.HallService halls;
    private final FacultyService faculty;
    private final ImportApplier applier;

    public ImportService(Validator validator, DepartmentService departments, SubjectService subjects,
                         com.invigilo.hall.HallService halls, FacultyService faculty, ImportApplier applier) {
        this.validator = validator;
        this.departments = departments;
        this.subjects = subjects;
        this.halls = halls;
        this.faculty = faculty;
        this.applier = applier;
    }

    public ImportReport run(ImportType type, byte[] content, boolean dryRun) {
        List<RowError> errors = new ArrayList<>();
        List<CSVRecord> records = parse(type, content, errors);
        if (!errors.isEmpty()) {
            return report(type, dryRun, false, records.size(), 0, 0, null, errors);
        }
        Set<String> existingCodes = new HashSet<>();
        List<Row> rows = switch (type) {
            case DEPARTMENTS -> departmentRows(records, errors, existingCodes);
            case SUBJECTS -> subjectRows(records, errors, existingCodes);
            case HALLS -> hallRows(records, errors, existingCodes);
            case FACULTY -> facultyRows(records, errors, existingCodes);
        };
        int toUpdate = (int) rows.stream().filter(r -> existingCodes.contains(r.code())).count();
        int toCreate = rows.size() - toUpdate;
        if (!errors.isEmpty() || dryRun) {
            return report(type, dryRun, false, records.size(), toCreate, toUpdate, null, errors);
        }
        try {
            Map<String, Integer> counts = applier.apply(type, rows);
            return report(type, false, true, records.size(), toCreate, toUpdate, counts, List.of());
        } catch (ImportApplier.RowFailed e) {
            return report(type, false, false, records.size(), toCreate, toUpdate, null,
                    List.of(new RowError(e.line(), null, e.getMessage())));
        }
    }

    public byte[] template(ImportType type) {
        StringBuilder out = new StringBuilder();
        try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            printer.printRecord(type.columns().stream().map(ImportType.Column::name).toList());
            printer.printRecord(type.columns().stream().map(ImportType.Column::example).toList());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---- parsing ----

    private List<CSVRecord> parse(ImportType type, byte[] content, List<RowError> errors) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            errors.add(new RowError(0, null, "The file is not UTF-8 text. In Excel, use 'Save as: CSV UTF-8'."));
            return List.of();
        }
        if (text.startsWith("﻿")) text = text.substring(1); // Excel's byte-order mark
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true).setTrim(true).setIgnoreEmptyLines(true)
                .get();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            Set<String> header = new HashSet<>();
            parser.getHeaderNames().forEach(h -> header.add(h.trim().toLowerCase(Locale.ROOT)));
            Set<String> known = new HashSet<>();
            for (ImportType.Column c : type.columns()) {
                known.add(c.name());
                if (c.required() && !header.contains(c.name())) {
                    errors.add(new RowError(1, c.name(), "Missing required column"));
                }
            }
            for (String h : header) {
                if (!known.contains(h)) errors.add(new RowError(1, h, "Unknown column"));
            }
            if (!errors.isEmpty()) return List.of();
            List<CSVRecord> records = new ArrayList<>();
            for (CSVRecord r : parser) {
                if (records.size() == MAX_ROWS) {
                    errors.add(new RowError(0, null, "Too many rows: at most " + MAX_ROWS + " per file"));
                    return records;
                }
                records.add(r);
            }
            if (records.isEmpty()) errors.add(new RowError(0, null, "The file has no data rows"));
            return records;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            errors.add(new RowError(0, null, "Could not read the CSV file: " + e.getMessage()));
            return List.of();
        }
    }

    /** Typed access to one CSV record that collects conversion errors. */
    private static final class Cells {
        final CSVRecord record;
        final int line;
        final List<RowError> errors;

        Cells(CSVRecord record, List<RowError> errors) {
            this.record = record;
            this.line = (int) record.getRecordNumber() + 1; // + header line
            this.errors = errors;
        }

        String text(String column) {
            return record.isMapped(column) && record.isSet(column) ? record.get(column).trim() : "";
        }

        String textOrNull(String column) {
            String v = text(column);
            return v.isEmpty() ? null : v;
        }

        boolean bool(String column, boolean dflt) {
            String v = text(column).toLowerCase(Locale.ROOT);
            return switch (v) {
                case "" -> dflt;
                case "yes", "y", "true", "1" -> true;
                case "no", "n", "false", "0" -> false;
                default -> {
                    error(column, "Use yes or no");
                    yield dflt;
                }
            };
        }

        Integer integer(String column, Integer dflt) {
            String v = text(column);
            if (v.isEmpty()) return dflt;
            try {
                return Integer.valueOf(v);
            } catch (NumberFormatException e) {
                error(column, "Must be a whole number");
                return dflt;
            }
        }

        BigDecimal decimal(String column, BigDecimal dflt) {
            String v = text(column);
            if (v.isEmpty()) return dflt;
            try {
                return new BigDecimal(v);
            } catch (NumberFormatException e) {
                error(column, "Must be a number such as 1.00");
                return dflt;
            }
        }

        private static final List<DateTimeFormatter> DATES = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd/MM/yyyy"), DateTimeFormatter.ofPattern("dd-MM-yyyy"));

        LocalDate date(String column) {
            String v = text(column);
            if (v.isEmpty()) return null;
            for (DateTimeFormatter f : DATES) {
                try {
                    return LocalDate.parse(v, f);
                } catch (DateTimeParseException ignored) {
                    // try next
                }
            }
            error(column, "Use a date such as 2015-06-01 or 01/06/2015");
            return null;
        }

        void error(String column, String message) {
            errors.add(new RowError(line, column, message));
        }
    }

    /** Runs Bean Validation of the API request record and reports violations under CSV column names. */
    private <T> boolean validate(T request, Cells cells, Map<String, String> columnByProperty) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        for (ConstraintViolation<T> v : violations) {
            String property = v.getPropertyPath().toString();
            String root = property.contains("[") ? property.substring(0, property.indexOf('[')) : property;
            cells.error(columnByProperty.getOrDefault(root, root), v.getMessage());
        }
        return violations.isEmpty();
    }

    private static boolean firstOccurrence(Set<String> seen, String key, Cells cells, String column) {
        if (key == null || key.isEmpty()) return true;
        if (!seen.add(key)) {
            cells.error(column, "Duplicate value in this file: " + key);
            return false;
        }
        return true;
    }

    // ---- per type ----

    private List<Row> departmentRows(List<CSVRecord> records, List<RowError> errors, Set<String> existing) {
        existing.addAll(departments.byCode().keySet());
        Set<String> seen = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        for (CSVRecord r : records) {
            Cells c = new Cells(r, errors);
            int before = errors.size();
            String code = c.text("code");
            var request = new DepartmentRequest(code, c.text("name"), c.bool("active", true), null);
            validate(request, c, Map.of());
            firstOccurrence(seen, Codes.normalize(code), c, "code");
            if (errors.size() == before) {
                rows.add(new Row(c.line, Codes.normalize(code), new DepartmentInput(code, request.name(), request.active())));
            }
        }
        return rows;
    }

    private List<Row> subjectRows(List<CSVRecord> records, List<RowError> errors, Set<String> existing) {
        Map<String, Department> deptByCode = departments.byCode();
        Map<String, Subject> subjectByCode = subjects.byCode();
        existing.addAll(subjectByCode.keySet());
        Set<String> seen = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        for (CSVRecord r : records) {
            Cells c = new Cells(r, errors);
            int before = errors.size();
            String code = c.text("code");
            UUID departmentId = department(c, deptByCode,
                    subjectByCode.containsKey(Codes.normalize(code)) ? subjectByCode.get(Codes.normalize(code)).getDepartment().getId() : null);
            var request = new SubjectRequest(code, c.text("name"), departmentId, c.textOrNull("programme"),
                    c.integer("semester", null), c.bool("active", true), null);
            validate(request, c, Map.of("departmentId", "department_code"));
            firstOccurrence(seen, Codes.normalize(code), c, "code");
            if (errors.size() == before) {
                rows.add(new Row(c.line, Codes.normalize(code), new SubjectInput(code, request.name(), departmentId,
                        request.programme(), request.semester(), request.active())));
            }
        }
        return rows;
    }

    private List<Row> hallRows(List<CSVRecord> records, List<RowError> errors, Set<String> existing) {
        existing.addAll(halls.list(true).stream().map(com.invigilo.hall.HallService.HallView::code).toList());
        Set<String> seen = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        for (CSVRecord r : records) {
            Cells c = new Cells(r, errors);
            int before = errors.size();
            String code = c.text("code");
            var request = new HallRequest(code, c.text("name"), c.textOrNull("building"), c.integer("capacity", null),
                    c.integer("min_invigilators", 1), c.bool("lab", false), c.bool("active", true), null);
            validate(request, c, Map.of("minInvigilators", "min_invigilators"));
            firstOccurrence(seen, Codes.normalize(code), c, "code");
            if (errors.size() == before) {
                rows.add(new Row(c.line, Codes.normalize(code), new HallInput(code, request.name(), request.building(),
                        request.capacity(), request.minInvigilators(), request.lab(), request.active())));
            }
        }
        return rows;
    }

    private List<Row> facultyRows(List<CSVRecord> records, List<RowError> errors, Set<String> existing) {
        Map<String, Department> deptByCode = departments.byCode();
        Map<String, Subject> subjectByCode = subjects.byCode();
        FacultyService.ExistingKeys keys = faculty.existingKeys();
        existing.addAll(keys.idByCode().keySet());
        Set<String> seenCodes = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        Map<String, String> columns = Map.of("employeeCode", "employee_code", "fullName", "full_name",
                "departmentId", "department_code", "employmentType", "employment_type", "joinedOn", "joined_on",
                "chiefEligible", "chief_eligible", "dutyWeight", "duty_weight", "subjectIds", "subject_codes");
        for (CSVRecord r : records) {
            Cells c = new Cells(r, errors);
            int before = errors.size();
            String code = Codes.normalize(c.text("employee_code"));
            UUID departmentId = department(c, deptByCode, null);
            Faculty.EmploymentType type = employmentType(c);
            List<UUID> subjectIds = new ArrayList<>();
            for (String sc : new LinkedHashSet<>(List.of(c.text("subject_codes").split("[;|]")))) {
                String s = Codes.normalize(sc);
                if (s.isEmpty()) continue;
                Subject subject = subjectByCode.get(s);
                if (subject == null) c.error("subject_codes", "Unknown subject code " + s);
                else subjectIds.add(subject.getId());
            }
            String email = c.textOrNull("email") == null ? null : c.text("email").toLowerCase(Locale.ROOT);
            var request = new FacultyRequest(c.text("employee_code"), c.text("full_name"), departmentId, email,
                    c.textOrNull("phone"), c.textOrNull("designation"), type, c.date("joined_on"),
                    c.bool("chief_eligible", false), c.decimal("duty_weight", BigDecimal.ONE), c.bool("active", true),
                    subjectIds, null);
            validate(request, c, columns);
            firstOccurrence(seenCodes, code, c, "employee_code");
            if (firstOccurrence(seenEmails, email, c, "email") && email != null) {
                String owner = keys.codeByEmail().get(email);
                if (owner != null && !owner.equals(code)) c.error("email", "Already used by faculty " + owner);
            }
            if (errors.size() == before) {
                rows.add(new Row(c.line, code, request.input()));
            }
        }
        return rows;
    }

    /**
     * Resolves {@code department_code}. A department that is inactive is accepted only when the
     * existing record already belongs to it ({@code currentDepartmentId}).
     */
    private static UUID department(Cells c, Map<String, Department> byCode, UUID currentDepartmentId) {
        String code = Codes.normalize(c.text("department_code"));
        if (code.isEmpty()) return null; // reported by validation as required
        Department d = byCode.get(code);
        if (d == null) {
            c.error("department_code", "Unknown department code " + code);
            return null;
        }
        if (!d.isActive() && !d.getId().equals(currentDepartmentId)) {
            c.error("department_code", "Department " + code + " is inactive");
        }
        return d.getId();
    }

    private static Faculty.EmploymentType employmentType(Cells c) {
        String v = c.text("employment_type");
        if (v.isEmpty()) return Faculty.EmploymentType.REGULAR;
        try {
            return Faculty.EmploymentType.valueOf(v.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            c.error("employment_type", "Use REGULAR, VISITING, GUEST or NON_TEACHING");
            return Faculty.EmploymentType.REGULAR;
        }
    }

    private static ImportReport report(ImportType type, boolean dryRun, boolean applied, int rows, int toCreate,
                                       int toUpdate, Map<String, Integer> counts, List<RowError> errors) {
        Map<String, Integer> c = counts == null ? new HashMap<>() : counts;
        List<RowError> sorted = errors.stream()
                .sorted(java.util.Comparator.comparingInt(RowError::line))
                .limit(200)
                .toList();
        return new ImportReport(type, dryRun, applied, rows, toCreate, toUpdate,
                c.getOrDefault("CREATED", 0), c.getOrDefault("UPDATED", 0), c.getOrDefault("UNCHANGED", 0), sorted);
    }

    static ApiException unknownType(String value) {
        return ApiException.notFound("IMPORT_TYPE_NOT_FOUND", "Unknown import type " + value);
    }
}
