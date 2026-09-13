package com.tagforge.telemetry.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * JDBC, not JPA: an entity manager tracking every reading is the wrong tool at
 * telemetry rates.
 *
 * One statement per value — the naive path, kept until it is measured.
 */
@Repository
public class TelemetryRepository {

    /**
     * DO UPDATE rather than DO NOTHING: a duplicate carries identical values so
     * either works, but a corrected backfill should win over the original.
     */
    private static final String UPSERT = """
            INSERT INTO telemetry (device_id, key, ts, bool_v, str_v, long_v, dbl_v)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (device_id, key, ts) DO UPDATE SET
                bool_v = EXCLUDED.bool_v,
                str_v  = EXCLUDED.str_v,
                long_v = EXCLUDED.long_v,
                dbl_v  = EXCLUDED.dbl_v
            """;

    private final JdbcClient jdbc;

    public TelemetryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The Postgres driver has no binding for Instant — an Instant carries no
     * offset, and timestamptz needs one — so it is converted here rather than
     * leaking the driver's limitation into the service.
     */
    public void save(UUID deviceId, String key, Instant ts,
                     Boolean boolV, String strV, Long longV, Double dblV) {
        jdbc.sql(UPSERT)
                .params(deviceId, key, OffsetDateTime.ofInstant(ts, ZoneOffset.UTC),
                        boolV, strV, longV, dblV)
                .update();
    }
}
