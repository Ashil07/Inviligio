package com.invigilo.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** The faculty dashboard (M11): own published duties only, colleagues, calendar file, access rules. */
@SuppressWarnings({"unchecked", "rawtypes"})
class MyDutiesIntegrationTest extends ChartScenario {

    Map<String, Object> myDuties(String token) {
        var r = data().send(GET, "/api/v1/me/duties", token, null, Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static List<Map<String, Object>> upcoming(Map<String, Object> mine) {
        return (List<Map<String, Object>>) mine.get("upcoming");
    }

    /** The code of someone with a duty in FN H1, and of their colleague there. */
    static String[] pairInMorningH1(Map<String, Object> chart) {
        var h1 = duties(chart, "FN", "H1");
        return new String[] {(String) h1.get(0).get("facultyCode"), (String) h1.get(1).get("facultyCode")};
    }

    @Test
    void facultySeeOnlyTheirPublishedDutiesWithTheirColleagues() {
        var draft = generate();
        String[] pair = pairInMorningH1(draft);
        String token = loginAs(pair[0]);

        // A draft is invisible to faculty.
        assertThat(upcoming(myDuties(token))).isEmpty();

        publishCurrentDraft();
        Map<String, Object> mine = myDuties(token);

        assertThat(mine).containsEntry("timeZone", "Asia/Kolkata").containsEntry("past", List.of());
        var duties = upcoming(mine);
        assertThat(duties).isNotEmpty();
        Map<String, Object> morning = duties.getFirst();
        assertThat(morning).containsEntry("date", "2027-03-01").containsEntry("sessionLabel", "FN").containsEntry("hallCode", "H1")
                .containsEntry("hallName", "Hall 1").containsEntry("periodName", "Mar 2027").containsEntry("role", "INVIGILATOR")
                .containsEntry("startTime", "09:30:00");
        assertThat((List<String>) morning.get("colleagues")).containsExactly("Faculty " + pair[1]);
        // Every duty listed is really theirs in the published chart.
        long inChart = ((List<Map<String, Object>>) chart().get("sessions")).stream()
                .flatMap(s -> ((List<Map<String, Object>>) s.get("halls")).stream())
                .flatMap(h -> ((List<Map<String, Object>>) h.get("duties")).stream())
                .filter(d -> pair[0].equals(d.get("facultyCode"))).count();
        assertThat(duties).hasSize((int) inChart);

        // A newer published version replaces the old one: duties are not listed twice.
        send(POST, base + "/draft", null);
        publishCurrentDraft();
        assertThat(upcoming(myDuties(token))).hasSize((int) inChart);
    }

    @Test
    void downloadsTheDutiesAsACalendarFile() {
        String code = pairInMorningH1(generate())[0];
        publishCurrentDraft();
        String token = loginAs(code);

        var r = data().send(GET, "/api/v1/me/duties.ics", token, null, String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType()).isNotNull();
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.parseMediaType("text/calendar"))).isTrue();
        assertThat(r.getHeaders().getFirst("Content-Disposition")).contains("attachment").contains("invigilation-duties.ics");
        String ics = r.getBody();
        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n").endsWith("END:VCALENDAR\r\n").contains("BEGIN:VEVENT\r\n")
                // 09:30-12:30 in Asia/Kolkata is 04:00-07:00 UTC.
                .contains("DTSTART:20270301T040000Z\r\n").contains("DTEND:20270301T070000Z\r\n")
                .contains("SUMMARY:Invigilator - H1 (FN)\r\n").contains("LOCATION:Hall 1\r\n");
        assertThat(ics.lines()).allMatch(line -> line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 75);
    }

    @Test
    void onlyLinkedFacultyHaveDutiesAndEachSeesOnlyTheirOwn() {
        generate();
        publishCurrentDraft();

        // The exam-cell login has no faculty record.
        var admin = data().send(GET, "/api/v1/me/duties", college.accessToken(), null, Map.class);
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(admin.getBody()).containsEntry("code", "NOT_A_FACULTY_MEMBER");

        // F2 was on leave all day, so has no duties even though colleagues do.
        assertThat(upcoming(myDuties(loginAs("F2")))).isEmpty();
        // Not signed in at all.
        assertThat(data().send(GET, "/api/v1/me/duties", null, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
