-- One row per value, not per message.
--
-- The primary key is natural rather than surrogate: an MQTT QoS 1 redelivery, a
-- device re-publishing after its own reconnect, and an edge agent backfilling
-- after an outage all produce the same (device, key, ts) and collapse onto one
-- row instead of becoming phantom readings.
--
-- No foreign key to device: it would add a lookup to every insert on the hot
-- path, and telemetry outlives the device row it refers to.
--
-- Constraints are named rather than left to Postgres, since partitioning and the
-- eventual move of `key` to a dictionary id will both have to alter them.
CREATE TABLE telemetry (
    device_id  UUID        NOT NULL,
    key        TEXT        NOT NULL,
    ts         TIMESTAMPTZ NOT NULL,

    -- Type is per row, from whichever column is non-null. A PLC register
    -- reconfigured from int to float leaves history self-describing.
    bool_v     BOOLEAN,
    str_v      TEXT,
    long_v     BIGINT,
    dbl_v      DOUBLE PRECISION,

    CONSTRAINT telemetry_pk PRIMARY KEY (device_id, key, ts),

    -- Exactly one, not at most one: a row with no value would mean "the sensor
    -- was unreachable", and nothing in the system can produce that fact yet --
    -- only the edge agent will be able to tell a failed read from silence.
    -- Deliberately the strict version, because relaxing this later is a
    -- catalogue update while tightening it would scan every row.
    CONSTRAINT telemetry_exactly_one_value
        CHECK (num_nonnulls(bool_v, str_v, long_v, dbl_v) = 1)
);
