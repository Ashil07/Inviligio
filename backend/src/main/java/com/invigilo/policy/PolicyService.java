package com.invigilo.policy;

import com.invigilo.audit.AuditService;
import com.invigilo.common.error.ApiException;
import com.invigilo.identity.AppUser;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Versioned examination policy of the current college. All reads/writes are tenant-filtered. */
@Service
public class PolicyService {

    /** A policy version as shown to clients. {@code version == 0} means "nothing saved yet, these are defaults". */
    public record PolicyView(int version, boolean saved, PolicySettings settings, Instant createdAt, String createdByName) {

        static PolicyView of(CollegePolicy p) {
            return new PolicyView(p.getPolicyVersion(), true, p.getSettings(), p.getCreatedAt(),
                    p.getCreatedBy() == null ? null : p.getCreatedBy().getFullName());
        }

        static PolicyView defaults() {
            return new PolicyView(0, false, PolicySettings.defaults(), null, null);
        }
    }

    public record PolicyVersionSummary(int version, Instant createdAt, String createdByName) {}

    private final CollegePolicyRepository repository;
    private final AuditService audit;
    private final EntityManager entityManager;

    public PolicyService(CollegePolicyRepository repository, AuditService audit, EntityManager entityManager) {
        this.repository = repository;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PolicyView current() {
        return repository.findFirstByOrderByPolicyVersionDesc().map(PolicyView::of).orElseGet(PolicyView::defaults);
    }

    /** The settings the allocation engine must use now, with the version id to record against a run. */
    @Transactional(readOnly = true)
    public Optional<CollegePolicy> latest() {
        return repository.findFirstByOrderByPolicyVersionDesc();
    }

    /** A specific saved version, e.g. the one a duty chart was generated with. */
    @Transactional(readOnly = true)
    public Optional<CollegePolicy> find(UUID policyId) {
        return policyId == null ? Optional.empty() : repository.findById(policyId);
    }

    @Transactional(readOnly = true)
    public boolean hasSavedPolicy() {
        return repository.findFirstByOrderByPolicyVersionDesc().isPresent();
    }

    /**
     * Saves {@code settings} as a new version. {@code basedOnVersion} is the version the editor
     * started from; if someone saved in between, the change is refused instead of silently
     * overwriting theirs. Saving identical settings creates no new version.
     */
    @Transactional
    public PolicyView save(int basedOnVersion, PolicySettings settings, UUID actorUserId) {
        Optional<CollegePolicy> current = repository.findFirstByOrderByPolicyVersionDesc();
        int currentVersion = current.map(CollegePolicy::getPolicyVersion).orElse(0);
        if (basedOnVersion != currentVersion) {
            throw ApiException.conflict("POLICY_CHANGED",
                    "The policy was changed by someone else (now version " + currentVersion
                            + "). Reload it and apply your changes again.");
        }
        if (current.isPresent() && current.get().getSettings().equals(settings)) {
            return PolicyView.of(current.get());
        }
        AppUser actor = actorUserId == null ? null : entityManager.getReference(AppUser.class, actorUserId);
        CollegePolicy saved = repository.save(new CollegePolicy(currentVersion + 1, settings, actor));
        audit.record("POLICY_VERSION_CREATED", "college_policy", saved.getId(),
                current.map(CollegePolicy::getSettings).orElse(null), settings, null);
        // created_at is set on flush; flush so the returned view carries it.
        repository.flush();
        return PolicyView.of(saved);
    }

    @Transactional(readOnly = true)
    public List<PolicyVersionSummary> versions() {
        return repository.findAllByOrderByPolicyVersionDesc().stream()
                .map(p -> new PolicyVersionSummary(p.getPolicyVersion(), p.getCreatedAt(),
                        p.getCreatedBy() == null ? null : p.getCreatedBy().getFullName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicyView version(int version) {
        return repository.findByPolicyVersion(version)
                .map(PolicyView::of)
                .orElseThrow(() -> ApiException.notFound("POLICY_VERSION_NOT_FOUND", "No such policy version"));
    }
}
