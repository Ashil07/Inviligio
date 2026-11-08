package com.invigilo.hall;

import com.invigilo.common.persistence.Codes;
import com.invigilo.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** An examination hall or lab. Capacity and minimum invigilators feed staffing requirements. */
@Entity
@Table(name = "hall")
public class Hall extends TenantScopedEntity {

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String building;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "min_invigilators", nullable = false)
    private int minInvigilators = 1;

    @Column(name = "is_lab", nullable = false)
    private boolean lab;

    @Column(nullable = false)
    private boolean active = true;

    protected Hall() {}

    Hall(HallService.HallInput input) {
        update(input);
    }

    void update(HallService.HallInput input) {
        this.code = Codes.normalize(input.code());
        this.name = input.name().trim();
        this.building = input.building() == null || input.building().isBlank() ? null : input.building().trim();
        this.capacity = input.capacity();
        this.minInvigilators = input.minInvigilators();
        this.lab = input.lab();
        this.active = input.active();
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getBuilding() { return building; }
    public int getCapacity() { return capacity; }
    public int getMinInvigilators() { return minInvigilators; }
    public boolean isLab() { return lab; }
    public boolean isActive() { return active; }
}
