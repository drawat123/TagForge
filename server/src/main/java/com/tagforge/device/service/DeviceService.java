package com.tagforge.device.service;

import com.tagforge.device.entity.Device;
import com.tagforge.device.exception.DeviceNotFoundException;
import com.tagforge.device.repository.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Device use cases, and the transaction boundary.
 *
 * {@code @Transactional} works through a proxy: a call from one method of this
 * class to another bypasses it and the annotation is ignored.
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;

    public DeviceService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Transactional
    public Device create(String name) {
        return deviceRepository.save(new Device(name, Instant.now()));
    }

    @Transactional(readOnly = true)
    public Device findById(UUID id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException(id));
    }

    /**
     * Deletes the device row only. Its telemetry is left in place: there is no
     * foreign key, nothing joins to device, and ingestion rejects unknown ids so
     * no new readings can appear. Cascading would mean deleting up to a week of
     * readings synchronously -- tens of millions of rows at target load -- while
     * holding locks on the hottest table in the system. Retention removes them
     * instead.
     */
    @Transactional
    public void delete(UUID id) {
        if (!deviceRepository.existsById(id)) {
            throw new DeviceNotFoundException(id);
        }
        deviceRepository.deleteById(id);
    }
}
