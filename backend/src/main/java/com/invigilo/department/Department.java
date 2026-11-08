package com.invigilo.department;

import com.invigilo.common.persistence.Codes;
import com.invigilo.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "department")
public class Department extends TenantScopedEntity {

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    protected Department() {}

    Department(String code, String name, boolean active) {
        update(code, name, active);
    }

    void update(String code, String name, boolean active) {
        this.code = Codes.normalize(code);
        this.name = name.trim();
        this.active = active;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}
