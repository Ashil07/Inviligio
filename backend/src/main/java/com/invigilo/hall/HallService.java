package com.invigilo.hall;

import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.common.persistence.Codes;
import com.invigilo.common.persistence.UpsertResult;
import com.invigilo.common.persistence.UsageCheck;
import com.invigilo.common.persistence.Versions;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HallService {

    public record HallView(UUID id, String code, String name, String building, int capacity, int minInvigilators,
                           boolean lab, boolean active, long version) {
        public static HallView of(Hall h) {
            return new HallView(h.getId(), h.getCode(), h.getName(), h.getBuilding(), h.getCapacity(),
                    h.getMinInvigilators(), h.isLab(), h.isActive(), h.getVersion());
        }
    }

    public record HallInput(String code, String name, String building, int capacity, int minInvigilators,
                            boolean lab, boolean active) {}

    private final HallRepository repository;
    /** Exam scheduling (M5) registers a check so halls used in sessions cannot be deleted. */
    private final ObjectProvider<UsageCheck<Hall>> usageChecks;
    private final AuditService audit;

    public HallService(HallRepository repository, ObjectProvider<UsageCheck<Hall>> usageChecks, AuditService audit) {
        this.repository = repository;
        this.usageChecks = usageChecks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<HallView> list(boolean includeInactive) {
        var rows = includeInactive ? repository.findAllByOrderByCodeAsc() : repository.findByActiveTrueOrderByCodeAsc();
        return rows.stream().map(HallView::of).toList();
    }

    @Transactional(readOnly = true)
    public HallView get(UUID id) {
        return HallView.of(find(id));
    }

    @Transactional
    public HallView create(HallInput input) {
        String code = Codes.normalize(input.code());
        if (repository.existsByCode(code)) {
            throw codeTaken(code);
        }
        Hall saved = repository.saveAndFlush(new Hall(input));
        HallView view = HallView.of(saved);
        audit.record("HALL_CREATED", "hall", saved.getId(), null, view, null);
        return view;
    }

    @Transactional
    public HallView update(UUID id, HallInput input, Long version) {
        Hall hall = find(id);
        Versions.requireUnchanged(hall, version);
        if (repository.existsByCodeAndIdNot(Codes.normalize(input.code()), id)) {
            throw codeTaken(Codes.normalize(input.code()));
        }
        HallView before = HallView.of(hall);
        hall.update(input);
        repository.flush();
        HallView after = HallView.of(hall);
        if (after.version() != before.version()) { // nothing changed: no audit noise
            audit.record("HALL_UPDATED", "hall", id, before, after, null);
        }
        return after;
    }

    /** Create or update by code, for CSV import. */
    @Transactional
    public UpsertResult upsert(HallInput input) {
        var existing = repository.findByCode(Codes.normalize(input.code()));
        if (existing.isEmpty()) {
            create(input);
            return UpsertResult.CREATED;
        }
        Hall h = existing.get();
        long before = h.getVersion();
        HallView after = update(h.getId(), input, h.getVersion());
        return after.version() == before ? UpsertResult.UNCHANGED : UpsertResult.UPDATED;
    }

    @Transactional
    public void delete(UUID id) {
        Hall hall = find(id);
        for (UsageCheck<Hall> check : usageChecks.orderedStream().toList()) {
            long count = check.countUsages(id);
            if (count > 0) {
                throw ApiException.conflict("HALL_IN_USE",
                        "This hall is used by " + count + " " + check.usedBy() + ". Deactivate it instead of deleting.");
            }
        }
        HallView before = HallView.of(hall);
        repository.delete(hall);
        audit.record("HALL_DELETED", "hall", id, before, null, null);
    }

    @Transactional(readOnly = true)
    public Hall find(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("HALL_NOT_FOUND", "Hall not found"));
    }

    private static ApiException codeTaken(String code) {
        return ApiException.conflict("HALL_CODE_TAKEN", "A hall with code " + code + " already exists");
    }
}
