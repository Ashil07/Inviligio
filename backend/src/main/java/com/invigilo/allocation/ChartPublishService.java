package com.invigilo.allocation;

import com.invigilo.allocation.AllocationService.ChartView;
import com.invigilo.allocation.AllocationService.IssueView;
import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.exam.ExamPeriod;
import com.invigilo.exam.ExamPeriodService;
import com.invigilo.tenant.CurrentUser;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishing a duty chart and preparing its next version (M10).
 *
 * <p>Publishing freezes the DRAFT as an immutable version, supersedes the previously published one
 * (kept as history) and freezes the period's timetable. It is refused while any duty breaks a
 * hard rule. Afterwards a new draft can be started from the published chart (or generated), edited,
 * and published to replace it; the published version stays in force until then.
 */
@Service
public class ChartPublishService {

    private final AllocationService allocation;
    private final ExamPeriodService periods;
    private final DutyChartRepository charts;
    private final DutyAssignmentRepository assignments;
    private final UnfilledPositionRepository unfilled;
    private final AuditService audit;
    private final Clock clock;

    public ChartPublishService(AllocationService allocation, ExamPeriodService periods, DutyChartRepository charts,
                               DutyAssignmentRepository assignments, UnfilledPositionRepository unfilled, AuditService audit,
                               Clock clock) {
        this.allocation = allocation;
        this.periods = periods;
        this.charts = charts;
        this.assignments = assignments;
        this.unfilled = unfilled;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Publishes the period's draft. {@code version} is the draft's version when it was reviewed; any
     * edit since makes it stale, so what is published is exactly what was reviewed.
     */
    @Transactional
    public ChartView publish(UUID periodId, Long version, CurrentUser user) {
        ExamPeriod period = requireOpen(periodId);
        DutyChart draft = charts.lockDraft(periodId)
                .orElseThrow(() -> ApiException.conflict("NO_DRAFT", "There is no draft chart to publish. Generate one first."));
        if (version == null) throw ApiException.unprocessable("VERSION_REQUIRED", "The version of the reviewed draft is required");
        if (!Objects.equals(version, draft.getVersion())) {
            throw ApiException.conflict("STALE_UPDATE",
                    "The draft was changed after you reviewed it. Review it again, then publish.");
        }
        List<DutyAssignment> duties = assignments.findForChart(draft.getId());
        List<IssueView> issues = AllocationService.issues(duties, allocation.buildForChart(period, draft));
        if (!issues.isEmpty()) {
            throw ApiException.conflict("CHART_HAS_ERRORS", issues.size() + (issues.size() == 1 ? " duty breaks" : " duties break")
                    + " a strict rule. Fix " + (issues.size() == 1 ? "it" : "them") + " before publishing.");
        }

        Optional<DutyChart> previous = charts.findByPeriodIdAndStatus(periodId, DutyChart.Status.PUBLISHED);
        previous.ifPresent(old -> {
            old.supersede();
            charts.flush(); // at most one PUBLISHED per period (partial unique index)
        });
        draft.publish(user.userId(), clock.instant());
        charts.flush();
        periods.markPublished(periodId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("chartVersion", draft.getChartVersion());
        after.put("supersededVersion", previous.map(DutyChart::getChartVersion).orElse(null));
        after.put("positions", duties.size() + unfilled.missingIn(draft.getId()));
        after.put("filled", duties.size());
        audit.record("DUTY_CHART_PUBLISHED", ChartEditService.ENTITY, draft.getId(), null, after, null);
        return allocation.view(draft, period);
    }

    /** Starts a new draft as a copy of the published chart, to change it without touching what faculty see. */
    @Transactional
    public ChartView newDraftFromPublished(UUID periodId, CurrentUser user) {
        ExamPeriod period = requireOpen(periodId);
        if (charts.findByPeriodIdAndStatus(periodId, DutyChart.Status.DRAFT).isPresent()) {
            throw ApiException.conflict("DRAFT_EXISTS", "A draft already exists for this period. Edit or publish that one.");
        }
        DutyChart published = charts.findByPeriodIdAndStatus(periodId, DutyChart.Status.PUBLISHED)
                .orElseThrow(() -> ApiException.conflict("NO_PUBLISHED_CHART", "Nothing has been published for this period yet"));

        DutyChart draft = charts.saveAndFlush(DutyChart.draftFrom(published, charts.maxVersion(periodId) + 1, user.userId()));
        assignments.saveAll(assignments.findForChart(published.getId()).stream()
                .map(d -> DutyAssignment.copyOf(d, draft.getId())).toList());
        unfilled.saveAll(unfilled.findForChart(published.getId()).stream()
                .map(u -> UnfilledPosition.copyOf(u, draft.getId())).toList());
        assignments.flush();
        audit.record("DUTY_CHART_DRAFTED", ChartEditService.ENTITY, draft.getId(), null,
                Map.of("chartVersion", draft.getChartVersion(), "fromVersion", published.getChartVersion()), null);
        return allocation.view(draft, period);
    }

    private ExamPeriod requireOpen(UUID periodId) {
        ExamPeriod period = periods.findPeriod(periodId);
        if (!period.allowsChartChanges()) {
            throw ApiException.conflict("PERIOD_LOCKED", "This examination period is " + period.getStatus()
                    + "; its duty chart can no longer change");
        }
        return period;
    }
}
