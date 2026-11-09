# Allocation Engine — Design

The engine turns *(exam sessions, session halls, faculty, availability, prior workload, policy)*
into a duty chart that **never violates a hard constraint**, fills as many positions as possible,
spreads load fairly, and explains every decision and every gap. It is deterministic: identical
inputs always produce an identical chart.

## 1. Approach and why

| Option | Verdict |
|---|---|
| Random / round-robin | Rejected: unfair, not explainable, breaks constraints. |
| Plain greedy per position | Rejected alone: order-dependent, leaves fillable positions empty. |
| ILP / CP-SAT (OR-Tools) | Optimal, but native binaries per OS, hard to explain to admins, overkill at this scale. |
| Timefold Solver (ex-OptaPlanner) | Powerful, but heavy learning curve; reproducibility and per-decision explanations need extra work. Keep as an escape hatch (§9). |
| **Session-by-session optimal matching + repair + improvement** | **Chosen.** Exact within each session, deterministic, fast, and each step is easy to explain. |

Key observation: the hardest constraint (*no faculty in two overlapping duties*) is local to a
session. Within one session, filling halls is a **bipartite assignment problem** (positions ×
eligible faculty, each faculty at most once), which a min-cost assignment solves *optimally*:
if any assignment can fill every position in the session, the solver finds it. Constraints that
span sessions (daily maximum, consecutive sessions, rest period, total maximum) are enforced as
eligibility given earlier decisions, with look-ahead and repair to soften greedy effects.

## 2. Structure

```
allocation/
  engine/                 PURE JAVA — no Spring, no JPA, no clock, no randomness
    model/                immutable records: AllocationProblem, Session, Position, Candidate,
                          PolicySnapshot, AllocationResult, Assignment, Unfilled, Explanation
    constraint/           HardConstraint & SoftConstraint implementations (one class per rule)
    solve/                AssignmentSolver (Hungarian / min-cost), SessionPlanner, RepairPass, ImprovementPass
    validate/             ChartValidator (whole-chart re-check with the same constraints)
  AllocationService       Spring: loads a snapshot in one read-only transaction, runs the engine,
                          persists allocation_run + DRAFT duty_chart + unfilled_position
```

The engine never sees entities. `AllocationService` builds the `AllocationProblem` snapshot, so the
engine can be unit-tested with hand-built problems and the result is reproducible from its input
(the run stores an input hash).

## 3. Policy catalogue (`PolicySettings`, versioned per college)

Implemented in M2 as `policy/PolicySettings` (backend record, Bean Validation) and
`features/policy/schema.ts` (Zod mirror). Stored as JSON in `college_policy.settings`, one immutable
row per version. Limits with a **mode** are `HARD` (filter), `SOFT` (penalty) or `OFF`.

**Always hard, not configurable:** no overlapping duties; approved leave/unavailability respected
(explicit, audited admin override per session only, if `adminCanOverrideUnavailability`); inactive
faculty excluded; faculty-specific restrictions (`faculty_restriction`, M4).

