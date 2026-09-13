package com.tagforge.telemetry.dto;

import java.util.Map;

/**
 * A telemetry message as published by a device.
 *
 * @param ts     device clock, epoch millis — the moment the sensor was read, not
 *               the moment the server saw it, so backfilled data keeps its meaning.
 * @param values tag name to value; type is whatever JSON carried.
 */
public record TelemetryUpload(Long ts, Map<String, Object> values) {
}
