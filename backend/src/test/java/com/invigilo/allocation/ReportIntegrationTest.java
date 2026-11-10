package com.invigilo.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Report downloads (M16): duty chart groupings and filters, workload, fairness, formats and access. */
@SuppressWarnings({"unchecked", "rawtypes"})
class ReportIntegrationTest extends ChartScenario {

    String reports() {
        return "/api/v1/exam-periods/" + period + "/reports";
    }

    ResponseEntity<byte[]> download(String path) {
        return data().send(GET, reports() + path, college.accessToken(), null, byte[].class);
    }

    /** The CSV lines without the byte-order mark. */
    List<String> csv(String path) {
        var r = download(path);
        assertThat(r.getStatusCode()).as(r.getBody() == null ? "" : new String(r.getBody(), StandardCharsets.UTF_8)).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("text/csv");
        return new String(r.getBody(), StandardCharsets.UTF_8).substring(1).lines().toList();
    }

    @Test
    void dutyChartByEveryGroupingWithFiltersAndOpenPositions() {
        generate();
        // Before publishing only an explicit version can be printed (e.g. the draft under review).
        assertThat(data().send(GET, reports() + "/duty-chart?format=csv", college.accessToken(), null, Map.class).getBody())
                .containsEntry("code", "NO_PUBLISHED_CHART");
        assertThat(csv("/duty-chart?format=csv&version=1")).hasSize(1 + 5);

        // Leave one afternoon position open, then publish.
        Map<String, Object> removed = duties(chart(), "AN", "H1").getFirst();
        send(DELETE, base + "/assignments/" + removed.get("assignmentId") + "?version=" + removed.get("version"), null);
        publishCurrentDraft();

        var byDate = download("/duty-chart?format=csv");
        assertThat(byDate.getHeaders().getFirst("Content-Disposition")).contains("attachment").contains("duty-chart-mar-2027-by-date.csv");
        List<String> lines = new String(byDate.getBody(), StandardCharsets.UTF_8).substring(1).lines().toList();
        assertThat(lines.getFirst()).isEqualTo("Date,Session,Time,Hall,Role,Faculty,Code,Department");
        assertThat(lines).hasSize(1 + 4 + 1).allSatisfy(l -> assertThat(l).isNotBlank());
        assertThat(lines).anySatisfy(l -> assertThat(l).isEqualTo("Mon 1 Mar 2027,AN,13:30-16:30,H1,Invigilator,(open position),,"));

        assertThat(csv("/duty-chart?format=csv&groupBy=faculty").getFirst()).isEqualTo("Faculty,Date,Session,Time,Hall,Role");
        assertThat(csv("/duty-chart?format=csv&groupBy=faculty")).hasSize(1 + 4); // open positions have nobody to group by
        assertThat(csv("/duty-chart?format=csv&groupBy=department").getFirst()).isEqualTo("Department,Faculty,Code,Date,Session,Time,Hall,Role");
        assertThat(csv("/duty-chart?format=csv&groupBy=hall").getFirst()).isEqualTo("Hall,Date,Session,Time,Role,Faculty,Code,Department");
        assertThat(csv("/duty-chart?format=csv&groupBy=session").subList(1, 6)).allSatisfy(l -> assertThat(l).startsWith("Mon 1 Mar 2027 · "));

        // Filters.
        assertThat(csv("/duty-chart?format=csv&session=AN")).hasSize(1 + 2).allSatisfy(l -> assertThat(l).doesNotContain(",FN,"));
        assertThat(csv("/duty-chart?format=csv&department=ECE").subList(1, csv("/duty-chart?format=csv&department=ECE").size()))
                .allSatisfy(l -> assertThat(l).endsWith(",ECE"));
        assertThat(csv("/duty-chart?format=csv&hall=H2")).hasSize(1 + 1);
        assertThat(csv("/duty-chart?format=csv&from=2027-03-02")).hasSize(1);
    }

    @Test
    void pdfAndExcelAndTheWorkloadAndFairnessReports() throws Exception {
        generate();
        publishCurrentDraft();

        var pdf = download("/duty-chart?format=pdf&groupBy=session");
        assertThat(pdf.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        PdfReader reader = new PdfReader(pdf.getBody());
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();
        assertThat(text).contains("Duty chart by session", "Mar 2027", "Mon 1 Mar 2027 · FN 09:30-12:30", "H1");

        var xlsx = download("/duty-chart?format=xlsx&groupBy=hall");
        assertThat(xlsx.getHeaders().getFirst("Content-Disposition")).contains("duty-chart-mar-2027-by-hall.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx.getBody()))) {
            assertThat(book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Duty chart by hall");
        }

        List<String> workload = csv("/workload?format=csv");
        assertThat(workload.getFirst()).isEqualTo("Faculty,Code,Department,Duty weight,Duties,As chief,Even share,Workload,All periods");
        assertThat(workload).hasSize(1 + 6);
        assertThat(csv("/workload?format=csv&department=CSE")).hasSize(1 + 1);

        var fairness = download("/fairness?format=pdf");
        reader = new PdfReader(fairness.getBody());
        String fairnessText = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();
        assertThat(fairnessText).contains("Fairness of duty allocation", "Summary", "Departments", "Across exam periods");
    }

    @Test
    void onlyTheExamCellOfThisCollegeAndOnlyKnownOptions() {
        generate();
        publishCurrentDraft();

        assertThat(data().send(GET, reports() + "/duty-chart?format=doc", college.accessToken(), null, Map.class).getBody())
                .containsEntry("code", "UNKNOWN_FORMAT");
        assertThat(data().send(GET, reports() + "/duty-chart?groupBy=room", college.accessToken(), null, Map.class).getBody())
                .containsEntry("code", "UNKNOWN_GROUPING");
        assertThat(data().send(GET, reports() + "/duty-chart?version=9", college.accessToken(), null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(data().send(GET, reports() + "/duty-chart", loginAs("F3"), null, Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String outsider = loginAtAnotherCollege(List.of("EXAM_CELL_ADMIN"));
        assertThat(data().send(GET, reports() + "/duty-chart", outsider, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(data().send(GET, reports() + "/workload", outsider, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
