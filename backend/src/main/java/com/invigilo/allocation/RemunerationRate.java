package com.invigilo.allocation;

import com.invigilo.allocation.engine.AllocationResult.Role;
import com.invigilo.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Pay for one duty in a role, from {@code effectiveFrom} until the next rate of the same role (M17). */
@Entity
@Table(name = "remuneration_rate")
public class RemunerationRate extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Role role;

    @Column(nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected RemunerationRate() {}

    RemunerationRate(Role role, BigDecimal amount, String currency, LocalDate effectiveFrom, UUID createdBy) {
        this.role = role;
        this.amount = amount;
        this.currency = currency;
        this.effectiveFrom = effectiveFrom;
        this.createdBy = createdBy;
    }

    public Role getRole() { return role; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public UUID getCreatedBy() { return createdBy; }
}
