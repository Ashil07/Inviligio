package com.invigilo.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.invigilo.report.Report.Column;
import com.invigilo.report.Report.Section;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ReportWritersTest {

    static final List<Column> COLUMNS = List.of(Column.text("Hall"), Column.text("Faculty"), Column.number("Duties"));

    static Report grouped() {
        return new Report("Duty chart by date", List.of("Demo College · Mar 2027"), "Date", List.of(
                new Section("Mon 1 Mar 2027", COLUMNS, List.of(List.of("H1", "Rao, Asha", "2"), List.of("H2", "Nair \"Meera\"", "1"))),
                new Section("Tue 2 Mar 2027", COLUMNS, List.of(List.of("H1", "Kumar", "3")))));
    }

    @Test
    void csvIsOneFlatTableWithTheGroupFirstWhenSectionsShareColumns() {
        byte[] bytes = ReportWriters.csv(grouped());

        assertThat(bytes[0]).isEqualTo((byte) 0xEF); // BOM so Excel reads UTF-8
        String csv = new String(bytes, StandardCharsets.UTF_8).substring(1);
        assertThat(csv.lines()).containsExactly(
                "Date,Hall,Faculty,Duties",
                "Mon 1 Mar 2027,H1,\"Rao, Asha\",2",
                "Mon 1 Mar 2027,H2,\"Nair \"\"Meera\"\"\",1",
                "Tue 2 Mar 2027,H1,Kumar,3");
    }

    @Test
    void csvWritesBlocksWhenSectionsDiffer() {
        Report mixed = new Report("Fairness", List.of(), null, List.of(
                new Section("Summary", List.of(Column.text("Measure"), Column.number("Value")), List.of(List.of("Filled", "5"))),
                new Section("Departments", List.of(Column.text("Department"), Column.number("Duties")), List.of(List.of("ECE", "4")))));

        String csv = new String(ReportWriters.csv(mixed), StandardCharsets.UTF_8).substring(1);

        assertThat(csv.lines()).containsExactly("Summary", "Measure,Value", "Filled,5", "", "Departments", "Department,Duties", "ECE,4", "");
    }

    @Test
    void excelHasTheTitleSectionsAndRealNumbers() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(ReportWriters.xlsx(grouped())))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Duty chart by date");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Demo College · Mar 2027");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Mon 1 Mar 2027");
            assertThat(sheet.getRow(4).getCell(2).getStringCellValue()).isEqualTo("Duties");
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("Rao, Asha");
            assertThat(sheet.getRow(5).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(5).getCell(2).getNumericCellValue()).isEqualTo(2.0);
        }
    }

    @Test
    void pdfColumnsAreAsWideAsTheirLongestValueWithinBounds() {
        Section s = new Section("x", List.of(Column.text("No"), Column.text("Reason"), Column.number("N")),
                List.of(List.of("1", "a".repeat(80), "12345678")));

        assertThat(ReportWriters.columnWidths(s)).containsExactly(6f, 40f, 8f);
    }

    @Test
    void pdfContainsTheTitleHeadingsRowsAndFooter() throws Exception {
        byte[] pdf = ReportWriters.pdf(grouped(), "Invigilo · Demo College");

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        PdfReader reader = new PdfReader(pdf);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(text).contains("Duty chart by date", "Mon 1 Mar 2027", "Tue 2 Mar 2027", "Rao, Asha", "Kumar", "Invigilo", "Page 1");
        reader.close();
    }
}
