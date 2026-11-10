package com.invigilo.allocation;

import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.exam.ExamSession;
import com.invigilo.exam.SessionHall;
import com.invigilo.tenant.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A faculty member's own duties (M11): only from published charts, split into upcoming and past by
 * the college's local time, with the colleagues on duty in the same hall. Also renders them as an
 * iCalendar file to import into a calendar app.
 */
@Service
public class MyDutiesService {

    /** One duty as the faculty member sees it. */
    public record MyDuty(UUID assignmentId, UUID periodId, String periodName, LocalDate date, String sessionLabel,
                         LocalTime startTime, LocalTime endTime, String hallCode, String hallName, String building, Role role,
                         DutyAssignment.Status status, List<String> colleagues) {}

    /** {@code upcoming} soonest first; {@code past} most recent first. */
    public record MyDuties(String timeZone, LocalDate today, int chiefDuties, List<MyDuty> upcoming, List<MyDuty> past) {}

    private static final DateTimeFormatter ICS_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final DutyAssignmentRepository assignments;
    private final OwnFaculty ownFaculty;
    private final CollegeTime collegeTime;
    private final Clock clock;

    MyDutiesService(DutyAssignmentRepository assignments, OwnFaculty ownFaculty, CollegeTime collegeTime, Clock clock) {
        this.assignments = assignments;
        this.ownFaculty = ownFaculty;
        this.collegeTime = collegeTime;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MyDuties mine(CurrentUser user) {
        ZoneId zone = collegeTime.zone();
        LocalDateTime now = collegeTime.now();
        List<MyDuty> all = load(ownFaculty.requireId(user));
        // A duty counts as upcoming until its session has ended.
        List<MyDuty> upcoming = all.stream().filter(d -> d.date().atTime(d.endTime()).isAfter(now))
                .sorted(Comparator.comparing(MyDuty::date).thenComparing(MyDuty::startTime).thenComparing(MyDuty::hallCode))
                .toList();
        List<MyDuty> past = all.stream().filter(d -> !d.date().atTime(d.endTime()).isAfter(now))
                .sorted(Comparator.comparing(MyDuty::date).thenComparing(MyDuty::startTime).reversed())
                .toList();
        int chief = (int) all.stream().filter(d -> d.role() == Role.CHIEF).count();
        return new MyDuties(zone.getId(), now.toLocalDate(), chief, upcoming, past);
    }

    /** All published duties as an iCalendar (RFC 5545) document, times in UTC. */
    @Transactional(readOnly = true)
    public String calendar(CurrentUser user) {
        ZoneId zone = collegeTime.zone();
        String stamp = ICS_UTC.format(Instant.now(clock).atOffset(ZoneOffset.UTC));
        StringBuilder ics = new StringBuilder();
        line(ics, "BEGIN:VCALENDAR");
        line(ics, "VERSION:2.0");
        line(ics, "PRODID:-//Invigilo//Invigilation duties//EN");
        line(ics, "CALSCALE:GREGORIAN");
        line(ics, "METHOD:PUBLISH");
        line(ics, "X-WR-CALNAME:Invigilation duties");
        for (MyDuty d : load(ownFaculty.requireId(user))) {
            String role = d.role() == Role.CHIEF ? "Chief invigilator" : "Invigilator";
            line(ics, "BEGIN:VEVENT");
            line(ics, "UID:" + d.assignmentId() + "@invigilo");
            line(ics, "DTSTAMP:" + stamp);
            line(ics, "DTSTART:" + utc(d.date(), d.startTime(), zone));
            line(ics, "DTEND:" + utc(d.date(), d.endTime(), zone));
            line(ics, "SUMMARY:" + text(role + " - " + d.hallCode() + " (" + d.sessionLabel() + ")"));
            line(ics, "LOCATION:" + text(d.hallName() + (d.building() == null ? "" : ", " + d.building())));
            String with = d.colleagues().isEmpty() ? "" : " With: " + String.join(", ", d.colleagues()) + ".";
            line(ics, "DESCRIPTION:" + text(d.periodName() + ". " + role + " in " + d.hallCode() + "." + with));
            line(ics, "END:VEVENT");
        }
        line(ics, "END:VCALENDAR");
        return ics.toString();
    }

    // ---- loading ----

    private List<MyDuty> load(UUID facultyId) {
        List<DutyAssignment> mine = assignments.findPublishedForFaculty(facultyId);
        if (mine.isEmpty()) return List.of();
        Set<UUID> charts = mine.stream().map(DutyAssignment::getChartId).collect(Collectors.toSet());
        Set<UUID> halls = mine.stream().map(d -> d.getSessionHall().getId()).collect(Collectors.toSet());
        Map<String, List<String>> together = assignments.findInHalls(charts, halls).stream()
                .collect(Collectors.groupingBy(a -> a.getChartId() + "/" + a.getSessionHall().getId(),
                        Collectors.mapping(a -> a, Collectors.toList())))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream()
                        .filter(a -> !a.getFaculty().getId().equals(facultyId))
                        .map(a -> a.getFaculty().getFullName() + (a.getRole() == Role.CHIEF ? " (chief)" : ""))
                        .sorted().toList()));
        List<MyDuty> out = new ArrayList<>();
        for (DutyAssignment d : mine) {
            SessionHall sh = d.getSessionHall();
            ExamSession s = sh.getSession();
            out.add(new MyDuty(d.getId(), s.getPeriod().getId(), s.getPeriod().getName(), s.getSessionDate(), s.getLabel(),
                    s.getStartTime(), s.getEndTime(), sh.getHall().getCode(), sh.getHall().getName(), sh.getHall().getBuilding(),
                    d.getRole(), d.getStatus(), together.getOrDefault(d.getChartId() + "/" + sh.getId(), List.of())));
        }
        out.sort(Comparator.comparing(MyDuty::date).thenComparing(MyDuty::startTime));
        return out;
    }

    // ---- iCalendar helpers ----

    private static String utc(LocalDate date, LocalTime time, ZoneId zone) {
        return ICS_UTC.format(date.atTime(time).atZone(zone).withZoneSameInstant(ZoneOffset.UTC));
    }

    /** Escapes TEXT values (RFC 5545 §3.3.11). */
    private static String text(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    /** Writes a content line, folded at 75 octets as RFC 5545 §3.1 requires. */
    private static void line(StringBuilder out, String content) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= 75) {
            out.append(content).append("\r\n");
            return;
        }
        StringBuilder current = new StringBuilder();
        int size = 0;
        int limit = 75;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (size + len > limit) {
                out.append(current).append("\r\n ");
                current.setLength(0);
                size = 0;
                limit = 74; // continuation lines start with a space
            }
            current.appendCodePoint(cp);
            size += len;
            i += Character.charCount(cp);
        }
        out.append(current).append("\r\n");
    }
}
