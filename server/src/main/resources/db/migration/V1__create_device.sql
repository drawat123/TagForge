CREATE TABLE device (
    id          UUID        NOT NULL,
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT device_pk PRIMARY KEY (id)
);
