package com.invigilo.college;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import com.invigilo.audit.AuditRepository;
import com.invigilo.identity.Role;
import com.invigilo.policy.PolicySettings;
import com.invigilo.support.IntegrationTest;
import com.invigilo.support.TestData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

@SuppressWarnings({"unchecked", "rawtypes"})
class OnboardingIntegrationTest extends IntegrationTest {

    @Autowired AuditRepository auditLog;

    TestData.Registered college;

    @BeforeEach
    void register() {
        college = data().registerCollege();
    }

    static Map<String, Object> profile() {
        Map<String, Object> p = new HashMap<>();
        p.put("name", "Government Engineering College, Thrissur");
        p.put("affiliation", "APJ Abdul Kalam Technological University");
        p.put("address", "Ramavarmapuram");
        p.put("city", "Thrissur");
        p.put("state", "Kerala");
        p.put("postalCode", "680009");
        p.put("contactEmail", "exams@gectcr.example.test");
        p.put("contactPhone", "+91 487 0000000");
        p.put("academicYear", "2026-27");
        p.put("timeZone", "Asia/Kolkata");
        return p;
    }

    Map<String, Object> savePolicy(String token, int basedOn, PolicySettings settings) {
        var response = data().send(PUT, "/api/v1/policy", token,
                Map.of("basedOnVersion", basedOn, "settings", settings), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Nested
    class Profile {

        @Test
        void newCollegeReportsMissingProfileFields() {
            var details = data().get("/api/v1/college", college.accessToken(), Map.class).getBody();

            assertThat(details).containsEntry("status", "ONBOARDING");
            assertThat((List<String>) details.get("missingProfileFields"))
                    .containsExactly("affiliation", "address", "city", "state", "academicYear");
        }

        @Test
        void adminUpdatesProfileAndChangeIsAudited() {
            var response = data().send(PUT, "/api/v1/college", college.accessToken(), profile(), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((List<?>) response.getBody().get("missingProfileFields")).isEmpty();
            assertThat((Map<String, Object>) response.getBody().get("profile"))
                    .containsEntry("affiliation", "APJ Abdul Kalam Technological University")
                    .containsEntry("academicYear", "2026-27");
            var entries = tenants.asTenant(college.collegeId(),
                    () -> auditLog.findByEntityTypeAndEntityIdOrderByOccurredAtDesc("college", college.collegeId()));
            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().getAction()).isEqualTo("COLLEGE_PROFILE_UPDATED");
            assertThat(entries.getFirst().getActorUserId()).isEqualTo(college.userId());
            assertThat(entries.getFirst().getAfterState().get("city").asText()).isEqualTo("Thrissur");
        }

        @Test
        void validatesProfile() {
            Map<String, Object> bad = profile();
            bad.put("academicYear", "2026");
            bad.put("contactEmail", "nope");
            bad.put("city", " ");

            var response = data().send(PUT, "/api/v1/college", college.accessToken(), bad, Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((Map<String, String>) response.getBody().get("fieldErrors"))
                    .containsOnlyKeys("academicYear", "contactEmail", "city");
        }

        @Test
        void rejectsUnknownTimeZone() {
            Map<String, Object> bad = profile();
            bad.put("timeZone", "Mars/Olympus");

            var response = data().send(PUT, "/api/v1/college", college.accessToken(), bad, Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody()).containsEntry("code", "INVALID_TIME_ZONE");
        }

        @Test
        void facultyCanReadButNotChangeTheCollege() {
            String faculty = signInAsNewMember(college.collegeId(), Role.FACULTY);

            assertThat(data().get("/api/v1/college", faculty, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            var update = data().send(PUT, "/api/v1/college", faculty, profile(), Map.class);
            assertThat(update.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(update.getBody()).containsEntry("code", "ACCESS_DENIED");
        }
    }

    @Nested
    class Policy {

        @Test
        void unsavedPolicyIsReportedAsDefaults() {
            var policy = data().get("/api/v1/policy", college.accessToken(), Map.class).getBody();

            assertThat(policy).containsEntry("version", 0).containsEntry("saved", false);
            assertThat((Map<String, Object>) ((Map<String, Object>) policy.get("settings")).get("workload"))
                    .containsEntry("maxDutiesPerDay", 2);
        }

        @Test
        void eachSaveCreatesANewImmutableVersion() {
            var v1 = savePolicy(college.accessToken(), 0, PolicySettings.defaults());
            var changed = withMaxDutiesPerDay(PolicySettings.defaults(), 3);
            var v2 = savePolicy(college.accessToken(), 1, changed);

            assertThat(v1).containsEntry("version", 1).containsEntry("saved", true);
            assertThat(v2).containsEntry("version", 2);
            assertThat(v2.get("createdByName")).isEqualTo("Admin " + college.collegeCode().substring(1));

            var old = data().get("/api/v1/policy/versions/1", college.accessToken(), Map.class).getBody();
            assertThat((Map<String, Object>) ((Map<String, Object>) old.get("settings")).get("workload"))
                    .containsEntry("maxDutiesPerDay", 2);
            var history = data().get("/api/v1/policy/versions", college.accessToken(), List.class).getBody();
            assertThat(history).extracting(h -> ((Map<String, Object>) h).get("version")).containsExactly(2, 1);
        }

        @Test
        void savingIdenticalSettingsDoesNotCreateAVersion() {
            savePolicy(college.accessToken(), 0, PolicySettings.defaults());
            var again = savePolicy(college.accessToken(), 1, PolicySettings.defaults());

            assertThat(again).containsEntry("version", 1);
        }

        @Test
        void staleEditorIsRefused() {
            savePolicy(college.accessToken(), 0, PolicySettings.defaults());

            var stale = data().send(PUT, "/api/v1/policy", college.accessToken(),
                    Map.of("basedOnVersion", 0, "settings", withMaxDutiesPerDay(PolicySettings.defaults(), 4)), Map.class);

            assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(stale.getBody()).containsEntry("code", "POLICY_CHANGED");
        }

        @Test
        void validatesNestedSettingsWithFieldPaths() {
            var response = data().send(PUT, "/api/v1/policy", college.accessToken(),
                    Map.of("basedOnVersion", 0, "settings", withMaxDutiesPerDay(PolicySettings.defaults(), 0)), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((Map<String, String>) response.getBody().get("fieldErrors"))
                    .containsKey("settings.workload.maxDutiesPerDay");
        }

        @Test
        void everyRuleMustBeStatedExplicitly() {
            Map<String, Object> missingRule = new HashMap<>(Map.of(
                    "allowOwnSubject", false, "ownDepartmentExams", "ALLOW", "visitingFacultyEligible", true,
                    "seniorsGetFewerDuties", false, "chiefInvigilator", "NONE", "chiefCountsTowardsStaffing", true));
            var d = PolicySettings.defaults();
            var settings = Map.of("faculty", missingRule, "workload", d.workload(), "staffing", d.staffing(),
                    "availability", d.availability(), "swaps", d.swaps());

            var response = data().send(PUT, "/api/v1/policy", college.accessToken(),
                    Map.of("basedOnVersion", 0, "settings", settings), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((Map<String, String>) response.getBody().get("fieldErrors"))
                    .containsKey("settings.faculty.preferSeniorsAsChief");
        }

        @Test
        void onlyAdminsChangeOrBrowseHistoryButEveryoneCanRead() {
            String hod = signInAsNewMember(college.collegeId(), Role.HOD, Role.FACULTY);

            assertThat(data().get("/api/v1/policy", hod, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(data().send(PUT, "/api/v1/policy", hod,
                    Map.of("basedOnVersion", 0, "settings", PolicySettings.defaults()), Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(data().get("/api/v1/policy/versions", hod, Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void policiesAreIsolatedBetweenColleges() {
            var other = data().registerCollege();
            savePolicy(college.accessToken(), 0, withMaxDutiesPerDay(PolicySettings.defaults(), 5));

            var otherPolicy = data().get("/api/v1/policy", other.accessToken(), Map.class).getBody();
            var otherVersion = data().get("/api/v1/policy/versions/1", other.accessToken(), Map.class);

            assertThat(otherPolicy).containsEntry("version", 0);
            assertThat(otherVersion.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(savePolicy(other.accessToken(), 0, PolicySettings.defaults())).containsEntry("version", 1);
        }
    }

    @Nested
    class Completion {

        @Test
        void requiresProfileAndPolicy() {
            var response = data().send(POST, "/api/v1/college/complete-onboarding", college.accessToken(), null, Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody()).containsEntry("code", "ONBOARDING_INCOMPLETE");
            assertThat((String) response.getBody().get("detail")).contains("affiliation", "policy");
        }

        @Test
        void activatesCollegeOnceEverythingIsInPlace() {
            data().send(PUT, "/api/v1/college", college.accessToken(), profile(), Map.class);
            savePolicy(college.accessToken(), 0, PolicySettings.defaults());

            var response = data().send(POST, "/api/v1/college/complete-onboarding", college.accessToken(), null, Map.class);
            var again = data().send(POST, "/api/v1/college/complete-onboarding", college.accessToken(), null, Map.class);
            var me = data().get("/api/v1/auth/me", college.accessToken(), Map.class).getBody();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("status", "ACTIVE");
            assertThat(response.getBody().get("onboardingCompletedAt")).isNotNull();
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((Map<String, Object>) me.get("college")).containsEntry("status", "ACTIVE");
        }
    }

    static PolicySettings withMaxDutiesPerDay(PolicySettings s, int max) {
        var w = s.workload();
        return new PolicySettings(s.faculty(),
                new PolicySettings.WorkloadRules(max, w.consecutiveSessions(), w.minRestMinutes(), w.consecutiveDays(),
                        w.maxDutiesPerPeriod(), w.equalDistributionWeight(), w.historicalWorkloadPercent(),
                        w.avoidRepeatHallWeight()),
                s.staffing(), s.availability(), s.swaps());
    }
}