| JSON path | Default | Range | Applied as |
|---|---|---|---|
| `faculty.allowOwnSubject` | `false` | bool | false = HARD: not in a hall seating a subject one teaches |
| `faculty.ownDepartmentExams` | `ALLOW` | `ALLOW`/`AVOID`/`FORBID` | FORBID = HARD, AVOID = SOFT |
| `faculty.visitingFacultyEligible` | `true` | bool | HARD filter on employment type |
| `faculty.seniorsGetFewerDuties` | `false` | bool | SOFT: seniority lowers the fair-share target (Q4) |
| `faculty.preferSeniorsAsChief` | `false` | bool | SOFT: seniors ranked first for CHIEF positions (Q4) |
| `faculty.chiefInvigilator` | `NONE` | `NONE`/`PER_HALL` | creates CHIEF positions, chief-eligible faculty only |
| `faculty.chiefCountsTowardsStaffing` | `true` | bool | position count |
| `workload.maxDutiesPerDay` | 2 | 1-6 | HARD |
| `workload.consecutiveSessions` | HARD, 2 | mode + 1-20 | per mode |
| `workload.minRestMinutes` | 0 | 0-720 | HARD |
| `workload.consecutiveDays` | OFF, 3 | mode + 1-20 | per mode |
| `workload.maxDutiesPerPeriod` | null | null or 1-200 | HARD when set |
| `workload.equalDistributionWeight` | 8 | 0-10 | SOFT weight of the quadratic load cost |
| `workload.historicalWorkloadPercent` | 30 | 0-100 | share of previous periods' load counted |
| `workload.avoidRepeatHallWeight` | 2 | 0-10 | SOFT weight |
| `staffing.studentsPerInvigilator` | 30 | 1-500 | position count |
| `staffing.practicalStudentsPerInvigilator` | null | null or 1-500 | position count for PRACTICAL exams (null = theory ratio) |
| `staffing.minInvigilatorsPerHall` | 1 | 1-20 | position count (a hall's own minimum, M3, may be higher) |
| `availability.allowFullDayUnavailability` | `true` | bool | workflow |
| `availability.allowSessionUnavailability` | `true` | bool | workflow |
| `availability.leaveRequiresHodApproval` | `false` | bool | workflow; false = requests take effect immediately |
| `availability.adminCanOverrideUnavailability` | `true` | bool | workflow |
| `swaps.swapsEnabled` | `true` | bool | workflow |
| `swaps.recipientMustAccept` | `true` | bool | workflow |
| `swaps.hodApprovalRequired` | `false` | bool | workflow |
| `swaps.adminCanReassignDirectly` | `true` | bool | workflow |

All fields are required in API requests (wrapper types + `@NotNull`), so a client can never change
behaviour by omitting a rule. **Adding a field** = add it to both schemas, choose a default, and
write a Flyway migration that backfills it into every existing `college_policy.settings` row.

**Required positions for a session hall** =
`max(staffing.minInvigilatorsPerHall, hall.min_invigilators, ceil(total_students / ratio))` where ratio is the practical ratio if any
seated exam is PRACTICAL, plus one CHIEF position if `chiefInvigilator = PER_HALL` and
`chiefCountsTowardsStaffing = false` (when it is true, one of the required positions is the chief).
Admin may override per session hall with a reason. A hall with no students needs nobody.
Implemented in `exam/Staffing.java`; the engine must call it rather than re-implement it.

## 4. Algorithm

```
1. Build snapshot; compute positions per session hall (CHIEF + INVIGILATOR).
2. Group sessions into overlap groups: sessions on the same date whose time ranges overlap
   (connected components). Usually one group per session (FN/AN do not overlap).
   Conservative: a faculty takes at most one position per group.
3. Order groups chronologically (date, start time, session id).
4. For each group:
     a. For each position, candidates = faculty passing ALL hard constraints given assignments so far.
     b. Cost(faculty, position) = Σ weight_i × penalty_i      (integer, see §6)
          + scarcity term: penalise using faculty who are the few eligible options for
            upcoming positions (look-ahead over the next K groups, K = 3)
     c. Solve min-cost maximum-cardinality assignment (Hungarian on a padded matrix;
        unfillable positions get a dummy column with a very large cost).
        CHIEF positions only have edges to chief-eligible faculty.
     d. Commit assignments; record unfilled positions with their elimination funnel.
5. Repair pass (bounded): for each unfilled position P, find faculty F blocked *only* by a
   cross-session rule because of one existing assignment A. If A can be given to another eligible
   faculty G (without new violations), move A → G and assign F → P. Depth 1–2, deterministic order.
6. Improvement pass (bounded): pairwise exchanges between faculty within the same group that
   reduce total cost without breaking any hard constraint; first-improvement, fixed iteration cap.
7. Final validation: ChartValidator re-checks the whole chart. Any hard violation here is a BUG:
   the run fails loudly and nothing is saved.
8. Persist as DRAFT with stats, explanations and unfilled positions.
```

Determinism: no randomness, no wall clock, all collections sorted by stable keys
(date, start time, hall code, faculty employee code, then id), integer costs, ties broken by the
same sort keys. Test: run twice on the same snapshot → byte-identical result.

Complexity: Hungarian is O(n³) per group with n ≈ positions + candidates (≈ 300) → milliseconds.
A full period (60 sessions × 25 halls × 2 positions, 300 faculty) must finish in < 5 s.

## 5. One rule set, four consumers

The same `HardConstraint`/`SoftConstraint` classes drive:

| Consumer | Use |
|---|---|
| Generator | candidate filtering and costs |
| Manual editor (`POST …/assignments/validate`) | **blocking errors** = hard violations, **warnings** = soft penalties above threshold, plus top-N alternative candidates |
| Swap workflow | both sides re-validated inside the transaction before commit |
| Emergency substitute | hard filter + ranking by cost, with per-candidate reasons |

Each constraint returns a structured result (`code`, `severity`, message params), e.g.
`OVERLAPPING_DUTY {otherHall: "H-204", session: "12 Nov FN"}`. Messages are rendered in the UI from
the code, so the backend stays the source of truth.

## 6. Fairness and soft costs

- Marginal load cost is **quadratic**: giving a faculty with effective load L another duty costs
  `(2L + 1) × w_equal`, where `L = currentLoad / duty_weight + historicalWeight × normalizedHistory`.
  Quadratic marginal cost drives the solver toward equal loads without a separate balancing phase.
- Consecutive-session, same-day, own-department (AVOID), repeat-hall and seniority penalties are
  linear with their configured weights.
- Weights are integers (scaled ×100) to keep the solver exact and deterministic.

Run stats saved with each chart: min / max / mean / std-dev of duties per faculty (overall and per
department), filled / required positions, count of each soft-penalty type.

## 7. Explanations

**Per assignment** (`duty_assignment.explanation`): rank among candidates, number of candidates,
the faculty's load at that point, the soft penalties incurred.

**Per unfilled position** (`unfilled_position.reason`): an elimination funnel, e.g.

```
Hall H-101, 12 Nov FN, INVIGILATOR #2
  148 active faculty
  −31 on approved leave/unavailable
  −52 already assigned in this session
  −40 teach a subject seated in this hall (allowOwnSubject = false)
  −25 reached maxDutiesPerDay = 2
  = 0 eligible
Suggestions:
  • 25 faculty are blocked only by maxDutiesPerDay; raising it to 3 would make them eligible.
  • Reduce required invigilators for H-101 (52 students, ratio 30 → 2 required).
  • Move part of the seating to a hall with spare invigilators.
```

Suggestions come from candidates blocked by exactly one *configurable* rule, so they are concrete
and actionable. The engine never relaxes a rule itself.

## 8. Edge cases to cover in tests

- Faculty with whole-day leave vs session-level unavailability; pending (not approved) leave is **not** a block but is shown as a warning.
- Hall seating two subjects where the faculty teaches one of them.
- Sessions with overlapping but non-identical times (practical 09:00–13:00 vs theory 10:00–13:00).
- No chief-eligible faculty available.
- Fewer eligible faculty than positions in one session (partial fill, correct funnel).
- Re-running generation on a period with an existing DRAFT (replace draft, keep published untouched).
- Faculty deactivated after publish (published chart unchanged; shows a warning).
- Zero students in a session hall (no positions).
- Policy changed between generation and publish (publish uses the draft's policy version; UI warns that a newer policy exists).

## 8a. As implemented (M7)

Code: `backend/src/main/java/com/invigilo/allocation/engine/` (pure) and `allocation/AllocationService`.

- **Positions** come from `exam.Staffing` per session hall (halls needing 0 are skipped).
- **Overlap groups** are built chronologically; a faculty member takes at most one position per
  group (conservative for chains of partly overlapping sessions).
- **Hard rules, in order** (`Reason`): unavailable (approved leave), visiting/guest not eligible,
  personal hall exclusion, personal department exclusion, own subject, own department (FORBID),
  chief eligibility (incl. NOT_CHIEF), busy at that time, max per day (policy and personal, the
  lower wins), minimum rest, consecutive sessions (HARD), consecutive days (HARD), max per period
  (policy and personal). "Visiting" means employment type VISITING or GUEST.
- **Costs** (integers): load `(equalDistributionWeight×100 + 10) × (2L + 1)` with
  `L = (duties + historical × historicalWorkloadPercent/100) / effectiveWeight`;
  `effectiveWeight = dutyWeight × (seniorsGetFewerDuties ? 1 − 0.4 × seniority : 1)` where the most
  senior (earliest joining date) has seniority 1 and the most junior 0; +400 for a SOFT
  consecutive-session or consecutive-day excess; +600 own department (AVOID); +30 ×
  avoidRepeatHallWeight per earlier duty in the same hall; chief positions +300 × juniority when
  `preferSeniorsAsChief`; look-ahead scarcity +150 × Σ(1/eligible) over the next 3 groups.
- **Solver**: Hungarian per group with one "unfilled" column per position (cost 1e9) and
  forbidden pairs at 1e15, so the number of filled positions is maximised first, then cost.
- **Repair**: direct fill if someone became free; otherwise free a faculty member blocked only by
  cross-session limits by moving one of their other duties to the cheapest legal replacement.
  The pairwise *improvement pass* from §4 is **not implemented**: the quadratic load cost already
  gives near-equal shares (max − min ≤ 2 on the 300-faculty benchmark).
- **Validation**: every assignment is re-checked against all others; a violation throws and
  nothing is saved (`ALLOCATION_FAILED`, 500).
- **Explanations**: per assignment the number of eligible faculty, duties before it and accepted
  penalties; per unfilled hall/role the first-failing reason counts and suggestions for rules
  that are the *only* obstacle for some faculty.
- **Measured**: 300 faculty × 60 sessions × 25 halls × 2 = 3000 positions in ≈0.5 s (unit test
  `handlesACollegeSizedPeriodQuicklyAndFairly`, limit 5 s). Determinism is tested by shuffling
  input order.
- **Storage**: each generation stores an `allocation_run` and replaces the period's DRAFT
  `duty_chart` (version numbers keep increasing) with `duty_assignment` and `unfilled_position`
  rows. The chart records the policy version and an input fingerprint (`ProblemFingerprint`); the
  review shows `policyChanged` / `inputsChanged` instead of silently re-allocating.

## 9. Escape hatch

If real data shows the greedy/repair approach leaves too many gaps, the `engine` boundary
(`AllocationProblem → AllocationResult`) allows swapping the solver for Timefold or CP-SAT without
touching the services, validators, explanations or UI.
