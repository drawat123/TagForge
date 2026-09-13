package com.tagforge.telemetry.service;

import com.tagforge.device.service.DeviceService;
import com.tagforge.telemetry.dto.TelemetryUpload;
import com.tagforge.telemetry.repository.TelemetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Stores a telemetry message.
 *
 * No @Transactional: at telemetry rates a transaction per message is overhead,
 * and each value is independently idempotent, so a partial write is not a
 * corrupt state — it is a message that will be retried and converge.
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final TelemetryRepository telemetryRepository;
    private final DeviceService deviceService;

    public TelemetryService(TelemetryRepository telemetryRepository, DeviceService deviceService) {
        this.telemetryRepository = telemetryRepository;
        this.deviceService = deviceService;
    }

    /** Throws DeviceNotFoundException for an unknown device — the same exception the API uses. */
    public void store(UUID deviceId, TelemetryUpload upload) {
        deviceService.findById(deviceId);

        Instant ts = upload.ts() != null ? Instant.ofEpochMilli(upload.ts()) : Instant.now();

        for (Map.Entry<String, Object> entry : upload.values().entrySet()) {
            store(deviceId, entry.getKey(), ts, entry.getValue());
        }
    }

    /** Which column a value lands in is decided per reading, from its JSON type. */
    private void store(UUID deviceId, String key, Instant ts, Object value) {
        switch (value) {
            case null -> log.debug("Ignoring null value for {} on {}", key, deviceId);
            case Boolean b -> telemetryRepository.save(deviceId, key, ts, b, null, null, null);
            case String s -> telemetryRepository.save(deviceId, key, ts, null, s, null, null);
            case Integer i -> telemetryRepository.save(deviceId, key, ts, null, null, i.longValue(), null);
            case Long l -> telemetryRepository.save(deviceId, key, ts, null, null, l, null);
            case Number n -> telemetryRepository.save(deviceId, key, ts, null, null, null, n.doubleValue());
            default -> log.warn("Unsupported value type {} for {} on {}",
                    value.getClass().getSimpleName(), key, deviceId);
        }
    }
}
