package com.tagforge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data generates the implementation at startup — save, findById, findAll,
 * deleteById and more come from JpaRepository with no code.
 *
 * This is the administrative half of the model, where JPA earns its place. The
 * telemetry write path will not use it. See docs/adr/0001-data-access.md.
 */
public interface DeviceRepository extends JpaRepository<Device, UUID> {
}
