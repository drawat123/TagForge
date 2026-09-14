package com.tagforge.telemetry.dto;

import java.time.Instant;

/** One reading. The typed columns are collapsed into a single value here. */
public record TelemetryPoint(Instant ts, Object value) {
}
