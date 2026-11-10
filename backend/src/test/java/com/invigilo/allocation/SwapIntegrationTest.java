package com.invigilo.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Duty swaps (M13): handover and exchange, colleague acceptance, HOD approval, re-validation when
 * applied, and who may do what.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class SwapIntegrationTest extends ChartScenario {

    /** One duty in the published chart. */
    record Duty(String assignmentId, String session, String hall, String code, String facultyId) {}

    ResponseEntity<Map> as(String token, HttpMethod method, String path, Object body) {
        return data().send(method, path, token, body, Map.class);
    }

    Map<String, Object> ok(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();
        return r.getBody();
    }

    List<Duty> duties() {
        List<Duty> out = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) chart().get("sessions")) {
            for (Map<String, Object> h : (List<Map<String, Object>>) s.get("halls")) {
                for (Map<String, Object> d : (List<Map<String, Object>>) h.get("duties")) {
                    out.add(new Duty((String) d.get("assignmentId"), (String) s.get("label"), (String) h.get("hallCode"),
                            (String) d.get("facultyCode"), (String) d.get("facultyId")));
                }
            }
        }
        return out;
    }

    Duty dutyOf(String code, String session) {
        return duties().stream().filter(d -> d.code().equals(code) && d.session().equals(session)).findFirst().orElse(null);
    }

    static Map<String, Object> input(Duty mine, String targetFacultyId, Duty theirs) {
        Map<String, Object> body = new HashMap<>(Map.of("assignmentId", mine.assignmentId(), "targetFacultyId", targetFacultyId));
        if (theirs != null) body.put("targetAssignmentId", theirs.assignmentId());
        return body;
    }

    static Map<String, Object> decision(Map<String, Object> swap) {
        return Map.of("version", swap.get("version"));
    }

    String swapPath(Map<String, Object> swap, String action) {
        return "/api/v1/swap-requests/" + swap.get("id") + "/" + action;
    }

    /** An ECE member with a morning duty (FN, H2, so anyone may take it) and someone free that morning. */
    Duty morningDutyInH2() {
        return duties().stream().filter(d -> d.session().equals("FN") && d.hall().equals("H2")).findFirst().orElseThrow();
    }

    @Test
    void handoverAcceptedByTheColleagueMovesTheDuty() {
        generate();
        publishCurrentDraft();
        Duty mine = morningDutyInH2();
        String free = freeInMorning(chart());
        String requester = loginAs(mine.code());
        String colleague = loginAs(free);

        // The options list who could take it; F2 (on leave) is not among them.
        Map<String, Object> options = ok(as(requester, GET, "/api/v1/me/swap-options?assignmentId=" + mine.assignmentId(), null));
        List<Map<String, Object>> colleagues = (List<Map<String, Object>>) options.get("colleagues");
        List<String> offered = colleagues.stream().map(c -> (String) ((Map) c.get("faculty")).get("code")).toList();
        assertThat(offered).contains(free).doesNotContain("F2", mine.code());
        assertThat(colleagues.stream().filter(c -> free.equals(((Map) c.get("faculty")).get("code"))).findFirst().orElseThrow())
                .containsEntry("canTakeHandover", true);
        // Someone busy that morning cannot take it outright, but may still trade one of their duties.
        String busy = codesIn(chart(), "FN").stream().filter(code -> !code.equals(mine.code())).findFirst().orElseThrow();
        assertThat(colleagues.stream().filter(c -> busy.equals(((Map) c.get("faculty")).get("code"))).findFirst())
                .hasValueSatisfying(c -> assertThat(c).containsEntry("canTakeHandover", false));
        assertThat(options).containsEntry("swapsEnabled", true);

        Map<String, Object> swap = ok(as(requester, POST, "/api/v1/me/swap-requests",
                Map.of("assignmentId", mine.assignmentId(), "targetFacultyId", facultyIds.get(free), "reason", "Clinic appointment")));
        assertThat(swap).containsEntry("status", "PENDING_RECIPIENT").containsEntry("targetDuty", null);
        // Nothing changes before the colleague agrees.
        assertThat(dutyOf(mine.code(), "FN")).isNotNull();

        Map<String, Object> received = ok(as(colleague, GET, "/api/v1/me/swap-requests", null));
        assertThat((List<Map<String, Object>>) received.get("received")).extracting(r -> r.get("id")).containsExactly(swap.get("id"));

        Map<String, Object> done = ok(as(colleague, POST, swapPath(swap, "accept"), decision(swap)));
        assertThat(done).containsEntry("status", "APPROVED");
        assertThat(done.get("appliedAt")).isNotNull();

        Duty moved = duties().stream().filter(d -> d.assignmentId().equals(mine.assignmentId())).findFirst().orElseThrow();
        assertThat(moved.code()).isEqualTo(free);
        assertThat(field(history(), "summary")).anySatisfy(s -> assertThat((String) s).startsWith("Swap: Faculty " + mine.code())
                .contains("handed over").contains("to Faculty " + free));
        // The colleague now sees the duty on their dashboard.
        var theirDuties = (List<Map<String, Object>>) ok(as(colleague, GET, "/api/v1/me/duties", null)).get("upcoming");
        assertThat(theirDuties).extracting(d -> d.get("assignmentId")).contains(mine.assignmentId());
    }

    @Test
    void aSwapThatNoLongerFitsIsInvalidatedInsteadOfApplied() {
        generate();
        publishCurrentDraft();
        Duty mine = morningDutyInH2();
        String free = freeInMorning(chart());
        String requester = loginAs(mine.code());

        // Checking against someone on leave shows why it cannot work.
        Map<String, Object> check = ok(as(requester, POST, "/api/v1/me/swap-requests/validate", input(mine, facultyIds.get("F2"), null)));
        assertThat(check).containsEntry("allowed", false);
        assertThat(codes(check, "blocking")).contains("UNAVAILABLE");
        assertThat(as(requester, POST, "/api/v1/me/swap-requests", input(mine, facultyIds.get("F2"), null)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        Map<String, Object> swap = ok(as(requester, POST, "/api/v1/me/swap-requests", input(mine, facultyIds.get(free), null)));
        // A second request for the same duty is refused while the first is open.
        var again = as(requester, POST, "/api/v1/me/swap-requests", input(mine, facultyIds.get(free), null));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "SWAP_ALREADY_REQUESTED");

        // The colleague goes on leave before accepting.
        send(POST, "/api/v1/availability", Map.of("facultyId", facultyIds.get(free), "fromDate", "2027-03-01", "toDate", "2027-03-01",
                "type", "LEAVE"));
        Map<String, Object> result = ok(as(loginAs(free), POST, swapPath(swap, "accept"), decision(swap)));

        assertThat(result).containsEntry("status", "INVALIDATED");
        assertThat((String) result.get("invalidReason")).contains("is on approved leave");
        assertThat(dutyOf(mine.code(), "FN")).isNotNull();
    }

    @Test
    void exchangeNeedsTheHeadOfTheRequestersDepartment() {
        swapRules(true, true, true);
        generate();
        publishCurrentDraft();
        String hod = loginAs("F2", List.of("HOD")); // ECE head, on leave that day but still deciding

        // An ECE member with only a morning duty and someone with only an afternoon duty, who may trade.
        Duty mine = null;
        Duty theirs = null;
        String requester = null;
        for (Duty a : duties()) {
            if (!a.session().equals("FN") || a.code().equals("F1") || dutyOf(a.code(), "AN") != null) continue;
            for (Duty b : duties()) {
                if (!b.session().equals("AN") || dutyOf(b.code(), "FN") != null) continue;
                String token = requester == null || !a.equals(mine) ? loginAs(a.code()) : requester;
                if ((Boolean) ok(as(token, POST, "/api/v1/me/swap-requests/validate", input(a, b.facultyId(), b))).get("allowed")) {
                    mine = a;
                    theirs = b;
                    requester = token;
                    break;
                }
            }
            if (mine != null) break;
        }
        assertThat(mine).as("a morning and an afternoon duty that can be exchanged").isNotNull();

        Map<String, Object> swap = ok(as(requester, POST, "/api/v1/me/swap-requests", input(mine, theirs.facultyId(), theirs)));
        Map<String, Object> accepted = ok(as(loginAs(theirs.code()), POST, swapPath(swap, "accept"), decision(swap)));
        assertThat(accepted).containsEntry("status", "PENDING_HOD");
        assertThat(dutyOf(mine.code(), "FN")).isNotNull();

        // Only the requester's head of department (or the exam cell) decides.
        String cseHod = loginAs("F1", List.of("HOD"));
        assertThat((List) data().send(GET, "/api/v1/swap-requests?status=PENDING_HOD", cseHod, null, List.class).getBody()).isEmpty();
        assertThat(as(cseHod, POST, swapPath(accepted, "approve"), decision(accepted)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(as(requester, POST, swapPath(accepted, "approve"), decision(accepted)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        List<Map<String, Object>> waiting = data().send(GET, "/api/v1/swap-requests?status=PENDING_HOD", hod, null, List.class).getBody();
        assertThat(waiting).extracting(w -> w.get("id")).containsExactly(swap.get("id"));

        Map<String, Object> done = ok(as(hod, POST, swapPath(accepted, "approve"), Map.of("version", accepted.get("version"), "note", "Fine")));
        assertThat(done).containsEntry("status", "APPROVED").containsEntry("reviewNote", "Fine").containsEntry("reviewedByName", "Faculty F2");

        assertThat(dutyOf(mine.code(), "AN").assignmentId()).isEqualTo(theirs.assignmentId());
        assertThat(dutyOf(theirs.code(), "FN").assignmentId()).isEqualTo(mine.assignmentId());
        assertThat(field(history(), "summary")).anySatisfy(s -> assertThat((String) s).startsWith("Swap: ").contains(" now as "));
    }

    @Test
    void onlyThePeopleInvolvedActAndRulesAreRespected() {
        // Drafts cannot be swapped.
        generate();
        Duty draftDuty = morningDutyInH2();
        String requester = loginAs(draftDuty.code());
        assertThat(as(requester, POST, "/api/v1/me/swap-requests", input(draftDuty, facultyIds.get("F1"), null)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        publishCurrentDraft();
        Duty mine = morningDutyInH2();
        String free = freeInMorning(chart());
        String stranger = List.of("F3", "F4", "F5", "F6", "F1").stream()
                .filter(c -> !c.equals(free) && !c.equals(mine.code())).findFirst().orElseThrow();
        String strangerToken = loginAs(stranger);

        // Someone else's duty is not yours to offer.
        assertThat(as(strangerToken, POST, "/api/v1/me/swap-requests", input(mine, facultyIds.get(free), null)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        Map<String, Object> swap = ok(as(requester, POST, "/api/v1/me/swap-requests", input(mine, facultyIds.get(free), null)));
        // Neither a stranger nor the requester can accept; the exam cell list shows it.
        assertThat(as(strangerToken, POST, swapPath(swap, "accept"), decision(swap)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(as(requester, POST, swapPath(swap, "accept"), decision(swap)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((List) data().send(GET, "/api/v1/swap-requests", college.accessToken(), null, List.class).getBody()).hasSize(1);
        // Plain faculty cannot list everyone's swaps.
        assertThat(as(strangerToken, GET, "/api/v1/swap-requests", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Another college sees nothing and cannot act.
        String outsider = loginAtAnotherCollege(List.of("HOD"));
        assertThat((List) data().send(GET, "/api/v1/swap-requests", outsider, null, List.class).getBody()).isEmpty();
        assertThat(as(outsider, POST, swapPath(swap, "reject"), decision(swap)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // A stale version is refused; the requester cancels with the right one.
        assertThat(as(requester, POST, swapPath(swap, "cancel"), Map.of("version", 99)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ok(as(requester, POST, swapPath(swap, "cancel"), decision(swap)))).containsEntry("status", "CANCELLED");
        assertThat(as(loginAs(free), POST, swapPath(swap, "accept"), decision(swap)).getBody()).containsEntry("code", "SWAP_NOT_PENDING");

        // With swaps switched off nobody can ask.
        swapRules(false, true, false);
        var off = as(requester, POST, "/api/v1/me/swap-requests", input(mine, facultyIds.get(free), null));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(off.getBody()).containsEntry("code", "SWAPS_DISABLED");
    }

    @Test
    void aDraftStartedBeforeASwapSaysItDoesNotHaveIt() {
        swapRules(true, false, false);
        generate();
        publishCurrentDraft();
        Duty mine = morningDutyInH2();
        String free = freeInMorning(chart());
        ok(send(POST, base + "/draft", null));
        assertThat(chart()).containsEntry("status", "DRAFT").containsEntry("swapsNotInDraft", 0);

        ok(as(loginAs(mine.code()), POST, "/api/v1/me/swap-requests", input(mine, facultyIds.get(free), null)));

        assertThat(chart()).containsEntry("status", "DRAFT").containsEntry("swapsNotInDraft", 1);
    }

    @Test
    void withoutAcceptanceTheSwapIsAppliedAtOnce() {
        swapRules(true, false, false);
        generate();
        publishCurrentDraft();
        Duty mine = morningDutyInH2();
        String free = freeInMorning(chart());

        Map<String, Object> swap = ok(as(loginAs(mine.code()), POST, "/api/v1/me/swap-requests", input(mine, facultyIds.get(free), null)));

        assertThat(swap).containsEntry("status", "APPROVED");
        assertThat(dutyOf(free, "FN")).isNotNull();
    }
}
