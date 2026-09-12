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
}
