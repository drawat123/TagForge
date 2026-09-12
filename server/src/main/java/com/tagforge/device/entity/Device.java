package com.tagforge.device.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A device, mapped to the `device` table. Flyway owns the schema; ddl-auto is
 * `validate`, so a mapping that drifts from the table fails at startup.
 *
 * Only {@code @Getter} — {@code @Data} would generate equals/hashCode over every
 * field, which on an entity can trigger a lazy load.
 */
@Entity
@Table(name = "device")
@Getter
public class Device {

    /**
     * Never null, so Spring Data's save() cannot detect a new entity and calls
     * merge() — a SELECT before every INSERT. Irrelevant at device rates.
     */
    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private String name;

    /** Set from the application clock, so the column default never fires. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected Device() {
    }

    public Device(String name, Instant createdAt) {
        this.name = name;
        this.createdAt = createdAt;
    }

    /**
     * Sound only because the id is assigned at construction and never changes.
     * Uses getId() rather than the field: a lazy proxy's fields are empty until
     * the getter loads them.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Device other)) {
            return false;
        }
        return Objects.equals(id, other.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
