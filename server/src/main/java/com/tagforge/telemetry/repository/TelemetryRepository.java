package com.tagforge.telemetry.repository;

import com.tagforge.telemetry.dto.KeyedPoint;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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

    /**
     * DISTINCT ON is Postgres-specific and reads one row per key: with the primary
     * key ordered (device_id, key, ts) the index supplies these directly, so the
     * cost is per key rather than per reading.
     */
    private static final String LATEST = """
            SELECT DISTINCT ON (key) key, ts, bool_v, str_v, long_v, dbl_v
            FROM telemetry
            WHERE device_id = ? AND key = ANY (?)
            ORDER BY key, ts DESC
            """;

    /**
     * The limit is shared across all requested keys, not applied per key: asking
     * for three keys with limit 100 returns the 100 most recent readings overall,
     * which may be skewed toward whichever key publishes fastest.
     *
     * key is in the ORDER BY only as a tiebreak. Several keys share a timestamp in
     * every message, so ts alone is not a total order and which rows survive the
     * LIMIT would otherwise vary between runs.
     */
    private static final String RANGE = """
            SELECT key, ts, bool_v, str_v, long_v, dbl_v
            FROM telemetry
            WHERE device_id = ? AND key = ANY (?) AND ts >= ? AND ts < ?
            ORDER BY ts DESC, key
            LIMIT ?
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

    public List<KeyedPoint> findLatest(UUID deviceId, String[] keys) {
        return jdbc.sql(LATEST)
                .params(deviceId, keys)
                .query(TelemetryRepository::toPoint)
                .list();
    }

    public List<KeyedPoint> findRange(UUID deviceId, String[] keys,
                                      Instant from, Instant to, int limit) {
        return jdbc.sql(RANGE)
                .params(deviceId, keys,
                        OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(to, ZoneOffset.UTC),
                        limit)
                .query(TelemetryRepository::toPoint)
                .list();
    }

    /**
     * Collapses the four typed columns back to one value. getObject returns null
     * rather than a zero default, so the first non-null column is the reading's
     * type -- the same rule the check constraint enforces on write.
     */
    private static KeyedPoint toPoint(ResultSet rs, int rowNum) throws SQLException {
        Object value = rs.getObject("bool_v");
        if (value == null) {
            value = rs.getObject("str_v");
        }
        if (value == null) {
            value = rs.getObject("long_v");
        }
        if (value == null) {
            value = rs.getObject("dbl_v");
        }
        return new KeyedPoint(rs.getString("key"),
                rs.getObject("ts", OffsetDateTime.class).toInstant(),
                value);
    }
}
