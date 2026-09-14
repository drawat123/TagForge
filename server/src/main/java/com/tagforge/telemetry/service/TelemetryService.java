package com.tagforge.telemetry.service;

import com.tagforge.device.service.DeviceService;
import com.tagforge.telemetry.dto.KeyedPoint;
import com.tagforge.telemetry.dto.TelemetryPoint;
import com.tagforge.telemetry.dto.TelemetryUpload;
import com.tagforge.telemetry.repository.TelemetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** Most recent reading per key. One row per key regardless of history size. */
    public Map<String, TelemetryPoint> latest(UUID deviceId, String[] keys) {
        deviceService.findById(deviceId);

        Map<String, TelemetryPoint> result = new LinkedHashMap<>();
        for (KeyedPoint point : telemetryRepository.findLatest(deviceId, keys)) {
            result.put(point.key(), new TelemetryPoint(point.ts(), point.value()));
        }
        return result;
    }

    /**
     * Readings in a half-open range, grouped by key so a chart gets one series per
     * key and the key name is not repeated on every point.
     */
    public Map<String, List<TelemetryPoint>> range(UUID deviceId, String[] keys,
                                                   Instant from, Instant to, int limit) {
        deviceService.findById(deviceId);

        return telemetryRepository.findRange(deviceId, keys, from, to, limit).stream()
                .collect(Collectors.groupingBy(
                        KeyedPoint::key,
                        LinkedHashMap::new,
                        Collectors.mapping(p -> new TelemetryPoint(p.ts(), p.value()), Collectors.toList())));
    }
}
