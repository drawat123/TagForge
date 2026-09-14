package com.tagforge.telemetry.dto;

import java.time.Instant;

/** A row as read from the table, before grouping by key. */
public record KeyedPoint(String key, Instant ts, Object value) {
}
