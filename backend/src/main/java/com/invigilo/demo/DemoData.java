package com.invigilo.demo;

import com.invigilo.auth.AuthDtos.IssuedSession;
import com.invigilo.auth.AuthDtos.RegisterCollegeRequest;
import com.invigilo.auth.AuthService;
import com.invigilo.availability.AvailabilityRequest;
import com.invigilo.availability.AvailabilityService;
import com.invigilo.college.College;
import com.invigilo.college.CollegeRepository;
import com.invigilo.college.CollegeService;
import com.invigilo.department.DepartmentService;
import com.invigilo.department.DepartmentService.DepartmentInput;
import com.invigilo.exam.Exam;
import com.invigilo.exam.ExamPeriodService;
import com.invigilo.exam.ExamPeriodService.Slot;
import com.invigilo.exam.ScheduleService;
import com.invigilo.exam.ScheduleService.ExamInput;
import com.invigilo.exam.ScheduleService.SeatInput;
import com.invigilo.exam.ScheduleService.SessionHallInput;
import com.invigilo.faculty.Faculty;
import com.invigilo.faculty.FacultyRestriction;
import com.invigilo.faculty.FacultyService;
import com.invigilo.faculty.FacultyService.FacultyInput;
import com.invigilo.faculty.FacultyService.RestrictionInput;
import com.invigilo.hall.HallService;
import com.invigilo.hall.HallService.HallInput;
import com.invigilo.identity.Role;
import com.invigilo.policy.PolicyService;
import com.invigilo.policy.PolicySettings;
import com.invigilo.subject.SubjectService;
import com.invigilo.subject.SubjectService.SubjectInput;
import com.invigilo.tenant.CurrentUser;
import com.invigilo.tenant.SystemTransactions;
import com.invigilo.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a realistic demo college through the normal services (same validation, audit and
 * tenancy as real use): 5 departments, 20 subjects, 12 halls, 50 faculty, a saved policy, and a
 * one-week examination period with seated exams and some leave, ready for "Generate".
 *
 * <p>Deterministic (fixed random seed) and idempotent: if the college code exists nothing is done.
 * Never runs on its own; see {@link DemoDataRunner} (profile {@code demo}).
 */
@Component
public class DemoData {

    public static final String CODE = "DEMO";

    public record Summary(boolean created, UUID collegeId, UUID periodId, int faculty, int sessions) {}

    private static final String[][] DEPARTMENTS = {
        {"CSE", "Computer Science and Engineering"}, {"ECE", "Electronics and Communication"},
        {"ME", "Mechanical Engineering"}, {"CE", "Civil Engineering"}, {"MA", "Mathematics"}};
    private static final String[] FIRST = {"Asha", "Ravi", "Meera", "Arun", "Divya", "Hari", "Lakshmi", "Nikhil", "Priya",
        "Sanjay", "Anitha", "Vivek", "Deepa", "Rahul", "Sreeja", "Manoj", "Kavya", "Ajith", "Neha", "Suresh"};
    private static final String[] LAST = {"Menon", "Nair", "Pillai", "Kumar", "Varma", "Thomas", "George", "Iyer", "Das", "Rao"};
    private static final String[] DESIGNATIONS = {"Professor", "Associate Professor", "Assistant Professor", "Assistant Professor"};

