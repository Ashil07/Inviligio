package com.invigilo.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.invigilo.support.IntegrationTest;
import com.invigilo.support.TestData;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;

@SuppressWarnings({"unchecked", "rawtypes"})
class ImportIntegrationTest extends IntegrationTest {

    TestData.Registered college;

    @BeforeEach
    void setUp() {
        college = data().registerCollege();
    }

    ResponseEntity<Map> upload(String type, String csv, boolean dryRun) {
        return upload(type, csv.getBytes(StandardCharsets.UTF_8), dryRun, college.accessToken());
    }

    ResponseEntity<Map> upload(String type, byte[] bytes, boolean dryRun, String token) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "import.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange("/api/v1/imports/" + type + "?dryRun=" + dryRun, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    List<Map<String, Object>> errors(ResponseEntity<Map> r) {
        return (List<Map<String, Object>>) r.getBody().get("errors");
    }

    @Test
    void dryRunReportsErrorsByLineAndColumnAndSavesNothing() {
        String csv = """
                code,name,active
                CSE,Computer Science,yes
                bad code,Broken,yes
                cse,Duplicate,maybe
                """;

        var r = upload("departments", csv, true);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("applied", false).containsEntry("rows", 3);
        assertThat(errors(r)).extracting(e -> e.get("line") + ":" + e.get("column"))
                .containsExactlyInAnyOrder("3:code", "4:active", "4:code");
        assertThat(data().get("/api/v1/departments?includeInactive=true", college.accessToken(), List.class).getBody()).isEmpty();
    }

    @Test
    void importsInOrderAndUpdatesExistingCodes() {
        var depts = upload("departments", "code,name\nCSE,Computer Science\nECE,Electronics\n", false);
        assertThat(depts.getBody()).containsEntry("applied", true).containsEntry("created", 2);

        String subjects = """
                code,name,department_code,semester
                CS301,Algorithms,CSE,5
                EC201,Signals,ece,3
                """;
        var dry = upload("subjects", subjects, true);
        assertThat(dry.getBody()).containsEntry("toCreate", 2).containsEntry("applied", false);
        assertThat(upload("subjects", subjects, false).getBody()).containsEntry("created", 2);

        String faculty = """
                employee_code,full_name,department_code,email,employment_type,joined_on,chief_eligible,duty_weight,subject_codes
                F1,Dr. Asha,CSE,asha@college.example.test,regular,01/06/2015,yes,1.00,CS301;EC201
                F2,Mr. Ravi,ECE,,visiting,,no,0.5,
                """;
        var imported = upload("faculty", faculty, false);
        assertThat(imported.getBody()).as(String.valueOf(imported.getBody())).containsEntry("created", 2);

        var list = data().get("/api/v1/faculty?q=asha", college.accessToken(), Map.class).getBody();
        var asha = ((List<Map<String, Object>>) list.get("items")).getFirst();
        assertThat(asha).containsEntry("subjectCount", 2).containsEntry("chiefEligible", true);

        // Re-import: one change, one identical row.
        String again = faculty.replace("Dr. Asha", "Dr. Asha Menon");
        var reimport = upload("faculty", again, false);
        assertThat(reimport.getBody()).containsEntry("updated", 1).containsEntry("unchanged", 1).containsEntry("created", 0);
    }

    @Test
    void referencesMustExist() {
        upload("departments", "code,name\nCSE,CS\n", false);
        upload("subjects", "code,name,department_code\nCS1,Intro,CSE\n", false);

        var r = upload("faculty", """
                employee_code,full_name,department_code,subject_codes
                F1,A,XYZ,CS1
                F2,B,CSE,CS1;NOPE
                """, true);

        assertThat(errors(r)).extracting(e -> e.get("line") + ":" + e.get("column") + ":" + e.get("message"))
                .contains("2:department_code:Unknown department code XYZ", "3:subject_codes:Unknown subject code NOPE");
    }

    @Test
    void headerProblemsAreReportedBeforeRows() {
        var r = upload("halls", "code,title,capacity\nH1,Hall,60\n", true);

        assertThat(errors(r)).extracting(e -> e.get("column")).containsExactlyInAnyOrder("name", "title");
    }

    @Test
    void acceptsExcelUtf8WithByteOrderMarkAndRejectsOtherEncodings() {
        byte[] withBom = ("﻿code,name,capacity\nH1,Hall 1,60\n").getBytes(StandardCharsets.UTF_8);
        byte[] latin1 = "code,name,capacity\nH2,Salén,60\n".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(upload("halls", withBom, false, college.accessToken()).getBody()).containsEntry("created", 1);
        assertThat((String) errors(upload("halls", latin1, true, college.accessToken())).getFirst().get("message"))
                .contains("UTF-8");
    }

    @Test
    void conflictsWithExistingRecordsAreCaughtInTheDryRun() {
        upload("departments", "code,name\nCSE,CS\n", false);
        upload("faculty", "employee_code,full_name,department_code,email\nF1,A,CSE,a@college.example.test\n", false);

        var caught = upload("faculty", "employee_code,full_name,department_code,email\nF2,B,CSE,a@college.example.test\n", true);

        assertThat((String) errors(caught).getFirst().get("message")).contains("Already used by faculty F1");
    }

    @org.springframework.beans.factory.annotation.Autowired
    ImportApplier applier;

    @Test
    void wholeFileRollsBackWhenARowFailsWhileApplying() {
        upload("departments", "code,name\nCSE,CS\n", false);
        var cse = ((List<Map<String, Object>>) data().get("/api/v1/departments", college.accessToken(), List.class).getBody()).getFirst();
        var good = new ImportService.Row(2, "S1", new com.invigilo.subject.SubjectService.SubjectInput(
                "S1", "Fine", java.util.UUID.fromString((String) cse.get("id")), null, null, true));
        // Passed validation, but its department vanished before applying (simulated with a random id).
        var bad = new ImportService.Row(3, "S2", new com.invigilo.subject.SubjectService.SubjectInput(
                "S2", "Broken", java.util.UUID.randomUUID(), null, null, true));

        var failure = org.assertj.core.api.Assertions.catchThrowableOfType(ImportApplier.RowFailed.class,
                () -> com.invigilo.tenant.TenantContext.callAsTenant(college.collegeId(),
                        () -> applier.apply(ImportType.SUBJECTS, List.of(good, bad))));

        assertThat(failure.line()).isEqualTo(3);
        assertThat(data().get("/api/v1/subjects?includeInactive=true", college.accessToken(), Map.class).getBody())
                .containsEntry("total", 0);
    }

    @Test
    void templatesAndUnknownTypes() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(college.accessToken());
        var template = http.exchange("/api/v1/imports/faculty/template", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(template.getHeaders().getContentDisposition().getFilename()).isEqualTo("invigilo-faculty-template.csv");
        assertThat(template.getBody()).startsWith("employee_code,full_name,department_code,");
        assertThat(upload("rooms", "a\n1\n", true).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void onlyExamCellCanImport() {
        String faculty = signInAsNewMember(college.collegeId(), com.invigilo.identity.Role.FACULTY);
        var r = upload("departments", "code,name\nX,Y\n".getBytes(StandardCharsets.UTF_8), false, faculty);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
