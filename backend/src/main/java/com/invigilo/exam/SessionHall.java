package com.invigilo.exam;

import com.invigilo.common.persistence.TenantScopedEntity;
import com.invigilo.hall.Hall;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One hall used in one session: the unit that needs invigilators. */
@Entity
@Table(name = "session_hall")
public class SessionHall extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private ExamSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hall_id", nullable = false, updatable = false)
    private Hall hall;

    /** Replaces the computed number of invigilators (a reason is mandatory). */
    @Column(name = "required_override")
    private Integer requiredOverride;

    @Column(name = "override_reason")
    private String overrideReason;

    @OneToMany(mappedBy = "sessionHall", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<HallSeating> seating = new LinkedHashSet<>();

    protected SessionHall() {}

    SessionHall(ExamSession session, Hall hall) {
        this.session = session;
        this.hall = hall;
    }

    void setOverride(Integer requiredOverride, String reason) {
        this.requiredOverride = requiredOverride;
        this.overrideReason = requiredOverride == null ? null : reason.trim();
    }

    /** Makes the seating exactly {@code counts} (exam -> students), touching only the differences. */
    void replaceSeating(Map<Exam, Integer> counts) {
        Map<UUID, Integer> byExamId = new HashMap<>();
        counts.forEach((exam, n) -> byExamId.put(exam.getId(), n));
        seating.removeIf(s -> !byExamId.containsKey(s.getExam().getId()));
        Set<UUID> present = new HashSet<>();
        for (HallSeating s : seating) {
            s.setStudentCount(byExamId.get(s.getExam().getId()));
            present.add(s.getExam().getId());
        }
        counts.forEach((exam, n) -> {
            if (!present.contains(exam.getId())) seating.add(new HallSeating(this, exam, n));
        });
    }

    public int totalStudents() {
        return seating.stream().mapToInt(HallSeating::getStudentCount).sum();
    }

    public ExamSession getSession() { return session; }
    public Hall getHall() { return hall; }
    public Integer getRequiredOverride() { return requiredOverride; }
    public String getOverrideReason() { return overrideReason; }
    public Set<HallSeating> getSeating() { return seating; }
}