    private final AuthService auth;
    private final CollegeRepository colleges;
    private final SystemTransactions system;
    private final CollegeService college;
    private final PolicyService policies;
    private final DepartmentService departments;
    private final SubjectService subjects;
    private final HallService halls;
    private final FacultyService faculty;
    private final ExamPeriodService periods;
    private final ScheduleService schedule;
    private final AvailabilityService availability;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DemoData(AuthService auth, CollegeRepository colleges, SystemTransactions system, CollegeService college,
                    PolicyService policies, DepartmentService departments, SubjectService subjects, HallService halls,
                    FacultyService faculty, ExamPeriodService periods, ScheduleService schedule,
                    AvailabilityService availability, PlatformTransactionManager transactionManager, Clock clock) {
        this.auth = auth;
        this.colleges = colleges;
        this.system = system;
        this.college = college;
        this.policies = policies;
        this.departments = departments;
        this.subjects = subjects;
        this.halls = halls;
        this.faculty = faculty;
        this.periods = periods;
        this.schedule = schedule;
        this.availability = availability;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Loads the demo college with the given exam-cell admin login, unless code {@value #CODE} exists. */
    public Summary load(String adminEmail, String adminPassword) {
        if (system.execute(() -> colleges.existsByCode(CODE))) {
            return new Summary(false, null, null, 0, 0);
        }
        IssuedSession admin = auth.registerCollege(new RegisterCollegeRequest("Invigilo Demo College", CODE,
                "Demo Exam Cell", adminEmail, adminPassword));
        UUID collegeId = admin.session().college().id();
        CurrentUser actor = new CurrentUser(admin.session().user().id(), collegeId, admin.session().membershipId(),
                Set.of(Role.EXAM_CELL_ADMIN));
        return TenantContext.callAsTenant(collegeId, () -> tx.execute(status -> populate(collegeId, actor)));
    }

    private Summary populate(UUID collegeId, CurrentUser actor) {
        Random random = new Random(2027);
        college.updateProfile(new College.Profile("Invigilo Demo College", "Demo Technological University",
                "1 College Road", "Thrissur", "Kerala", "680001", null, null, academicYear(), College.DEFAULT_TIME_ZONE));
        policies.save(0, PolicySettings.defaults(), actor.userId());
        college.completeOnboarding();

        Map<String, UUID> deptIds = new LinkedHashMap<>();
        for (String[] d : DEPARTMENTS) deptIds.put(d[0], departments.create(new DepartmentInput(d[0], d[1], true)).id());
        Map<String, List<UUID>> subjectsByDept = new LinkedHashMap<>();
        List<UUID> allSubjects = new ArrayList<>();
        for (String dept : deptIds.keySet()) {
            for (int i = 1; i <= 4; i++) {
                UUID id = subjects.create(new SubjectInput(dept + "30" + i, dept + " Paper " + i, deptIds.get(dept), "B.Tech", 5, true)).id();
                subjectsByDept.computeIfAbsent(dept, k -> new ArrayList<>()).add(id);
                allSubjects.add(id);
            }
        }
        List<UUID> theoryHalls = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            theoryHalls.add(halls.create(new HallInput(String.format("H1%02d", i), "Main Block " + (100 + i), "Main Block", 60,
                    i <= 2 ? 2 : 1, false, true)).id());
        }
        UUID lab = halls.create(new HallInput("LAB1", "Computer Lab 1", "Lab Block", 30, 2, true, true)).id();
        halls.create(new HallInput("LAB2", "Electronics Lab", "Lab Block", 30, 2, true, true));

        List<UUID> facultyIds = new ArrayList<>();
        int n = 0;
        for (String dept : deptIds.keySet()) {
            List<UUID> own = subjectsByDept.get(dept);
            for (int i = 0; i < 10; i++, n++) {
                String name = (i % 4 == 0 ? "Dr. " : "") + FIRST[n % FIRST.length] + " " + LAST[(n / 2) % LAST.length];
                Faculty.EmploymentType type = n % 17 == 5 ? Faculty.EmploymentType.VISITING : Faculty.EmploymentType.REGULAR;
                List<UUID> teaches = List.of(own.get(i % 4), own.get((i + 1) % 4));
                facultyIds.add(faculty.create(new FacultyInput(String.format("F%03d", n + 1), name, deptIds.get(dept),
                        "f" + (n + 1) + "@demo.invigilo.test", null, DESIGNATIONS[i % 4], type,
                        LocalDate.of(1995 + random.nextInt(28), 1 + random.nextInt(12), 1), i % 3 == 0,
                        i == 9 ? new BigDecimal("0.50") : BigDecimal.ONE, true, teaches)).id());
            }
        }
        faculty.addRestriction(facultyIds.get(3), new RestrictionInput(FacultyRestriction.Type.EXCLUDE_HALL, theoryHalls.get(9), null, null,
                "Upper-floor hall; mobility"));
        faculty.addRestriction(facultyIds.get(12), new RestrictionInput(FacultyRestriction.Type.MAX_DUTIES_PER_PERIOD, null, null, 2,
                "Course coordinator this semester"));

        LocalDate start = LocalDate.now(clock).plusWeeks(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusDays(5);
        UUID periodId = periods.create("Demo end-semester examinations", start, end).id();
        periods.createSessions(periodId, start, start.plusDays(4), Set.of(DayOfWeek.SUNDAY), List.of(
                new Slot("FN", LocalTime.of(9, 30), LocalTime.of(12, 30)),
                new Slot("AN", LocalTime.of(13, 30), LocalTime.of(16, 30))));

        var sessionList = periods.sessionsOf(periodId);
        int subjectCursor = 0;
        for (int s = 0; s < sessionList.size(); s++) {
            UUID sessionId = sessionList.get(s).getId();
            List<UUID> examIds = new ArrayList<>();
            List<Integer> sizes = new ArrayList<>();
            for (int e = 0; e < 3; e++) {
                UUID subject = allSubjects.get(subjectCursor++ % allSubjects.size());
                int students = 40 + random.nextInt(70);
                var detail = schedule.addExam(sessionId, new ExamInput(subject, Exam.Type.THEORY, students, null));
                examIds.add(detail.exams().stream().filter(x -> x.subject().id().equals(subject)).findFirst().orElseThrow().id());
                sizes.add(students);
            }
            // Pack students into halls in order; a hall may seat two exams (mixed seating).
            int hallIndex = 0;
            int free = 60;
            Map<Integer, List<SeatInput>> seating = new LinkedHashMap<>();
            for (int e = 0; e < examIds.size(); e++) {
                int left = sizes.get(e);
                while (left > 0) {
                    int put = Math.min(left, free);
                    seating.computeIfAbsent(hallIndex, k -> new ArrayList<>()).add(new SeatInput(examIds.get(e), put));
                    left -= put;
                    free -= put;
                    if (free == 0) {
                        hallIndex++;
                        free = 60;
                    }
                }
            }
            for (var entry : seating.entrySet()) {
                schedule.addHall(sessionId, new SessionHallInput(theoryHalls.get(entry.getKey()), null, null, entry.getValue()));
            }
            if (s == 1) {
                UUID practicalSubject = subjectsByDept.get("CSE").get(0); // not examined as theory in this session
                var detail = schedule.addExam(sessionId, new ExamInput(practicalSubject, Exam.Type.PRACTICAL, 25, "Programming lab"));
                UUID practical = detail.exams().stream().filter(x -> x.subject().id().equals(practicalSubject)).findFirst().orElseThrow().id();
                schedule.addHall(sessionId, new SessionHallInput(lab, null, null, List.of(new SeatInput(practical, 25))));
            }
        }
        for (int i = 0; i < 5; i++) {
            availability.recordFor(actor, facultyIds.get(i * 9 + 1), new AvailabilityService.RequestInput(start.plusDays(i), start.plusDays(i),
                    null, AvailabilityRequest.Type.LEAVE, "Demo leave"));
        }
        return new Summary(true, collegeId, periodId, facultyIds.size(), sessionList.size());
    }

    private LocalDate academicYearStart() {
        LocalDate today = LocalDate.now(clock);
        return LocalDate.of(today.getMonthValue() >= 6 ? today.getYear() : today.getYear() - 1, 6, 1);
    }

    private String academicYear() {
        int y = academicYearStart().getYear();
        return y + "-" + String.format("%02d", (y + 1) % 100);
    }
}
