package com.invigilo.report;

import com.invigilo.common.error.ApiException;
import java.util.Locale;

/** The file formats a report can be downloaded in. */
public enum ReportFormat {
    PDF("application/pdf", "pdf"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    CSV("text/csv", "csv");

    private final String contentType;
    private final String extension;

    ReportFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    public static ReportFormat parse(String value) {
        if (value == null || value.isBlank()) return PDF;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("UNKNOWN_FORMAT", "Choose pdf, xlsx or csv");
        }
    }

    public byte[] render(Report report, String footer) {
        return switch (this) {
            case PDF -> ReportWriters.pdf(report, footer);
            case XLSX -> ReportWriters.xlsx(report);
            case CSV -> ReportWriters.csv(report);
        };
    }
}
