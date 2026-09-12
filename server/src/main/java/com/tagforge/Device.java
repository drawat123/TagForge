package com.tagforge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A device, mapped to the `device` table created by Flyway V1.
 *
 * Not a record: JPA needs a no-arg constructor and mutable fields, so entities
 * are ordinary classes. Flyway still owns the schema — ddl-auto is `validate`,
 * so Hibernate checks this mapping against the real table at startup and fails
 * fast if they disagree.
 */
@Entity
@Table(name = "device")
public class Device {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    /**
     * The table also defaults this to now(). Setting it here means the application
     * server's clock wins and the database default is never used.
     *
     * TODO: decide which clock you want. Letting the database set it means marking
     *       this insertable = false and re-reading the row to see the value.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. Not for application code. */
    protected Device() {
    }

    public Device(UUID id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
