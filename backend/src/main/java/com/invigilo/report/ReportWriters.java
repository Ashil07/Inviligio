package com.invigilo.report;

import com.invigilo.report.Report.Column;
import com.invigilo.report.Report.Section;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** Renders a {@link Report} as CSV, Excel (streaming) or PDF. Pure: no Spring, no database. */
public final class ReportWriters {

    private ReportWriters() {}

    // ---- CSV ----

    /**
     * UTF-8 with a byte-order mark so Excel opens names correctly. A report whose sections share
     * their columns becomes one flat table (the section heading as the first column); otherwise each
     * section is a block after its heading.
     */
    static byte[] csv(Report report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        try (CSVPrinter csv = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            boolean flat = report.uniform() && !report.sections().isEmpty();
            boolean groupColumn = flat && report.groupLabel() != null;
            if (flat) {
                List<String> header = new ArrayList<>();
                if (groupColumn) header.add(report.groupLabel());
                report.sections().getFirst().columns().forEach(c -> header.add(c.name()));
                csv.printRecord(header);
            }
            for (Section s : report.sections()) {
                if (!flat) {
                    csv.printRecord(safe(s.heading() == null ? report.title() : s.heading()));
                    csv.printRecord(s.columns().stream().map(Column::name).toList());
                }
                for (List<String> row : s.rows()) {
                    List<String> values = new ArrayList<>();
                    if (groupColumn) values.add(s.heading() == null ? "" : s.heading());
                    values.addAll(row);
                    csv.printRecord(values.stream().map(ReportWriters::safe).toList());
                }
                if (!flat) csv.println();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * Neutralises spreadsheet formulas (CSV injection): names are typed by users, and a value such as
     * {@code =HYPERLINK(...)} would run when the file is opened in Excel. Such values get a leading
     * apostrophe; plain negative numbers are left alone.
     */
    static String safe(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        char first = value.charAt(0);
        boolean risky = first == '=' || first == '+' || first == '@' || first == '\t' || first == '\r'
                || (first == '-' && !value.matches("-\\d+(\\.\\d+)?"));
        return risky ? "'" + value : value;
    }

    // ---- Excel ----

    /** One sheet: title and details, then each section with a bold heading and header row. */
    static byte[] xlsx(Report report) {
        try (SXSSFWorkbook book = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            SXSSFSheet sheet = book.createSheet("Report");
            org.apache.poi.ss.usermodel.Font bold = book.createFont();
            bold.setBold(true);
            org.apache.poi.ss.usermodel.Font titleFont = book.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            CellStyle title = book.createCellStyle();
            title.setFont(titleFont);
            CellStyle heading = book.createCellStyle();
            heading.setFont(bold);
            CellStyle header = book.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);

            int r = 0;
            cell(sheet.createRow(r++), 0, report.title(), title);
            for (String line : report.details()) cell(sheet.createRow(r++), 0, line, null);
            // Column widths from the text (POI's autoSizeColumn is slow on large sheets).
            int[] chars = new int[report.sections().stream().mapToInt(s -> s.columns().size()).max().orElse(1)];
            for (Section s : report.sections()) {
                r++;
                if (s.heading() != null) cell(sheet.createRow(r++), 0, s.heading(), heading);
                Row head = sheet.createRow(r++);
                float[] widths = columnWidths(s);
                for (int c = 0; c < s.columns().size(); c++) {
                    cell(head, c, s.columns().get(c).name(), header);
                    chars[c] = Math.max(chars[c], (int) widths[c]);
                }
                for (List<String> values : s.rows()) {
                    Row row = sheet.createRow(r++);
                    for (int c = 0; c < values.size(); c++) {
                        String v = values.get(c);
                        if (s.columns().get(c).numeric() && isNumber(v)) {
                            row.createCell(c).setCellValue(Double.parseDouble(v));
                        } else {
                            cell(row, c, v, null);
                        }
                    }
                }
            }
            for (int c = 0; c < chars.length; c++) sheet.setColumnWidth(c, Math.min(chars[c] + 3, 60) * 256);
            book.write(out);
            book.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) cell.setCellStyle(style);
    }

    private static boolean isNumber(String v) {
        return v != null && v.matches("-?\\d+(\\.\\d+)?");
    }

    // ---- PDF ----

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
    private static final Font DETAIL = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(0x52, 0x51, 0x4e));
    private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
    private static final Font HEAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);
    private static final Font FOOT = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, new Color(0x89, 0x87, 0x81));
    private static final Color HEAD_FILL = new Color(0xf0, 0xef, 0xec);
    private static final Color RULE = new Color(0xe1, 0xe0, 0xd9);

    /** A4 landscape; each section a table whose header repeats on every page; footer with page numbers. */
    static byte[] pdf(Report report, String footer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 32, 32, 32, 40);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter w, Document d) {
                ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_LEFT, new Phrase(footer, FOOT), d.left(), d.bottom() - 18, 0);
                ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_RIGHT, new Phrase("Page " + w.getPageNumber(), FOOT),
                        d.right(), d.bottom() - 18, 0);
            }
        });
        doc.addTitle(report.title());
        doc.open();
        doc.add(new Paragraph(report.title(), TITLE));
        for (String line : report.details()) doc.add(new Paragraph(line, DETAIL));
        if (report.sections().stream().allMatch(s -> s.rows().isEmpty())) {
            Paragraph none = new Paragraph("Nothing matches these filters.", BODY);
            none.setSpacingBefore(12);
            doc.add(none);
        }
        for (Section s : report.sections()) {
            if (s.heading() != null) {
                // Keep a heading with the start of its table: no heading alone at the foot of a page.
                if (writer.getVerticalPosition(true) - doc.bottom() < 90) doc.newPage();
                Paragraph h = new Paragraph(16, s.heading(), HEADING);
                h.setSpacingBefore(12);
                h.setSpacingAfter(6);
                h.setKeepTogether(true);
                doc.add(h);
            } else {
                doc.add(new Paragraph(Chunk.NEWLINE));
            }
            PdfPTable table = new PdfPTable(s.columns().size());
            float[] widths = columnWidths(s);
            float total = 0;
            for (float w : widths) total += w;
            table.setWidths(widths);
            // Narrow tables (e.g. a two-column summary) stay narrow instead of stretching across the page.
            table.setWidthPercentage(Math.max(45, Math.min(100, total * 1.3f)));
            table.setHorizontalAlignment(Element.ALIGN_LEFT);
            table.setHeaderRows(1);
            table.setSpacingBefore(6);
            table.setSpacingAfter(4);
            for (Column c : s.columns()) {
                PdfPCell cell = new PdfPCell(new Phrase(c.name(), HEAD));
                cell.setBackgroundColor(HEAD_FILL);
                cell.setBorderColor(RULE);
                cell.setPadding(4);
                if (c.numeric()) cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                table.addCell(cell);
            }
            for (List<String> row : s.rows()) {
                for (int c = 0; c < row.size(); c++) {
                    PdfPCell cell = new PdfPCell(new Phrase(row.get(c) == null ? "" : row.get(c), BODY));
                    cell.setBorderColor(RULE);
                    cell.setPadding(4);
                    if (s.columns().get(c).numeric()) cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    table.addCell(cell);
                }
            }
            doc.add(table);
        }
        doc.close();
        return out.toByteArray();
    }

    /** Relative column widths from the longest value in each column (header included), within sensible bounds. */
    static float[] columnWidths(Section s) {
        float[] widths = new float[s.columns().size()];
        for (int c = 0; c < widths.length; c++) {
            int longest = s.columns().get(c).name().length();
            for (List<String> row : s.rows()) longest = Math.max(longest, row.get(c) == null ? 0 : row.get(c).length());
            widths[c] = Math.max(6, Math.min(longest, 40));
        }
        return widths;
    }
}
