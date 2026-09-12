package com.tagforge.device.exception;

import lombok.Getter;

import java.util.UUID;

/** Carries no HTTP status, so the service stays usable from MQTT ingestion. */
@Getter
public class DeviceNotFoundException extends RuntimeException {

    private final UUID id;

    public DeviceNotFoundException(UUID id) {
        super("Device not found: " + id);
        this.id = id;
    }
}
