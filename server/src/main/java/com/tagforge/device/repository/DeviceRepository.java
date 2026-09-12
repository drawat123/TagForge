package com.tagforge.device.repository;

import com.tagforge.device.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Telemetry will not use JPA — an entity manager is the wrong tool for bulk inserts. */
public interface DeviceRepository extends JpaRepository<Device, UUID> {
}
