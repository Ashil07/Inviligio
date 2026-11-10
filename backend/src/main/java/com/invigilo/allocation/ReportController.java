package com.invigilo.allocation;

import com.invigilo.allocation.ReportService.Filters;
import com.invigilo.allocation.ReportService.GroupBy;
import com.invigilo.allocation.ReportService.ReportFile;
import com.invigilo.common.error.ApiException;
import com.invigilo.report.ReportFormat;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Report downloads of an exam period (M16), as {@code ?format=pdf|xlsx|csv}. Exam cell only. */
@RestController
@RequestMapping("/api/v1/exam-periods/{periodId}/reports")
@PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/duty-chart")
    public ResponseEntity<byte[]> dutyChart(@PathVariable UUID periodId, @RequestParam(required = false) String format,
                                            @RequestParam(required = false) String groupBy,
                                            @RequestParam(required = false) Integer version,
                                            @RequestParam(required = false) String department,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) String session,
                                            @RequestParam(required = false) String hall) {
        Filters filters = new Filters(blank(department), from, to, blank(session), blank(hall));
        return download(reports.dutyChart(periodId, version, group(groupBy), filters, ReportFormat.parse(format)));
    }

    @GetMapping("/workload")
    public ResponseEntity<byte[]> workload(@PathVariable UUID periodId, @RequestParam(required = false) String format,
                                           @RequestParam(required = false) String department) {
        return download(reports.workload(periodId, blank(department), ReportFormat.parse(format)));
    }

    @GetMapping("/remuneration")
    public ResponseEntity<byte[]> remuneration(@PathVariable UUID periodId, @RequestParam(required = false) String format,
                                               @RequestParam(required = false) String department) {
        return download(reports.remuneration(periodId, blank(department), ReportFormat.parse(format)));
    }

    @GetMapping("/fairness")
    public ResponseEntity<byte[]> fairness(@PathVariable UUID periodId, @RequestParam(required = false) String format) {
        return download(reports.fairness(periodId, ReportFormat.parse(format)));
    }

    private static ResponseEntity<byte[]> download(ReportFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.format().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }

    private static GroupBy group(String value) {
        if (value == null || value.isBlank()) return GroupBy.DATE;
        try {
            return GroupBy.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("UNKNOWN_GROUPING", "Group by date, session, hall, faculty or department");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
